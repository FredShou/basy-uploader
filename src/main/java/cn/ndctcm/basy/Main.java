package cn.ndctcm.basy;

import cn.ndctcm.basy.config.AppConfig;
import cn.ndctcm.basy.service.BatchUploadService;

import java.util.ArrayList;
import java.util.List;

/**
 * Basy 文件批量上传工具 - 程序入口
 *
 * 功能:
 * 1. 批量上传本地 zip 文件至 basy.ndctcm.cn 平台
 * 2. 上传后自动查询日志列表，等待文件分析完成
 * 3. 若存在异常日志，自动下载并保存到本地
 *
 * 使用方法:
 *   java -jar basy-uploader.jar                       # 批量上传
 *   java -jar basy-uploader.jar --query-failed        # 直接查询平台全部失败日志（不执行上传）
 *   java -jar basy-uploader.jar --query-failed 20     # 仅查询最近 20 条失败日志
 *
 * 配置文件:
 *   application.properties（放置在程序运行目录或 config/ 子目录下）
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("  Basy 文件批量上传工具 v1.0.0");
        System.out.println("  目标平台: basy.ndctcm.cn:1443");
        System.out.println("==============================================\n");

        try {
            // 加载配置
            AppConfig config = AppConfig.load();
            printConfigSummary(config);
            System.out.println();

            BatchUploadService service = new BatchUploadService(config);

            // 命令行参数: --query-failed [N] 直接查询平台失败日志（不执行上传，无需 orgCode）
            // N 为可选的最近失败记录条数限制，缺省查询全部
            if (args.length > 0 && "--query-failed".equals(args[0])) {
                int maxCount = 0;
                if (args.length > 1) {
                    try {
                        maxCount = Integer.parseInt(args[1]);
                        if (maxCount <= 0) {
                            System.err.println("[警告] 查询条数需为正整数: " + args[1] + "，将查询全部失败记录");
                            maxCount = 0;
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("[警告] 无效的查询条数参数: " + args[1] + "，将查询全部失败记录");
                    }
                }
                service.queryFailedLogs(maxCount);
            } else {
                // 检查必填项（仅上传流程需要 orgCode）
                if (config.getOrgCode().isEmpty()) {
                    System.err.println("[警告] 未配置 server.orgCode，请在 application.properties 中设置机构编码");
                    System.exit(1);
                }

                // 执行批量上传
                service.execute();
            }

        } catch (Exception e) {
            System.err.println("\n程序运行出错: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 打印配置摘要（隐藏敏感信息）
     */
    private static void printConfigSummary(AppConfig config) {
        System.out.println("【配置摘要】");
        System.out.println("  服务器地址: " + config.getServerUrl());
        System.out.println("  机构编码:   " + config.getOrgCode());
        System.out.println("  文件目录:   " + new java.io.File(config.getUploadFileDir()).getAbsolutePath());
        System.out.println("  文件类型:   " + config.getUploadFileExtension());
        System.out.println("  超时(秒):   " + config.getTimeoutSeconds());
        System.out.println("  重试次数:   " + config.getRetryCount());
        System.out.println("  轮询间隔:   " + config.getLogPollIntervalSeconds() + "s");
        System.out.println("  轮询超时:   " + config.getLogPollTimeoutSeconds() + "s");
        System.out.println("  日志目录:   " + new java.io.File(config.getDownloadErrorLogDir()).getAbsolutePath());
        System.out.println("  SSL信任:    " + config.isSslTrustAll());
        System.out.println("  鉴权方式:   " + getAuthSummary(config));
    }

    /**
     * 获取鉴权方式摘要（隐藏具体值）
     */
    private static String getAuthSummary(AppConfig config) {
        List<String> methods = new ArrayList<>();
        if (!config.getAuthCookie().isEmpty()) {
            methods.add("Cookie");
        }
        if (!config.getAuthToken().isEmpty()) {
            methods.add("Token");
        }
        if (!config.getAuthUsername().isEmpty()) {
            methods.add("BasicAuth");
        }
        if (!config.getCustomHeaders().isEmpty()) {
            methods.add("自定义头(" + config.getCustomHeaders().size() + ")");
        }
        return methods.isEmpty() ? "无（可能为开放接口，如遇401请配置鉴权信息）" : String.join(" + ", methods);
    }
}
