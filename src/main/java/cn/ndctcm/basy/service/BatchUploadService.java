package cn.ndctcm.basy.service;

import cn.ndctcm.basy.client.ApiUnreachableException;
import cn.ndctcm.basy.client.BasyApiClient;
import cn.ndctcm.basy.config.AppConfig;
import cn.ndctcm.basy.model.LogItem;
import cn.ndctcm.basy.model.LogListResult;
import cn.ndctcm.basy.model.UploadResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 批量上传编排服务
 *
 * 执行流程:
 * 0. 运行开始时先确认 uploaded 目录中上次遗留的文件（使用与步骤 3/4/5 相同的匹配与判定规则）:
 *    满足成功条件迁入 success 目录；不满足成功条件放回原上传目录等待重新上传
 *
 * 并行任务: 启动时同步开启预检线程 ProcessedFilePrechecker，循环调用接口 2 logList 匹配
 * 待上传目录中的文件，平台已有成功记录(totalCount>0 且 errorCount=0)的直接移入 success 目录
 * 跳过上传；上传循环结束后停止预检线程
 * 针对每个待上传文件:
 * 1. 调用接口 1 uploadFile 上传文件
 * 2. 上传解析成功后将文件迁移至同级 uploaded 目录（介于待上传与成功之间的中间状态）
 * 3. 轮询接口 2 logList，按 oldFileName 匹配（成功优先：存在任意一条成功记录即成功，
 *    同名后续重复上传的失败记录不影响判定；无成功记录时取最新一条判定），匹配到记录即返回
 * 4. 匹配记录满足 totalCount>0 且 errorCount=0 时视为成功，将文件从 uploaded 迁移至同级 success 目录
 * 5. 匹配记录不满足成功条件（totalCount=0 或 errorCount>0）时视为失败：errorCount>0 且有 logId 时
 *    调用接口 3 getFileErrorLog 下载异常日志到 errorLogs 目录，文件从 uploaded 放回原上传目录等待重新上传
 * 6. 上传阶段因网络问题（连接超时/被拒绝、SSL 握手失败等）导致接口调不通时，文件保留在待上传目录，
 *    不迁入 fail；仅当平台明确返回上传失败时才迁入 fail
 * 7. logList 接口调不通或轮询超时（未匹配到记录）时文件保留在 uploaded 目录，不做迁移
 */
public class BatchUploadService {

    private final AppConfig config;
    private final BasyApiClient client;

    /** 与预检线程共享的协调锁（检查+移动/登记 均在锁内完成） */
    private final Object fileCoordLock = new Object();
    /** 正在上传中的文件名集合：主线程上传前登记、finally 移除，预检线程据此跳过 */
    private final Set<String> uploadingNames = new HashSet<>();

    /** 直接查询失败日志时的分页大小（全量遍历场景下减少请求次数） */
    private static final int FAILED_QUERY_PAGE_SIZE = 50;

    public BatchUploadService(AppConfig config) {
        this.config = config;
        this.client = new BasyApiClient(config);
    }

