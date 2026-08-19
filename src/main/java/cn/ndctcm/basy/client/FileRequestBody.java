package cn.ndctcm.basy.client;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;

import java.io.File;
import java.io.IOException;

/**
 * 支持文件上传的 RequestBody（适配 OkHttp 4.x API）
 */
public class FileRequestBody extends RequestBody {
    private final File file;
    private final MediaType mediaType;

    public FileRequestBody(File file, MediaType mediaType) {
        this.file = file;
        this.mediaType = mediaType;
    }

    @Override
    public long contentLength() throws IOException {
        return file.length();
    }

    @Override
    public MediaType contentType() {
        return mediaType;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            Buffer buffer = new Buffer();
            byte[] bufferArray = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = fis.read(bufferArray)) != -1) {
                buffer.write(bufferArray, 0, bytesRead);
            }
            sink.writeAll(buffer);
        }
    }
}
