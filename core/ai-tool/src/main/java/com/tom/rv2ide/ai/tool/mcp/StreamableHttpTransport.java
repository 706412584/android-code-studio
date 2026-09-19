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

package com.tom.rv2ide.ai.tool.mcp;

import com.tom.rv2ide.ai.tool.HttpPort;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP 的 **streamable HTTP 传输**（现行规范）。
 *
 * <p>单端点：所有 JSON-RPC 都 POST 到同一个 URL，结果直接放在响应体里；会话 id 由
 * {@code initialize} 的 {@code Mcp-Session-Id} **响应头**下发，后续请求回传。
 *
 * <p>没有独立的建连步骤，因此 {@link #open()} 只做地址校验——这也是「传输接口的 open
 * 可以很轻」的例证：SSE 需要它，HTTP 不需要。
 */
final class StreamableHttpTransport implements McpTransport {

  private final HttpPort http;
  private final String serverUrl;
  private final Map<String, String> extraHeaders;

  private volatile String sessionId = "";

  StreamableHttpTransport(HttpPort http, String serverUrl, Map<String, String> extraHeaders) {
    this.http = http;
    this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
    this.extraHeaders =
        extraHeaders == null
            ? Collections.<String, String>emptyMap()
            : new HashMap<>(extraHeaders);
  }

  @Override
  public void open() throws Exception {
    requireHttpUrl(serverUrl);
  }

  @Override
  public String send(String jsonRpcBody) throws Exception {
    requireHttpUrl(serverUrl);
    HttpPort.TextResponse response = http.postJson(serverUrl, jsonRpcBody, buildHeaders());
    // 会话 id 可能在任意一次响应里下发（规范上只在 initialize，但实现有差异），
    // 因此每次都更新而不是只在 initialize 时取。
    String fromHeader = response.header(McpClient.SESSION_HEADER);
    if (!fromHeader.isEmpty()) {
      sessionId = fromHeader;
    }
    return response.getBody();
  }

  @Override
  public void notify(String jsonRpcBody) throws Exception {
    requireHttpUrl(serverUrl);
    http.postJson(serverUrl, jsonRpcBody, buildHeaders());
  }

  @Override
  public String getSessionId() {
    return sessionId;
  }

  @Override
  public void close() {
    // 无长连接可释放。保留方法是为了让 McpClient 对两套传输用同一套调用序列。
  }

  private Map<String, String> buildHeaders() {
    Map<String, String> headers = new HashMap<>(extraHeaders);
    headers.put("Content-Type", "application/json");
    headers.put("Accept", "application/json");
    if (!sessionId.isEmpty()) {
      headers.put(McpClient.SESSION_HEADER, sessionId);
    }
    return headers;
  }

  private static void requireHttpUrl(String url) throws Exception {
    if (url == null || url.trim().isEmpty()) {
      throw new Exception("未配置 MCP server 地址。");
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      throw new Exception("MCP server 地址必须是 http(s)：" + url);
    }
  }
}