    /**
     * 执行批量上传
     */
    public void execute() {
        // 预检线程：与上传并行，循环调用 logList，
        // 将平台已成功处理的待上传文件直接移入 success 目录，跳过上传
        ProcessedFilePrechecker prechecker = new ProcessedFilePrechecker(
                config, client, fileCoordLock, uploadingNames);
        prechecker.start();
        try {
            // 0. 确认 uploaded 目录中上次运行遗留的文件（成功迁入 success，不满足成功条件放回待上传目录重新上传）
            reconfirmUploadedFiles();

            // 1. 扫描待上传文件
            List<File> files = scanFiles();
            if (files.isEmpty()) {
                System.out.println("未找到待上传文件");
                System.out.println("请将文件放置在目录: " + new File(config.getUploadFileDir()).getAbsolutePath());
                System.out.println("文件扩展名: " + config.getUploadFileExtension());
                return;
            }

            System.out.println("共找到 " + files.size() + " 个待上传文件:\n");
            for (int i = 0; i < files.size(); i++) {
                System.out.println("  [" + (i + 1) + "] " + files.get(i).getName()
                        + " (" + formatSize(files.get(i).length()) + ")");
            }
            System.out.println();

            // 2. 确保输出目录存在
            ensureDir(config.getDownloadErrorLogDir());

            // 3. 逐个上传
            int successCount = 0;
            int failCount = 0;
            int pendingCount = 0;
            int skipCount = 0;
            for (int i = 0; i < files.size(); i++) {
                File file = files.get(i);
                System.out.println("========== [" + (i + 1) + "/" + files.size() + "] "
                        + file.getName() + " ==========");
                // 上传前协调：预检线程可能已将该文件直接移入 success 目录
                if (!claimForUpload(file)) {
                    System.out.println("  已被预检线程移入 success 目录（平台已有成功记录），跳过上传");
                    System.out.println();
                    continue;
                }
                try {
                    ProcessResult result = processFile(file);
                    switch (result) {
                        case SUCCESS:
                            successCount++;
                            break;
                        case FAIL:
                            failCount++;
                            break;
                        case SKIPPED:
                            skipCount++;
                            break;
                        default:
                            pendingCount++;
                            break;
                    }
                } catch (Exception e) {
                    failCount++;
                    System.err.println("处理文件 " + file.getName() + " 时出错: " + e.getMessage());
                } finally {
                    synchronized (fileCoordLock) {
                        uploadingNames.remove(file.getName());
                    }
                }
                System.out.println();
            }

            // 4. 上传循环结束后先停止预检线程，再输出汇总（保证计数终值与可见性）
            stopPrechecker(prechecker);

            // 5. 汇总
            System.out.println("========== 批量上传完成 ==========");
            System.out.println("总计: " + files.size() + " 个文件");
            System.out.println("成功: " + successCount);
            System.out.println("失败: " + failCount);
            System.out.println("待确认(保留在 uploaded 目录): " + pendingCount);
            System.out.println("网络问题跳过(保留在待上传目录): " + skipCount);
            int precheckMovedCount = prechecker.getMovedCount();
            if (precheckMovedCount > 0) {
                System.out.println("平台已处理直接成功: " + precheckMovedCount
                        + " 个（由预检线程直接移入 success，未重复上传）");
            }
            if (failCount > 0) {
                System.out.println("请检查上方日志了解失败原因，失败文件已保留在待上传目录，下次运行将重新上传");
            }
            if (pendingCount > 0) {
                System.out.println("待确认文件已保留在 uploaded 目录，logList 接口恢复后可重新确认");
            }
            if (skipCount > 0) {
                System.out.println("因网络问题未上传的文件已保留在待上传目录，网络恢复后重新运行即可再次上传");
            }
        } finally {
            // 兜底停止（含"未找到待上传文件"提前返回路径；shutdown 幂等）
            stopPrechecker(prechecker);
        }
    }

    /**
     * 上传前在协调锁内登记文件：若已被预检线程移入 success（文件不存在）返回 false 跳过上传；
     * 否则登记进 uploadingNames（预检线程据此跳过该文件），由调用方在 finally 中移除
     */
    private boolean claimForUpload(File file) {
        synchronized (fileCoordLock) {
            if (!file.exists()) {
                return false;
            }
            uploadingNames.add(file.getName());
            return true;
        }
    }

