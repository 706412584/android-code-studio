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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import com.tom.rv2ide.ai.protocol.AppProxy;
import com.tom.rv2ide.ai.protocol.SimpleHttpClient;
import com.tom.rv2ide.ai.protocol.UrlPolicy;
import com.tom.rv2ide.ai.tool.HttpPort;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

/**
 * 用既有的 {@link SimpleHttpClient} 实现工具层的 {@link HttpPort}。
 *
 * <p><b>为什么不直接在工具里用 SimpleHttpClient</b>：{@code ai-tool} 刻意不依赖
 * {@code ai-protocol}（工具模块要能在 JVM 上单测，依赖面越窄越好）。这里做适配，
 * 使安全边界仍只有一处——URL 策略、代理与超时都由 {@code SimpleHttpClient} 内部处理，
 * 不会因为新增一个工具就绕过 {@code UrlPolicy}。
 */
public final class AppHttpPort implements HttpPort {

  /** 网页抓取的超时。比模型调用的超时短：用户在看结果，等太久不如早点失败。 */
  private static final int CONNECT_TIMEOUT_MS = 15_000;

  private static final int READ_TIMEOUT_MS = 30_000;

  /** SSE 长连接的读超时。事件间隔可能很长，给足余量（见 {@link #openStream}）。 */
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
    // SimpleHttpClient.download 只接受无自定义头的下载；当前没有需要自定义头的
    // 二进制下载场景，因此这里不为其扩展接口。
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
   * 长连接流式读取，供 MCP 的 SSE 传输使用。
   *
   * <p><b>为什么不复用 {@link SimpleHttpClient#get}</b>：那个方法读到流结束为止，
   * 而 SSE 是一条永不主动结束的长连接——用它会在超时或超出 32MB 上限时抛错，
   * 期间一个事件也拿不到。
   *
   * <p><b>为什么自己建连接而不是给 {@code SimpleHttpClient} 加一个方法</b>：
   * 那会动到 {@code ai-protocol}（本次改动范围之外），且它的响应头收集、正文读取都是
   * 「读完再返回」的形态，为长连接改它的代价远大于在此处重复十几行建连逻辑。
   * 安全边界仍然守住：URL 照样先过 {@link UrlPolicy}，代理照样走 {@link AppProxy}。
   */
  @Override
  public StreamHandle openStream(String url, Map<String, String> headers) throws Exception {
    String safeUrl = UrlPolicy.requireHttpOrLocalCleartextUrl(url, "URL");
    URL target = new URL(safeUrl);
    HttpURLConnection connection =
        (HttpURLConnection) target.openConnection(AppProxy.proxyFor(target.getHost()));
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    // 读超时必须足够长：SSE 的两次事件之间可能隔很久（MCP server 在跑慢工具），
    // 而 HttpURLConnection 下读超时会直接掐断连接。这里给到 10 分钟，
    // 真正的「对端不再响应」由上层 McpClient 的等待超时兜住。
    connection.setReadTimeout(SSE_READ_TIMEOUT_MS);
    // 逐跳校验重定向要靠手动跟随，这里不跟随：SSE 端点回 3xx 属于配置错误，
    // 静默跟到别处会让用户拿到一条来自陌生地址的事件流。
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("User-Agent", "LineCode/1.0");
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
    // 包装成「关流时一并 disconnect」：HttpURLConnection 不会在流关闭时释放底层 socket，
    // 而这条连接的持有者（SSE 读线程）只知道要 close 句柄，不该再了解连接对象。
    // 注意必须写成 HttpPort.streamHandle：接口的静态方法不被实现类继承，无法直接调用。
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

  /**
   * 从 {@code Content-Type} 里取字符集。
   *
   * <p>SSE 规范默认 UTF-8，但服务端可能显式声明别的（例如 GBK）。
   * 按声明解码能避免中文工具输出变成乱码。
   */
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
    // 值可能带引号（charset="utf-8"）
    if (charset.length() >= 2 && charset.charAt(0) == '"' && charset.charAt(charset.length() - 1) == '"') {
      charset = charset.substring(1, charset.length() - 1);
    }
    return charset.isEmpty() ? "UTF-8" : charset;
  }
}
