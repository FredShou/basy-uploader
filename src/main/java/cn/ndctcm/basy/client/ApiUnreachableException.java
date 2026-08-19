package cn.ndctcm.basy.client;

import java.io.IOException;

/**
 * 接口调不通异常：请求因网络问题（连接超时/被拒绝、SSL 握手失败、DNS 解析失败等）未能完成，
 * 平台未返回任何业务结论。
 * 此类异常不视为明确失败，相关文件不应迁入 fail 目录，保留在原目录等待下次处理。
 */
public class ApiUnreachableException extends IOException {

    public ApiUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