    /**
     * 停止预检线程（幂等）：置停止标志、打断休眠并等待退出
     */
    private static void stopPrechecker(ProcessedFilePrechecker prechecker) {
        try {
            prechecker.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 确认 uploaded 目录中上次运行遗留的文件
     * 使用与步骤2相同的匹配与判定规则（pollLogList 成功优先匹配：任意一条成功记录即成功）:
     * - 满足成功条件(totalCount>0 且 errorCount=0) → 迁入 success 目录
     * - 不满足成功条件 → 下载异常日志后放回原上传目录，等待重新上传
     * - 未匹配到记录(轮询超时)或 logList 接口调不通 → 保留在 uploaded 目录待下次确认
     */
    private void reconfirmUploadedFiles() {
        List<File> uploadedFiles = scanUploadedDir();
        if (uploadedFiles.isEmpty()) {
            return;
        }

        System.out.println("========== 确认 uploaded 目录遗留文件: " + uploadedFiles.size() + " 个 ==========\n");
        int successCount = 0;
        int failCount = 0;
        int pendingCount = 0;
        for (File file : uploadedFiles) {
            System.out.println("---------- " + file.getName() + " ----------");
            ProcessResult result = confirmUploadedFile(file);
            switch (result) {
                case SUCCESS:
                    successCount++;
                    break;
                case FAIL:
                    failCount++;
                    break;
                default:
                    pendingCount++;
                    break;
            }
            System.out.println();
        }
        System.out.println("uploaded 目录确认完成: 成功 " + successCount
                + " 个，放回待上传 " + failCount + " 个，待确认 " + pendingCount + " 个");
        System.out.println();
    }

    /**
     * 直接查询平台上的失败日志（不经过上传流程）
     *
     * 分页遍历 logList 接口的全部记录，筛选出平台明确返回失败的记录
     * （isSucceed 有明确值且≠1），逐条调用 getFileErrorLog
     * 获取失败日志详情并保存到 errorlogs 目录
     *
     * @param maxCount 最多查询的失败记录条数，≤0 表示查询全部
     */
    public void queryFailedLogs(int maxCount) {
        System.out.println("========== 直接查询失败日志 ==========");
        System.out.println("查询范围: " + (maxCount > 0 ? "最近 " + maxCount + " 条失败记录" : "全部失败记录"));
        List<LogItem> failedItems;
        try {
            failedItems = fetchFailedItems(maxCount);
        } catch (IOException e) {
            System.err.println("查询日志列表失败(logList 接口调不通): " + e.getMessage());
            return;
        }

        if (failedItems.isEmpty()) {
            System.out.println("未查询到失败记录（无 isSucceed≠1 的记录）");
            return;
        }

        System.out.println("共查询到 " + failedItems.size() + " 条失败记录:\n");

        Path errorLogDir = getErrorLogDir();
        ensureDir(errorLogDir.toString());

        int savedCount = 0;
        for (int i = 0; i < failedItems.size(); i++) {
            LogItem item = failedItems.get(i);
            System.out.println("---------- [" + (i + 1) + "/" + failedItems.size() + "] "
                    + item.getOldFileName() + " ----------");
            System.out.println("  logId:      " + item.getLogId());
            System.out.println("  uploadDate: " + item.getUploadDate());
            System.out.println("  isSucceed:  " + item.getIsSucceed());
            System.out.println("  errorCount: " + item.getErrorCount());
            System.out.println("  totalCount: " + item.getTotalCount());

            if (item.getLogId() == null || item.getLogId().isEmpty()) {
                System.err.println("  ⚠ 该记录无 logId，无法获取失败日志详情");
                System.out.println();
                continue;
            }
            try {
                String errorLog = getFileErrorLogWithRetry(item.getLogId());
                if (errorLog == null || errorLog.trim().isEmpty()) {
                    // errorCount=0 的失败记录往往无错误明细，不保存空文件
                    System.out.println("  服务端未返回该记录的详细失败日志内容（可能无错误明细）");
                } else {
                    String savePath = saveFailedLog(item, errorLog, errorLogDir);
                    System.out.println("  失败日志已保存至：" + savePath);
                    // 打印前200字符预览
                    String preview = errorLog.length() > 200
                            ? errorLog.substring(0, 200) + "..." : errorLog;
                    System.out.println("  日志预览: " + preview);
                    savedCount++;
                }
            } catch (IOException e) {
                System.err.println("  获取失败日志详情出错: " + e.getMessage());
            }
            System.out.println();
        }

        System.out.println("========== 查询失败日志完成 ==========");
        System.out.println("总计: " + failedItems.size() + " 条失败记录");
        System.out.println("已保存失败日志: " + savedCount + " 个");
        System.out.println("保存目录: " + errorLogDir.toAbsolutePath());
    }

    /**
     * 分页遍历 logList 全部记录，收集平台明确返回失败的记录
     * 失败判定：isSucceed 有明确值且≠1（涵盖分析完成但失败、分析失败两种终态）
     *
     * @param maxCount 最多收集的失败记录条数，≤0 表示收集全部
     */
    private List<LogItem> fetchFailedItems(int maxCount) throws IOException {
        List<LogItem> failedItems = new ArrayList<>();
        int limit = FAILED_QUERY_PAGE_SIZE;
        int page = 1;
        int totalPages = Integer.MAX_VALUE;

        while (page <= totalPages) {
            LogListResult result = logListWithRetry(page, limit);
            if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
                break;
            }
            if (page == 1) {
                // 根据总数计算总页数
                totalPages = (result.getTotal() + limit - 1) / limit;
            }
            failedItems.addAll(filterFailedItems(result.getRows()));

            // 已收集够指定数量，截断后停止翻页
            if (maxCount > 0 && failedItems.size() >= maxCount) {
                if (failedItems.size() > maxCount) {
                    failedItems = new ArrayList<>(failedItems.subList(0, maxCount));
                }
                break;
            }
            page++;
        }
        return failedItems;
    }

    /**
     * 带重试的日志列表查询
     * 弱网环境下 logList 接口易断流或返回空响应，翻页中途失败会导致整个查询中止，故加重试
     *
     * @param page  页码
     * @param limit 每页条数
     */
    private LogListResult logListWithRetry(int page, int limit) throws IOException {
        // retryCount=-1 表示无限重试
        int maxAttempts = config.getRetryCount() < 0 ? Integer.MAX_VALUE : config.getRetryCount() + 1;
        IOException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LogListResult result = client.logList(page, limit);
                if (result != null) {
                    return result;
                }
                // 服务端偶发返回 HTTP 200 但空响应体，视为一次失败尝试
                lastError = new IOException("logList 返回空响应");
            } catch (IOException e) {
                lastError = e;
            }
            if (attempt < maxAttempts) {
                System.err.println("  第 " + attempt + " 次查询日志列表失败: "
                        + lastError.getMessage() + "，3秒后重试...");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw lastError;
                }
            }
        }
        throw lastError;
    }

    /**
     * 带重试的失败日志详情查询
     *
     * @param logId 日志 ID
     */
    private String getFileErrorLogWithRetry(String logId) throws IOException {
        // retryCount=-1 表示无限重试
        int maxAttempts = config.getRetryCount() < 0 ? Integer.MAX_VALUE : config.getRetryCount() + 1;
        IOException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return client.getFileErrorLog(logId);
            } catch (IOException e) {
                lastError = e;
            }
            if (attempt < maxAttempts) {
                System.err.println("  第 " + attempt + " 次获取失败日志详情失败: "
                        + lastError.getMessage() + "，3秒后重试...");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw lastError;
                }
            }
        }
        throw lastError;
    }

    /**
     * 从日志列表中筛选失败记录
     *
     * 平台实际数据中失败记录存在两种终态（均以 isSucceed≠1 为失败标志）:
     * - isAnalysis=1 且 isSucceed≠1: 分析完成但结果失败
     * - isAnalysis=2 且 isSucceed≠1: 分析失败（如 totalCount=0 的异常终态）
     *
     * isSucceed 为空/null 的记录视为尚未分析，不算失败
     */
    private List<LogItem> filterFailedItems(List<LogItem> rows) {
        List<LogItem> failed = new ArrayList<>();
        for (LogItem item : rows) {
            if (item.getIsSucceed() != null && !item.getIsSucceed().isEmpty()
                    && !"1".equals(item.getIsSucceed())) {
                failed.add(item);
            }
        }
        return failed;
    }

    /**
     * 保存失败日志详情，文件名带 logId 避免同名文件多条记录相互覆盖
     */
    private String saveFailedLog(LogItem item, String content, Path saveDir) throws IOException {
        String fileName = item.getOldFileName() != null ? item.getOldFileName() : "unknown";
        String baseName = fileName;
        int dotIdx = baseName.lastIndexOf('.');
        if (dotIdx > 0) {
            baseName = baseName.substring(0, dotIdx);
        }
        Path path = saveDir.resolve(baseName + "_" + item.getLogId() + "_error.txt");
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path.toAbsolutePath().toString();
    }

    /**
     * 处理单个文件: 上传 -> 迁移至 uploaded 中间目录 -> logList 匹配后按 totalCount/errorCount 判定迁移
     * 上传阶段因网络问题导致接口调不通时，文件保留在待上传目录，不迁入 fail；
     * logList 匹配到记录（oldFileName 相同，成功优先：任意一条成功记录即成功）后
     * 迁入 success 目录，否则下载异常日志后从 uploaded 放回原上传目录等待重新上传；
     * logList 接口调不通或轮询超时（未匹配到记录）时文件保留在 uploaded 目录，不做迁移
     */
    private ProcessResult processFile(File file) throws Exception {
        // ---- 步骤1: 上传文件 (接口1) ----
        System.out.println("[步骤1] 上传文件...");
        UploadResult uploadResult;
        try {
            uploadResult = uploadWithRetry(file);
        } catch (ApiUnreachableException e) {
            // 网络问题导致上传失败：平台未返回明确结论，文件保留在待上传目录，不迁入 fail
            System.err.println("  上传失败(网络问题，接口调不通): " + e.getMessage());
            System.err.println("  ⏸ 文件保留在待上传目录，待网络恢复后重新上传: " + file.getAbsolutePath());
            return ProcessResult.SKIPPED;
        } catch (Exception e) {
            System.err.println("  上传失败: " + e.getMessage());
            moveToFailDir(file);
            return ProcessResult.FAIL;
        }
        if (uploadResult == null || !uploadResult.isSuccess()) {
            String msg = uploadResult != null ? uploadResult.getMsg() : "无响应";
            System.err.println("  上传失败: " + msg);
            moveToFailDir(file);
            return ProcessResult.FAIL;
        }
        System.out.println("  上传接口返回成功：" + uploadResult.getMsg());

        // ---- 上传解析成功: 先迁移至 uploaded 中间目录（介于待上传与成功之间），等待 logList 确认 ----
        File uploadedFile = moveToUploadedDir(file);

        // ---- 步骤 2: 查询日志列表并按 totalCount/errorCount 判定 (接口 2) ----
        System.out.println("[步骤2] 查询日志列表...");
        ProcessResult confirmResult = confirmUploadedFile(uploadedFile);
        if (confirmResult == ProcessResult.SUCCESS) {
            System.out.println("\n  🎉 本批次处理完成！\n");
        }
        return confirmResult;
    }

    /**
     * 对位于 uploaded 目录中的文件进行 logList 确认，并按判定结果迁移:
     * - 满足成功条件(totalCount>0 且 errorCount=0) → 迁入同级 success 目录
     * - 不满足成功条件(totalCount=0 或 errorCount>0) → 下载异常日志后放回原上传目录，等待重新上传
     * - 未匹配到记录(轮询超时)或 logList 接口调不通 → 保留在 uploaded 目录，不做迁移
     */
    private ProcessResult confirmUploadedFile(File uploadedFile) {
        LogItem logItem;
        try {
            logItem = pollLogList(uploadedFile.getName());
        } catch (Exception e) {
            // logList 接口调不通，未获得明确结果：文件保留在 uploaded 目录，不做迁移
            System.err.println("  查询日志列表出错(logList 接口调不通): " + e.getMessage());
            System.err.println("  ⏸ 文件保留在 uploaded 目录，待接口恢复后重新确认: "
                    + uploadedFile.getAbsolutePath());
            return ProcessResult.PENDING;
        }
        if (logItem == null) {
            // 轮询超时未匹配到记录：文件保留在 uploaded 目录，不做迁移
            System.err.println("  未在日志列表中找到该文件的记录（轮询超时）");
            System.err.println("  ⏸ 文件保留在 uploaded 目录，待下次确认: "
                    + uploadedFile.getAbsolutePath());
            return ProcessResult.PENDING;
        }
        System.out.println("  找到日志记录:");
        System.out.println("    logId:      " + logItem.getLogId());
        System.out.println("    uploadDate: " + logItem.getUploadDate());
        System.out.println("    isAnalysis: " + logItem.getIsAnalysis());
        System.out.println("    isSucceed:  " + logItem.getIsSucceed());
        System.out.println("    errorCount: " + logItem.getErrorCount());
        System.out.println("    totalCount: " + logItem.getTotalCount());

        // 判定规则: 匹配到记录后，totalCount>0 且 errorCount=0 视为成功
        if (logItem.getTotalCount() > 0 && logItem.getErrorCount() == 0) {
            System.out.println("  ✅ 文件处理成功！\n");
            // logList 查询确认成功后，将文件从 uploaded 迁移至同级 success 目录
            moveToSuccessDir(uploadedFile);
            return ProcessResult.SUCCESS;
        }

        // 不满足成功条件（totalCount=0 或 errorCount>0）：下载异常日志后放回原上传目录等待重新上传
        System.err.println("  ❗ 判定失败: totalCount=" + logItem.getTotalCount()
                + ", errorCount=" + logItem.getErrorCount() + "（成功需 totalCount>0 且 errorCount=0）");

        // ---- 下载异常日志 (接口 3)，失败记录保留错误明细供排查 ----
        if (logItem.getErrorCount() > 0 && logItem.getLogId() != null
                && !logItem.getLogId().isEmpty()) {
            System.out.println("  下载异常日志...");
            try {
                String errorLog = getFileErrorLogWithRetry(logItem.getLogId());
                if (errorLog == null || errorLog.trim().isEmpty()) {
                    System.out.println("  服务端未返回该记录的详细失败日志内容");
                } else {
                    Path errorLogDir = getErrorLogDir();
                    ensureDir(errorLogDir.toString());
                    String savePath = saveErrorLog(uploadedFile.getName(), errorLog, errorLogDir);
                    System.out.println("  异常日志已保存至：" + savePath);
                    // 打印前200字符预览
                    String preview = errorLog.length() > 200
                            ? errorLog.substring(0, 200) + "..." : errorLog;
                    System.out.println("  日志预览: " + preview);
                }
            } catch (IOException e) {
                // 异常日志下载失败不影响失败判定结果
                System.err.println("  下载异常日志出错: " + e.getMessage());
            }
        }

        moveBackToUploadDir(uploadedFile);
        return ProcessResult.FAIL;
    }

    /**
     * 带重试的上传
     * retryCount=-1 时无限重试当前文件，直至上传成功（每轮上传失败后等待 3 秒）
     */
    private UploadResult uploadWithRetry(File file) throws IOException, InterruptedException {
        boolean infinite = config.getRetryCount() < 0;
        int maxAttempts = infinite ? Integer.MAX_VALUE : config.getRetryCount() + 1;
        IOException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                System.out.println("  上传中 (第 " + attempt
                        + (infinite ? " 次，无限重试直至成功" : "/" + maxAttempts + " 次") + ")...");
                return client.uploadFile(file, config.getOrgCode());
            } catch (IOException e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    System.err.println("  第 " + attempt + " 次上传失败: " + e.getMessage() + "，3秒后重试...");
                    Thread.sleep(3000);
                }
            }
        }
        throw lastError;
    }

    /**
     * 轮询 logList，直到匹配到文件名对应的记录（成功优先）
     * 成功优先规则：存在任意一条成功记录（totalCount>0 且 errorCount=0）即返回该成功记录，
     * 同名后续重复上传产生的失败记录不影响成功判定；
     * 无成功记录时返回 uploadDate 最新的一条（同名多条），由调用方按 totalCount/errorCount 判定
     *
     * @param fileName 上传的文件名
     * @return 匹配到的 LogItem，超时未匹配到返回 null
     */
    private LogItem pollLogList(String fileName) throws IOException, InterruptedException {
        int interval = config.getLogPollIntervalSeconds();
        int timeout = config.getLogPollTimeoutSeconds();
        long deadline = System.currentTimeMillis() + (long) timeout * 1000;
        int pollCount = 0;

        while (System.currentTimeMillis() < deadline) {
            pollCount++;
            // 等待间隔
            if (pollCount > 1) {
                Thread.sleep(interval * 1000L);
            }

            LogListResult result = client.logList(1, config.getLogPageSize());
            if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
                System.out.println("  第 " + pollCount + " 次轮询: 暂无记录，等待...");
                continue;
            }

            // 成功优先：只要存在任意一条成功记录（totalCount>0 且 errorCount=0）即视为成功，
            // 同名后续重复上传产生的失败记录不影响判定（不再仅看最新一条）
            LogItem successMatch = findSuccessMatch(result.getRows(), fileName);
            if (successMatch != null) {
                System.out.println("  第 " + pollCount + " 次轮询：已匹配到成功记录 (uploadDate="
                        + successMatch.getUploadDate() + ")");
                return successMatch;
            }
            // 无成功记录：按最新一条返回，由调用方判定（匹配到即判定，不等待后续分析结果）
            LogItem latestMatch = findLatestMatch(result.getRows(), fileName);
            if (latestMatch == null) {
                System.out.println("  第 " + pollCount + " 次轮询：未找到匹配记录，等待...");
                continue;
            }
            System.out.println("  第 " + pollCount + " 次轮询：已匹配到记录 (uploadDate="
                    + latestMatch.getUploadDate() + ")");
            return latestMatch;
        }

        return null;
    }

    /**
     * 在日志列表中查找与文件名匹配的最近一条记录
     * 同名文件存在多条时，取 uploadDate 最新的一条
     * 包级静态方法：主流程轮询确认与 ProcessedFilePrechecker 预检共用同一份判定逻辑
     *
     * @param rows     日志列表
     * @param fileName 上传的文件名
     * @return 匹配的 LogItem，无匹配返回 null
     */
    static LogItem findLatestMatch(List<LogItem> rows, String fileName) {
        LogItem latest = null;
        for (LogItem item : rows) {
            if (fileName.equals(item.getOldFileName())) {
                if (latest == null || compareUploadDate(item, latest) > 0) {
                    latest = item;
                }
            }
        }
        return latest;
    }

    /**
     * 在日志列表中查找该文件名的成功记录（totalCount>0 且 errorCount=0，任意一条即命中）
     * 平台只要曾成功处理过该文件即视为已上传成功（用户规则：有一次上传成功即成功，
     * 不再仅看最新一条），同名文件后续重复上传产生的失败记录不影响判定；
     * 预检线程与步骤2确认流程共用
     *
     * @param rows     日志列表
     * @param fileName 上传的文件名
     * @return 命中的成功记录，无成功记录返回 null
     */
    static LogItem findSuccessMatch(List<LogItem> rows, String fileName) {
        for (LogItem item : rows) {
            if (fileName.equals(item.getOldFileName())
                    && item.getTotalCount() > 0 && item.getErrorCount() == 0) {
                return item;
            }
        }
        return null;
    }

    /**
     * 比较两个 LogItem 的 uploadDate 时间先后
     *
     * @return >0 表示 item1 更晚，<0 表示 item2 更晚，0 表示相同
     */
    static int compareUploadDate(LogItem item1, LogItem item2) {
        String date1 = item1.getUploadDate();
        String date2 = item2.getUploadDate();
        if (date1 == null && date2 == null) return 0;
        if (date1 == null) return -1;
        if (date2 == null) return 1;
        return date1.compareTo(date2);
    }

    /**
     * 获取错误日志存放目录（同级目录下的 errorlogs）
     */
    private Path getErrorLogDir() {
        File uploadDir = new File(config.getUploadFileDir());
        File parent = uploadDir.getParentFile();
        if (parent == null) {
            parent = new File(".");
        }
        return Paths.get(parent.getAbsolutePath(), "errorlogs");
    }

    /**
     * 保存异常日志到指定目录
     *
     * @param fileName   原文件名
     * @param content    日志内容
     * @param saveDir    目标目录
     * @return 保存的文件路径
     */
    private String saveErrorLog(String fileName, String content, Path saveDir) throws IOException {
        String baseName = fileName;
        int dotIdx = baseName.lastIndexOf('.');
        if (dotIdx > 0) {
            baseName = baseName.substring(0, dotIdx);
        }
        Path path = saveDir.resolve(baseName + "_error.txt");
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path.toAbsolutePath().toString();
    }

    /**
     * 扫描待上传目录下匹配扩展名的文件
     */
    private List<File> scanFiles() {
        return scanDir(new File(config.getUploadFileDir()));
    }

    /**
     * 扫描 uploaded 目录下待确认的遗留文件
     */
    private List<File> scanUploadedDir() {
        return scanDir(getSiblingDir("uploaded"));
    }

    /**
     * 扫描指定目录下匹配扩展名的文件
     * 按文件名末尾的 年_月_编号 数值从小到大排序，确保按编号从小到大依次上传
     */
    private List<File> scanDir(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return new ArrayList<>();
        }

        String[] extensions = config.getUploadFileExtension().split(",");
        File[] allFiles = dir.listFiles();
        if (allFiles == null) {
            return new ArrayList<>();
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
                .sorted(this::compareByTrailingNumbers)
                .collect(Collectors.toList());
    }

    /**
     * 按文件名末尾数字段（年、月、文件编号）数值比较两个文件的先后
     * 文件名形如 TCMMS61003_高邮县中医院__2025_05_11.zip，末尾数字段依次为年、月、编号；
     * 逐段按数值比较（编号 2 排在编号 10 之前，字典序会颠倒两者），
     * 末尾无数字段的文件排在最后，之间按名称字典序
     */
    private int compareByTrailingNumbers(File f1, File f2) {
        String name1 = f1.getName();
        String name2 = f2.getName();
        long[] nums1 = extractTrailingNumbers(name1);
        long[] nums2 = extractTrailingNumbers(name2);
        if (nums1 == null && nums2 == null) {
            return name1.compareTo(name2);
        }
        if (nums1 == null) {
            return 1;
        }
        if (nums2 == null) {
            return -1;
        }
        int len = Math.min(nums1.length, nums2.length);
        for (int i = 0; i < len; i++) {
            int cmp = Long.compare(nums1[i], nums2[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(nums1.length, nums2.length);
    }

    /**
     * 提取文件名末尾按 _ 分隔的连续数字段
     * 如 TCMMS61003_高邮县中医院__2025_05_11.zip 提取出 [2025, 5, 11]（年、月、编号）；
     * 前缀中的数字（如机构编码 61003）不在末尾连续数字段中，不会被误取
     *
     * @return 末尾数字段数组，末尾无数字段时返回 null
     */
    private long[] extractTrailingNumbers(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
        String[] tokens = baseName.split("_");
        int end = tokens.length;
        while (end > 0 && isNumberToken(tokens[end - 1])) {
            end--;
        }
        if (end == tokens.length) {
            return null;
        }
        long[] numbers = new long[tokens.length - end];
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = Long.parseLong(tokens[end + i]);
        }
        return numbers;
    }

    /**
     * 判断分隔段是否为可解析的纯数字段（仅 ASCII 数字，限 18 位以内避免超出 long 范围）
     */
    private boolean isNumberToken(String token) {
        if (token.isEmpty() || token.length() > 18) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 上传解析成功后，将文件先迁移至 uploaded 中间目录（介于待上传与成功之间），
     * 等待 logList 查询确认成功后再迁移至 success 目录
     */
    private File moveToUploadedDir(File file) {
        return moveFileTo(file, getSiblingDir("uploaded"), "uploaded(待确认)");
    }

    /**
     * logList 确认成功后，将文件迁移至上传目录的同级 success 目录
     */
    private void moveToSuccessDir(File file) {
        moveFileTo(file, getSiblingDir("success"), "success");
    }

    /**
     * logList 判定不满足成功条件后，将文件从 uploaded 目录放回原上传目录，等待重新上传
     */
    private void moveBackToUploadDir(File file) {
        moveFileTo(file, new File(config.getUploadFileDir()), "待上传");
    }

    /**
     * 上传失败后，将文件迁移至上传目录的同级 fail 目录
     */
    private void moveToFailDir(File file) {
        //moveFileTo(file, getSiblingDir("fail"), "fail");
    }

    /**
     * 将文件迁移至目标目录
     *
     * @param file      待迁移文件
     * @param targetDir 目标目录
     * @param dirLabel  目录说明（用于日志展示）
     * @return 迁移成功返回目标位置的新 File，失败返回原 File
     */
    private File moveFileTo(File file, File targetDir, String dirLabel) {
        ensureDir(targetDir.getAbsolutePath());
        File target = new File(targetDir, file.getName());
        if (file.renameTo(target)) {
            System.out.println("  文件已迁移至" + dirLabel + "目录: " + target.getAbsolutePath());
            return target;
        } else {
            System.err.println("  文件迁移失败: " + file.getAbsolutePath() + " -> " + target.getAbsolutePath());
            return file;
        }
    }

    /**
     * 获取上传目录的同级目录
     */
    private File getSiblingDir(String name) {
        File uploadDir = new File(config.getUploadFileDir());
        File parent = uploadDir.getParentFile();
        if (parent == null) {
            parent = new File(".");
        }
        return new File(parent, name);
    }

    private void ensureDir(String dir) {
        File d = new File(dir);
        if (!d.exists()) {
            d.mkdirs();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /** 单文件处理结果 */
    private enum ProcessResult {
        /** logList 确认成功，已迁入 success 目录 */
        SUCCESS,
        /** 平台明确返回上传失败或 logList 判定不满足成功条件，文件留在/放回待上传目录等待重新上传 */
        FAIL,
        /** logList 接口调不通或轮询超时，文件保留在 uploaded 目录待确认 */
        PENDING,
        /** 网络问题导致上传接口调不通，文件保留在待上传目录待下次重试 */
        SKIPPED
    }
}
