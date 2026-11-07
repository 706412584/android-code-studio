/*
 * This file is part of AndroidCodeStudio.
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.engine;

import com.tom.rv2ide.ai.protocol.AppProxy;
import com.tom.rv2ide.ai.protocol.SimpleHttpClient;
import com.tom.rv2ide.ai.protocol.UrlPolicy;
import com.tom.rv2ide.ai.tool.HttpPort;
import com.tom.rv2ide.ai.tool.HttpRequestPort;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

/**
 * 用内核的 {@link SimpleHttpClient} 实现工具层的 {@link HttpPort}。
 *
 * <p>从 ACS 的 {@code com.tom.rv2ide.artificial.agent.AppHttpPort} 迁入，逻辑逐字保留——
 * 它只依赖 {@code ai-protocol}，本来就不含任何 ACS 类型。
 *
 * <p><b>为什么不直接在工具里用 SimpleHttpClient</b>：{@code ai-tool} 刻意不依赖
 * {@code ai-protocol}（工具模块要能在 JVM 上单测）。这里做适配，使安全边界仍只有一处——
 * URL 策略、代理与超时都由 {@code SimpleHttpClient} 内部处理，
 * 不会因为新增一个工具就绕过 {@link UrlPolicy}。
 */
public final class AppHttpPort implements HttpPort, HttpRequestPort {

  /** 网页抓取的超时。比模型调用的超时短：用户在看结果，等太久不如早点失败。 */
  private static final int CONNECT_TIMEOUT_MS = 15_000;

  private static final int READ_TIMEOUT_MS = 30_000;

  /** SSE 长连接的读超时。事件间隔可能很长，给足余量。 */
  private static final int SSE_READ_TIMEOUT_MS = 600_000;

  @Override
  public String getText(String url, Map<String, String> headers) throws Exception {
    return SimpleHttpClient.get(
        url,
        CONNECT_TIMEOUT_MS,
        READ_TIMEOUT_MS,
        headers == null ? Collections.<String, String>emptyMap() : headers);
  }

  @Override
  public BinaryResponse getBytes(String url, Map<String, String> headers) throws Exception {
    SimpleHttpClient.DownloadResult result =
        SimpleHttpClient.download(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    return new BinaryResponse(result.mimeType, result.bytes);
  }

  @Override
  public TextResponse postJson(String url, String jsonBody, Map<String, String> headers)
      throws Exception {
    // 走 execute 而非 postJson 便捷方法：后者丢弃响应头，而 MCP 的会话 id 正是从
    // Mcp-Session-Id 响应头下发的，拿不到就无法建立会话。
    SimpleHttpClient.Request request = new SimpleHttpClient.Request(url, "POST", jsonBody);
    request.connectTimeoutMs = CONNECT_TIMEOUT_MS;
    request.readTimeoutMs = READ_TIMEOUT_MS;
    request.headers.put("Content-Type", "application/json");
    request.headers.put("Accept", "application/json");
    if (headers != null) {
      request.headers.putAll(headers);
    }
    SimpleHttpClient.Response response = SimpleHttpClient.execute(request);
    if (response.code < 200 || response.code >= 300) {
      throw new Exception("HTTP " + response.code + ": " + response.body);
    }
    return new TextResponse(response.body, response.headers);
  }

  /**
   * 通用请求：任意方法/头/体/超时，**非 2xx 也返回**（不抛），供 {@code http_request} 工具使用。
   *
   * <p>安全边界仍在 {@code SimpleHttpClient.execute} 里（逐跳 URL 校验 + 代理），
   * 自己再建一条连接就等于复制出第二处安全边界。
   */
  @Override
  public Response request(
      String method, String url, Map<String, String> headers, String body, int timeoutMs)
      throws Exception {
    SimpleHttpClient.Request request = new SimpleHttpClient.Request(url, method, body);
    request.connectTimeoutMs = timeoutMs;
    request.readTimeoutMs = timeoutMs;
    if (headers != null) {
      request.headers.putAll(headers);
    }
    SimpleHttpClient.Response response = SimpleHttpClient.execute(request);
    return new Response(
        response.code, response.message, response.contentType, response.headers, response.body);
  }

  /**
   * 长连接流式读取，供 MCP 的 SSE 传输使用。
   *
   * <p>不复用 {@code getText}：那个方法读到流结束为止，而 SSE 是永不主动结束的长连接。
   */
  @Override
  public StreamHandle openStream(String url, Map<String, String> headers) throws Exception {
    String safeUrl = UrlPolicy.requireHttpOrLocalCleartextUrl(url, "URL");
    URL target = new URL(safeUrl);
    HttpURLConnection connection =
        (HttpURLConnection) target.openConnection(AppProxy.proxyFor(target.getHost()));
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(SSE_READ_TIMEOUT_MS);
    // 不跟随重定向：SSE 端点回 3xx 属于配置错误，
    // 静默跟到别处会让用户拿到一条来自陌生地址的事件流。
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("User-Agent", "Assistant/1.0");
    if (headers != null) {
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        connection.setRequestProperty(entry.getKey(), entry.getValue());
      }
    }

    int code = connection.getResponseCode();
    if (code < 200 || code >= 300) {
      connection.disconnect();
      throw new Exception("HTTP " + code + ": " + connection.getResponseMessage());
    }

    String contentType = connection.getContentType();
    InputStream input = connection.getInputStream();
    // 包装成「关流时一并 disconnect」：HttpURLConnection 不会在流关闭时释放底层 socket。
    return HttpPort.streamHandle(
        code, new DisconnectingInputStream(input, connection), charsetOf(contentType));
  }

  /** 关闭时连带 {@code disconnect()} 的输入流包装。 */
  private static final class DisconnectingInputStream extends java.io.FilterInputStream {
    private final HttpURLConnection connection;

    DisconnectingInputStream(InputStream input, HttpURLConnection connection) {
      super(input);
      this.connection = connection;
    }

    @Override
    public void close() throws java.io.IOException {
      try {
        super.close();
      } finally {
        connection.disconnect();
      }
    }
  }

  /** 从 {@code Content-Type} 里取字符集；SSE 规范默认 UTF-8。 */
  private static String charsetOf(String contentType) {
    if (contentType == null) {
      return "UTF-8";
    }
    String lower = contentType.toLowerCase(java.util.Locale.US);
    int index = lower.indexOf("charset=");
    if (index < 0) {
      return "UTF-8";
    }
    String charset = contentType.substring(index + "charset=".length()).trim();
    int semicolon = charset.indexOf(';');
    if (semicolon >= 0) {
      charset = charset.substring(0, semicolon).trim();
    }
    if (charset.length() >= 2
        && charset.charAt(0) == '"'
        && charset.charAt(charset.length() - 1) == '"') {
      charset = charset.substring(1, charset.length() - 1);
    }
    return charset.isEmpty() ? "UTF-8" : charset;
  }
}
