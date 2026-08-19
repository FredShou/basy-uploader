package cn.ndctcm.basy.client;

import cn.ndctcm.basy.config.AppConfig;
import cn.ndctcm.basy.model.LogListResult;
import cn.ndctcm.basy.model.UploadResult;
import com.google.gson.Gson;
import okhttp3.*;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 平台 HTTP 客户端，封装三个接口调用:
 * 1. uploadFile  - 上传文件 (POST multipart/form-data)
 * 2. logList     - 查询日志列表 (GET)
 * 3. getFileErrorLog - 获取异常日志 (POST form-urlencoded)
 */
public class BasyApiClient {

    /** 接口基础路径 */
    private static final String API_BASE = "/dhccApi/medicalOrderDataCollection/fileLog";

    private final AppConfig config;
    private final OkHttpClient client;
    private final Gson gson;
    private final okhttp3.logging.HttpLoggingInterceptor loggingInterceptor;

    public BasyApiClient(AppConfig config) {
        this.config = config;
        this.gson = new Gson();
        // 关闭 HTTP 日志拦截器，避免打印二进制文件内容导致乱码
        this.loggingInterceptor = new okhttp3.logging.HttpLoggingInterceptor();
        this.loggingInterceptor.setLevel(okhttp3.logging.HttpLoggingInterceptor.Level.NONE);
        
        this.client = createClient();
    }

    // ==================== OkHttpClient 初始化 ====================

    private OkHttpClient createClient() {
        System.out.println("\n========== OkHttpClient 配置 ==========");
        System.out.println("超时时间：   " + config.getTimeoutSeconds() + "秒");
        System.out.println("重试连接：   " + true);
        System.out.println("SSL 信任所有：" + config.isSslTrustAll());
        System.out.println("鉴权方式：   " + getAuthMethodSummary());
        System.out.println("======================================\n");

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor(loggingInterceptor);  // 添加 HTTP 日志拦截器

        // SSL 信任所有证书（自签名场景）
        if (config.isSslTrustAll()) {
            configureTrustAllSsl(builder);
        }

        // 鉴权拦截器
        builder.addInterceptor(this::addAuthHeaders);

        return builder.build();
    }

    private String getAuthMethodSummary() {
        if (!config.getAuthCookie().isEmpty()) return "Cookie";
        if (!config.getAuthToken().isEmpty()) return "Token";
        if (!config.getAuthUsername().isEmpty()) return "Basic Auth";
        if (!config.getCustomHeaders().isEmpty()) return "自定义头";
        return "无";
    }

    private void configureTrustAllSsl(OkHttpClient.Builder builder) {
        try {
            X509TrustManager trustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            throw new RuntimeException("SSL 信任所有证书配置失败", e);
        }
    }

    /**
     * 鉴权拦截器：为每个请求添加 Cookie / Token / Basic Auth / 自定义头
     */
    private Response addAuthHeaders(Interceptor.Chain chain) throws IOException {
        Request original = chain.request();
        String originalUrl = original.url().toString();
        Request.Builder reqBuilder = original.newBuilder()
                .header("X-Requested-With", "XMLHttpRequest");

        // 方式 1: Cookie 鉴权
        String cookie = config.getAuthCookie();
        if (!cookie.isEmpty()) {
            reqBuilder.header("Cookie", cookie);
        }

        // 方式 2/3: Token 鉴权优先于 Basic Auth
        String token = config.getAuthToken();
        String username = config.getAuthUsername();
        if (!token.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + token);
        } else if (!username.isEmpty()) {
            String credential = Credentials.basic(username, config.getAuthPassword());
            reqBuilder.header("Authorization", credential);
        }

        // 方式 4: 自定义请求头
        for (Map.Entry<String, String> entry : config.getCustomHeaders().entrySet()) {
            reqBuilder.header(entry.getKey(), entry.getValue());
        }

