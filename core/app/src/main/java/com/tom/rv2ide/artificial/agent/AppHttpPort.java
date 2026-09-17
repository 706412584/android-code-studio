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

import com.tom.rv2ide.ai.protocol.SimpleHttpClient;
import com.tom.rv2ide.ai.tool.HttpPort;
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
}
