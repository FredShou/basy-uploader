package cn.ndctcm.basy.service;

import cn.ndctcm.basy.client.BasyApiClient;
import cn.ndctcm.basy.config.AppConfig;
import cn.ndctcm.basy.model.LogItem;
import cn.ndctcm.basy.model.LogListResult;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 待上传文件预检线程
 *
 * 在主流程上传文件的同时并行运行：循环调用接口 2 logList，
 * 用返回的记录匹配待上传目录中尚未上传的文件——若平台已有该文件的成功记录
 * （totalCount>0 且 errorCount=0），直接将文件从待上传目录移入同级 success 目录，
 * 跳过上传，避免重复上传。
 *
 * 典型场景：上次运行时上传接口在客户端视角失败（超时/网络问题），但平台实际已接收
 * 并继续处理成功，文件留在待上传目录——预检发现成功记录后直接标记成功，不再重传。
 *
 * 匹配规则：oldFileName 相同即匹配；判定采用"存在任意一条成功记录
 * （totalCount>0 且 errorCount=0）即视为平台已成功处理"（BatchUploadService.findSuccessMatch）——
 * 同名文件后续重复上传产生的失败记录不影响判定。
 * logList 调不通时仅跳过本轮、不迁移任何文件（网络问题不迁移文件原则），
 * 由下一轮循环天然重试。
 */
public class ProcessedFilePrechecker {

    private final AppConfig config;
    private final BasyApiClient client;
    /** 与主线程共享的协调锁（检查+移动/登记 均在锁内完成） */
    private final Object fileCoordLock;
    /** 与主线程共享的"正在上传中"文件名集合 */
    private final Set<String> uploadingNames;

    private Thread worker;
    private volatile boolean stopRequested = false;
    /** 直接移入 success 的文件数：仅预检线程写，主线程在 shutdown() 之后读取 */
    private int movedCount = 0;

    public ProcessedFilePrechecker(AppConfig config, BasyApiClient client,
                                   Object fileCoordLock, Set<String> uploadingNames) {
        this.config = config;
        this.client = client;
        this.fileCoordLock = fileCoordLock;
        this.uploadingNames = uploadingNames;
    }

    /**
     * 启动预检线程（幂等，重复调用无效）
     */
    public synchronized void start() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        stopRequested = false;
        worker = new Thread(this::runLoop, "logList-precheck");
        worker.start();
    }

    /**
     * 停止预检线程：置停止标志、打断休眠并等待线程退出（幂等，可重复调用）
     */
    public void shutdown() throws InterruptedException {
        stopRequested = true;
        if (worker != null) {
            worker.interrupt();
            worker.join();
        }
    }

    /**
     * 本次运行直接移入 success 的文件数（应在 shutdown() 之后调用）
     */
    public int getMovedCount() {
        return movedCount;
    }

    /**
     * 循环体：首轮立即执行一次，之后每轮间隔 log.pollIntervalSeconds 秒
     */
    private void runLoop() {
        long intervalMillis = config.getLogPollIntervalSeconds() * 1000L;
        while (!stopRequested) {
            try {
                checkOnce();
            } catch (Exception e) {
                // 预检异常只打日志，不影响主上传流程
                System.err.println("[预检] 本轮预检出错: " + e.getMessage());
            }
            if (stopRequested) {
                break;
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 执行一轮预检：扫描待上传目录 → 查询 logList 第 1 页 → 逐文件匹配判定
     * 目录为空时跳过 API 调用；未匹配/平台失败记录一律不动，交给正常上传流程
     */
    private void checkOnce() {
        List<File> pendingFiles = scanPendingFiles();
        if (pendingFiles.isEmpty()) {
            return;
        }

        LogListResult result;
        try {
            result = client.logList(1, config.getLogPageSize());
        } catch (IOException e) {
            // 网络问题不迁移文件：仅提示，下一轮循环再试
            System.err.println("[预检] 查询日志列表失败(logList 接口调不通): " + e.getMessage());
            return;
        }
        List<LogItem> rows = (result == null || result.getRows() == null)
                ? Collections.emptyList() : result.getRows();

        for (File file : pendingFiles) {
            if (stopRequested) {
                break;
            }
            LogItem successMatch = BatchUploadService.findSuccessMatch(rows, file.getName());
            if (successMatch != null) {
                // 平台已成功处理过该文件，直接移入 success，跳过上传
                tryMoveToSuccess(file, successMatch);
            }
            // 无成功记录（含仅有失败记录/未匹配）→ 不动，走正常上传流程
        }
    }

    /**
     * 协调锁内校验（文件仍在待上传目录、不在"上传中"集合、success 目录无同名文件）后，
     * 将文件 renameTo 至同级 success 目录
     */
    private void tryMoveToSuccess(File file, LogItem match) {
        File target;
        synchronized (fileCoordLock) {
            if (uploadingNames.contains(file.getName())) {
                // 主线程正在上传该文件，交由主流程确认
                return;
            }
            if (!file.exists()) {
                // 已被移走
                return;
            }
            File successDir = getSiblingDir("success");
            ensureDir(successDir);
            target = new File(successDir, file.getName());
            if (target.exists()) {
                System.err.println("[预检] success 目录已存在同名文件，跳过迁移避免覆盖: "
                        + target.getAbsolutePath());
                return;
            }
            if (!file.renameTo(target)) {
                System.err.println("[预检] 文件迁移失败: " + file.getAbsolutePath()
                        + " -> " + target.getAbsolutePath());
                return;
            }
        }
        movedCount++;
        System.out.println("[预检] 平台已有该文件的成功记录，跳过上传: " + file.getName());
        System.out.println("[预检]   logId: " + match.getLogId()
                + ", uploadDate: " + match.getUploadDate()
                + ", totalCount: " + match.getTotalCount()
                + ", errorCount: " + match.getErrorCount());
        System.out.println("[预检]   文件已直接迁移至 success 目录: " + target.getAbsolutePath());
    }

    /**
     * 扫描待上传目录中匹配扩展名的文件（预检与顺序无关，不排序）
     */
    private List<File> scanPendingFiles() {
        File dir = new File(config.getUploadFileDir());
        String[] extensions = config.getUploadFileExtension().split(",");
        File[] allFiles = dir.listFiles();
        if (allFiles == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(allFiles)
                .filter(File::isFile)
                .filter(f -> {
                    String name = f.getName().toLowerCase();
                    for (String ext : extensions) {
                        if (name.endsWith(ext.trim().toLowerCase())) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取待上传目录的同级目录（与 BatchUploadService.getSiblingDir 语义一致）
     */
    private File getSiblingDir(String name) {
        File uploadDir = new File(config.getUploadFileDir());
        File parent = uploadDir.getParentFile();
        if (parent == null) {
            parent = new File(".");
        }
        return new File(parent, name);
    }

    private void ensureDir(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
}