        try {
            Response response = chain.proceed(reqBuilder.build());
            return response;
        } catch (IOException e) {
            System.err.println("\n========== HTTP 请求失败调试信息 ==========");
            System.err.println("URL: " + originalUrl);
            System.err.println("错误类型：" + e.getClass().getSimpleName());
            System.err.println("错误消息：" + e.getMessage());
            
            if (e.getMessage() != null) {
                if (e.getMessage().contains("timeout") || e instanceof java.net.SocketTimeoutException) {
                    System.err.println("\n⚠️  超时错误！可能是：");
                    System.err.println("   1. 服务器响应时间过长");
                    System.err.println("   2. 网络连接问题");
                    System.err.println("   3. 防火墙或代理拦截");
                    System.err.println("   当前超时设置：" + config.getTimeoutSeconds() + "秒");
                }
                
                if (e.getMessage().contains("Connection refused") || 
                    e.getMessage().contains("连接被拒")) {
                    System.err.println("\n❌ 连接被拒绝！可能是：");
                    System.err.println("   1. 服务器未启动或无法访问");
                    System.err.println("   2. 端口不正确（检查是否使用 1443）");
                    System.err.println("   3. 防火墙阻止连接");
                }
                
                if (e.getMessage().contains("Certificate") || 
                    e.getMessage().contains("SSL") ||
                    e.getMessage().contains("handshake")) {
                    System.err.println("\n⚠️  SSL 证书错误！请检查：");
                    System.err.println("   1. ssl.trustAll=true 是否正确配置");
                    System.err.println("   2. 服务器证书是否有效");
                    System.err.println("   3. 尝试使用 http://而不是 https://");
                }
            }
            
            System.err.println("\n========================================\n");
            throw e;
        }
    }

    // ==================== 接口 1: uploadFile ====================

    /**
     * 接口 1: 上传文件
     * POST /dhccApi/medicalOrderDataCollection/fileLog/uploadFile
     * Content-Type: multipart/form-data
     * 参数：file(二进制文件), orgCode(机构编码)
     *
     * @param file    待上传的本地文件
     * @param orgCode 机构编码
     * @return 上传结果 {msg, flag}
     */
    public UploadResult uploadFile(File file, String orgCode) throws IOException {
        System.out.println("  [请求] 正在发送文件...");
        System.out.println("  URL: " + config.getServerUrl() + API_BASE + "/uploadFile");
        System.out.println("  文件名：" + file.getName());
        System.out.println("  文件大小：" + formatSize(file.length()));
        System.out.println("  机构编码：" + orgCode);

        // 猜测 MIME 类型
        String mimeType = URLConnection.guessContentTypeFromName(file.getName());
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }
        
        // 使用正确的文件上传方式（适配 OkHttp 4.x）
        RequestBody fileBody = new FileRequestBody(file, MediaType.parse(mimeType));

        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), fileBody)
                .addFormDataPart("orgCode", orgCode)
                .build();

        Request request = new Request.Builder()
                .url(config.getServerUrl() + API_BASE + "/uploadFile")
                .post(body)
                .build();

        Response response;
        try {
            response = client.newCall(request).execute();
        } catch (IOException e) {
            // 网络问题（连接超时/被拒绝、SSL 握手失败等）：平台未返回任何响应，无法得到明确结论
            throw new ApiUnreachableException("上传接口调不通(网络问题): " + e.getMessage(), e);
        }
        try (Response resp = response) {
            String respBody = resp.body() != null ? resp.body().string() : "";

            System.out.println("  [响应] HTTP 状态码：" + resp.code());
            System.out.println("  [响应内容]: " + respBody);

            if (!resp.isSuccessful()) {
                throw new IOException("上传失败 [HTTP " + resp.code() + "]: " + respBody);
            }
            // 响应可能是 HTML 包装的 JSON，直接用 Gson 解析
            return gson.fromJson(respBody, UploadResult.class);
        }
    }

    // ==================== 接口 2: logList ====================

    /**
     * 接口 2: 查询日志列表
     * GET /dhccApi/medicalOrderDataCollection/fileLog/logList?page={page}&limit={limit}
     *
     * @param page  页码（从 1 开始）
     * @param limit 每页条数
     * @return 日志列表结果
     */
    public LogListResult logList(int page, int limit) throws IOException {
        HttpUrl url = HttpUrl.parse(config.getServerUrl() + API_BASE + "/logList")
                .newBuilder()
                .addQueryParameter("page", String.valueOf(page))
                .addQueryParameter("limit", String.valueOf(limit))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            System.out.println("  [响应] HTTP 状态码：" + response.code());
            System.out.println("  [响应内容]: " + respBody);
            if (!response.isSuccessful()) {
                throw new IOException("查询日志列表失败 [HTTP " + response.code() + "]: " + respBody);
            }
            return gson.fromJson(respBody, LogListResult.class);
        }
    }

    // ==================== 接口 3: getFileErrorLog ====================

    /**
     * 接口 3: 获取文件异常日志
     * POST /dhccApi/medicalOrderDataCollection/fileLog/getFileErrorLog
     * Content-Type: application/x-www-form-urlencoded
     * 参数：logId
     *
     * @param logId 日志 ID（来自接口 2 返回的 rows[].logId）
     * @return 异常日志文本内容
     */
    public String getFileErrorLog(String logId) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("logId", logId)
                .build();

        Request request = new Request.Builder()
                .url(config.getServerUrl() + API_BASE + "/getFileErrorLog")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            System.out.println("  [响应] HTTP 状态码：" + response.code());
            System.out.println("  [响应内容]: " + respBody);
            if (!response.isSuccessful()) {
                throw new IOException("获取异常日志失败 [HTTP " + response.code() + "]: " + respBody);
            }
            return respBody;
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
}
