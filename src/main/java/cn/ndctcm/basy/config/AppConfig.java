package cn.ndctcm.basy.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 配置加载器
 * 按以下优先级查找 application.properties:
 * 1. 当前目录 ./application.properties
 * 2. config/ 子目录下
 * 3. classpath 内置默认配置
 */
public class AppConfig {

    private final Properties props = new Properties();

    private AppConfig() {
    }

    public static AppConfig load() throws IOException {
        AppConfig config = new AppConfig();

        String[] candidates = {
                "application.properties",
                "config/application.properties",
                "src/main/resources/application.properties"
        };

        boolean loaded = false;
        for (String path : candidates) {
            File file = new File(path);
            if (file.exists()) {
                try (Reader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    config.props.load(reader);
                }
                loaded = true;
                System.out.println("加载配置文件：" + file.getAbsolutePath());
                break;
            }
        }

        if (!loaded) {
            try (InputStream is = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
                if (is != null) {
                    config.props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                    loaded = true;
                    System.out.println("加载配置文件：classpath:application.properties");
                }
            }
        }

        if (!loaded) {
            throw new IOException("未找到配置文件 application.properties，请将其放置在程序运行目录下");
        }

        return config;
    }

    public String getServerUrl() {
        return props.getProperty("server.url", "https://basy.ndctcm.cn:1443").replaceAll("/+$", "");
    }

    public String getOrgCode() {
        return props.getProperty("server.orgCode", "").trim();
    }

    public String getAuthCookie() {
        return props.getProperty("auth.cookie", "").trim();
    }

    public String getAuthToken() {
        return props.getProperty("auth.token", "").trim();
    }

    public String getAuthUsername() {
        return props.getProperty("auth.username", "").trim();
    }

    public String getAuthPassword() {
        return props.getProperty("auth.password", "").trim();
    }

    public Map<String, String> getCustomHeaders() {
        Map<String, String> headers = new HashMap<>();
        String raw = props.getProperty("auth.customHeaders", "").trim();
        if (raw.isEmpty()) {
            return headers;
        }
        for (String pair : raw.split(";")) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            if (idx > 0) {
                headers.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
            }
        }
        return headers;
    }

    public boolean isSslTrustAll() {
        return Boolean.parseBoolean(props.getProperty("ssl.trustAll", "true"));
    }

    public String getUploadFileDir() {
        return props.getProperty("upload.fileDir", "./files");
    }

    public String getUploadFileExtension() {
        return props.getProperty("upload.fileExtension", ".zip");
    }

    public int getTimeoutSeconds() {
        return Integer.parseInt(props.getProperty("upload.timeoutSeconds", "120"));
    }

    public int getRetryCount() {
        return Integer.parseInt(props.getProperty("upload.retryCount", "2"));
    }

    public int getLogPollIntervalSeconds() {
        return Integer.parseInt(props.getProperty("log.pollIntervalSeconds", "5"));
    }

    public int getLogPollTimeoutSeconds() {
        return Integer.parseInt(props.getProperty("log.pollTimeoutSeconds", "300"));
    }

    public int getLogPageSize() {
        return Integer.parseInt(props.getProperty("log.pageSize", "1"));
    }

    public String getDownloadErrorLogDir() {
        return props.getProperty("download.errorLogDir", "./error_logs");
    }
}
