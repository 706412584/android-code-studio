/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.protocol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一 HTTP 基础设施，自动执行 UrlPolicy 校验、设置标准请求头、保证连接断开。
 */
public final class SimpleHttpClient {

    public static final long MAX_RESPONSE_BODY_BYTES = 32L * 1024 * 1024;

    /** 最多跟随的重定向跳数。超过即判定为环或恶意跳转。 */
    private static final int MAX_REDIRECTS = 5;

    private SimpleHttpClient() {
    }

    /**
     * 建立连接并**逐跳**校验重定向，返回已就绪的最终连接（调用方负责读流并 disconnect）。
     *
     * <p><b>为什么不能交给 {@code setInstanceFollowRedirects(true)}</b>：自动跟随只在
     * 初始 URL 上过了一次 {@link UrlPolicy}，之后每一跳都是 JDK 内部直接跳转，
     * 不再回到这里。于是一个正常的公网地址只要回一个
     * {@code 302 Location: http://192.168.1.1/}，请求就会打到内网设备上，
     * 而调用方以为自己在读公网文档。这类「用可信地址做跳板」是 SSRF 的常规形态。
     *
     * <p>手动跟随的代价是要自己处理方法与请求体的关系，规则如下：
     * <ul>
     *   <li>303，以及 301/302 对 POST——按 HTTP 语义降级为 GET 并丢弃请求体
     *       （浏览器与 JDK 都这么做）；</li>
     *   <li>307/308——保持原方法与请求体不变。</li>
     * </ul>
     *
     * <p>Location 可能是相对路径，因此用 {@code new URL(当前地址, Location)} 解析，
     * 这样相对跳转也能被正确拼成绝对地址再校验。
     */
    private static HttpURLConnection openFollowingRedirects(
            String url, String method, byte[] body, int connectTimeoutMs, int readTimeoutMs,
            Map<String, String> headers) throws Exception {
        String currentUrl = url;
        String currentMethod = method;
        byte[] currentBody = body;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            // 每一跳都重新校验：这是本方法存在的全部意义。
            String safeUrl = UrlPolicy.requireHttpOrLocalCleartextUrl(currentUrl, "URL");
            java.net.URL target = new java.net.URL(safeUrl);
            HttpURLConnection connection = null;
            boolean keep = false;
            try {
                connection = (HttpURLConnection) target.openConnection(AppProxy.proxyFor(target.getHost()));
                connection.setRequestMethod(currentMethod);
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                // 关闭自动跟随，改由本循环逐跳校验
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "LineCode/1.0");
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
                if (currentBody != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(currentBody.length);
                    OutputStream output = connection.getOutputStream();
                    try {
                        output.write(currentBody);
                    } finally {
                        output.close();
                    }
                }
                int code = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                boolean isRedirect = code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
                if (!isRedirect || location == null || location.trim().isEmpty()) {
                    // 非重定向（或 3xx 但没有 Location）：交给调用方处理
                    keep = true;
                    return connection;
                }
                if (hop == MAX_REDIRECTS) {
                    throw new Exception("Too many redirects (>" + MAX_REDIRECTS + ")");
                }
                // 解析相对 Location，并在下一轮循环开头重新校验
                currentUrl = new java.net.URL(target, location.trim()).toString();
                boolean keepMethod =
                        code == 307 || code == 308 || !"POST".equalsIgnoreCase(currentMethod);
                if (!keepMethod) {
                    currentMethod = "GET";
                    currentBody = null;
                }
            } finally {
                if (!keep && connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw new Exception("Too many redirects (>" + MAX_REDIRECTS + ")");
    }

    public static String postJson(String url, String jsonBody, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return postJson(url, jsonBody, connectTimeoutMs, readTimeoutMs, Collections.<String, String>emptyMap());
    }

    public static String postJson(String url, String jsonBody, int connectTimeoutMs, int readTimeoutMs,
                                  Map<String, String> headers) throws Exception {
        Request request = new Request(url, "POST", jsonBody);
        request.connectTimeoutMs = connectTimeoutMs;
        request.readTimeoutMs = readTimeoutMs;
        request.headers.put("Content-Type", "application/json");
        request.headers.put("Accept", "application/json");
        if (headers != null) {
            request.headers.putAll(headers);
        }
        Response response = execute(request);
        if (response.code < 200 || response.code >= 300) {
            throw new Exception("HTTP " + response.code + ": " + response.body);
        }
        return response.body;
    }

    public static String get(String url, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return get(url, connectTimeoutMs, readTimeoutMs, Collections.<String, String>emptyMap());
    }

    public static String get(String url, int connectTimeoutMs, int readTimeoutMs,
                             Map<String, String> headers) throws Exception {
        Request request = new Request(url, "GET", null);
        request.connectTimeoutMs = connectTimeoutMs;
        request.readTimeoutMs = readTimeoutMs;
        request.headers.put("Accept", "application/json");
        if (headers != null) {
            request.headers.putAll(headers);
        }
        Response response = execute(request);
        if (response.code < 200 || response.code >= 300) {
            throw new Exception("HTTP " + response.code + ": " + response.body);
        }
        return response.body;
    }

    public static DownloadResult download(String url, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return download(url, connectTimeoutMs, readTimeoutMs, (int) MAX_RESPONSE_BODY_BYTES);
    }

    /** 下载进度回调：read 为已读字节，total 为 Content-Length（未知时 0）。 */
    public interface DownloadProgressListener {
        void onProgress(long read, long total);
    }

    /** 带进度回调的下载（大文件场景：rootfs 等）。 */
    public static DownloadResult downloadWithProgress(String url, int connectTimeoutMs, int readTimeoutMs,
                                                      DownloadProgressListener progress) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection =
                    openFollowingRedirects(url, "GET", null, connectTimeoutMs, readTimeoutMs, null);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP download failed: " + code);
            }
            long total = connection.getContentLength();
            String mimeType = connection.getContentType();
            if (mimeType == null || mimeType.length() == 0) {
                mimeType = "application/octet-stream";
            } else {
                int semicolon = mimeType.indexOf(';');
                if (semicolon > 0) {
                    mimeType = mimeType.substring(0, semicolon).trim();
                }
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4 * 1024 * 1024);
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[65536];
                long read = 0;
                int n;
                while ((n = input.read(chunk)) > 0) {
                    if (read + n > MAX_RESPONSE_BODY_BYTES) {
                        throw new Exception("download exceeds limit: " + MAX_RESPONSE_BODY_BYTES);
                    }
                    buffer.write(chunk, 0, n);
                    read += n;
                    if (progress != null) {
                        progress.onProgress(read, total);
                    }
                }
            }
            return new DownloadResult(mimeType, buffer.toByteArray());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static DownloadResult download(String url, int connectTimeoutMs, int readTimeoutMs, int maxBytes) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection =
                    openFollowingRedirects(url, "GET", null, connectTimeoutMs, readTimeoutMs, null);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP download failed: " + code);
            }
            String mimeType = connection.getContentType();
            if (mimeType == null || mimeType.length() == 0) {
                mimeType = "application/octet-stream";
            } else {
                int semicolon = mimeType.indexOf(';');
                if (semicolon > 0) {
                    mimeType = mimeType.substring(0, semicolon).trim();
                }
            }
            return new DownloadResult(mimeType, readBytes(connection.getInputStream(), maxBytes));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static Response execute(Request request) throws Exception {
        HttpURLConnection connection = null;
        try {
            byte[] requestBytes = request.bodyBytes != null
                    ? request.bodyBytes
                    : request.body == null ? null : request.body.getBytes(StandardCharsets.UTF_8);
            // 逐跳校验重定向；请求头与请求体交给它按跳转语义决定是否保留
            connection =
                    openFollowingRedirects(
                            request.url,
                            request.method,
                            requestBytes,
                            request.connectTimeoutMs,
                            request.readTimeoutMs,
                            request.headers);
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String contentType = connection.getContentType();
            String message = connection.getResponseMessage();
            String body = readStream(stream);
            LinkedHashMap<String, String> responseHeaders = new LinkedHashMap<>();
            Map<String, java.util.List<String>> headerFields = connection.getHeaderFields();
            if (headerFields != null) {
                for (Map.Entry<String, java.util.List<String>> entry : headerFields.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                        continue;
                    }
                    responseHeaders.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            return new Response(code, message, contentType, body, responseHeaders);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static String readStream(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BODY_BYTES) {
                    throw new Exception("Response body too large, current limit is " + (MAX_RESPONSE_BODY_BYTES / 1024 / 1024) + " MB.");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            input.close();
        }
    }

    public static byte[] readBytes(InputStream input, int maxBytes) throws Exception {
        if (input == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new Exception("Data too large, current limit is " + (maxBytes / 1024 / 1024) + " MB.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    public static final class Request {
        public String url;
        public String method;
        public String body;
        public byte[] bodyBytes;
        public int connectTimeoutMs = 15000;
        public int readTimeoutMs = 30000;
        public final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

        public Request(String url, String method, String body) {
            this.url = url;
            this.method = method;
            this.body = body;
        }
    }

    public static final class Response {
        public final int code;
        public final String message;
        public final String contentType;
        public final String body;
        public final Map<String, String> headers;

        public Response(int code, String message, String contentType, String body) {
            this(code, message, contentType, body, Collections.<String, String>emptyMap());
        }

        public Response(int code, String message, String contentType, String body, Map<String, String> headers) {
            this.code = code;
            this.message = message == null ? "" : message;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body == null ? "" : body;
            this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
        }
    }

    public static final class DownloadResult {
        public final String mimeType;
        public final byte[] bytes;

        public DownloadResult(String mimeType, byte[] bytes) {
            this.mimeType = mimeType == null || mimeType.length() == 0 ? "image/png" : mimeType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }
    }
}
