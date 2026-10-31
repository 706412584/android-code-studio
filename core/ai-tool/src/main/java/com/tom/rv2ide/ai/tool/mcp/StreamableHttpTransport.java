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
 * <p>单端点：所有 JSON-RPC 都 POST 到同一个 URL；会话 id 由 {@code initialize} 的
 * {@code Mcp-Session-Id} **响应头**下发，后续请求回传。
 *
 * <p><b>响应有两种合法形态</b>（规范 2025-03-26 的 streamable HTTP）：
 * <ul>
 *   <li>{@code Content-Type: application/json} —— 响应体就是一条 JSON-RPC 报文；</li>
 *   <li>{@code Content-Type: text/event-stream} —— 响应体是 SSE 帧，
 *       形如 {@code event: message\ndata: {...}\n\n}。</li>
 * </ul>
 * 服务端可任选其一（官方 TypeScript / Python SDK 默认走后者）。因此这里的
 * {@code Accept} 必须**同时**声明两者，且解析要能剥掉 SSE 帧外壳。
 *
 * <p><b>这两个假设曾经各导致一次「工具列表找不到」</b>：只声明
 * {@code application/json} 时严格实现的 server 直接回 406；即便放过，SSE 帧交给
 * {@code new JSONObject(...)} 也会解析失败，而两条路径都被
 * {@code AgentOrchestrator.registerMcpTools} 的 catch 吞成一条错误日志——
 * 用户侧只看到「AI 说没有这个工具」。
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
    return unwrapSseBody(response);
  }

  /**
   * 把响应体统一成一条 JSON-RPC 报文。
   *
   * <p>服务端可以回纯 JSON，也可以回 SSE 帧。后者要剥掉 {@code event:} / {@code data:}
   * 外壳，否则 {@code McpClient.call} 的 {@code new JSONObject(...)} 会失败并报
   * 「server 返回的不是 JSON（可能地址填错）」——一句会把排查方向带偏的错误。
   *
   * <p><b>怎么判断该不该剥</b>：看正文开头而不是看 Content-Type。{@code HttpPort}
   * 只回传正文与响应头，且部分实现（含本仓库的 {@code AppHttpPort}）在响应头里拿不到
   * 小写化的 {@code content-type}；按正文首字符判断既简单又不会误判——合法 JSON-RPC
   * 一定以 {@code &#123;} 开头，SSE 帧一定以 {@code event:} / {@code data:} / {@code :}
   * 开头。
   *
   * <p>多帧时取**最后一条带 data 的**：MCP 的 streamable HTTP 在一条 POST 里只会回
   * 一个响应，但服务端可能先推心跳或通知帧。
   */
  static String unwrapSseBody(HttpPort.TextResponse response) {
    String body = response == null ? "" : response.getBody();
    String trimmed = body.trim();
    if (trimmed.isEmpty()) {
      return body;
    }
    char first = trimmed.charAt(0);
    if (first == '{' || first == '[') {
      return body;
    }
    // 不是 JSON 开头：按 SSE 帧解析。若解析不出任何 data 行，原样返回，
    // 让上层给出「不是 JSON」的可读错误（错误里带正文前 200 字符）。
    String collected = "";
    String eventName = "message";
    StringBuilder data = new StringBuilder();
    for (String rawLine : trimmed.split("\n", -1)) {
      String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
      if (line.isEmpty()) {
        // 空行结束一帧：只取 message 帧（endpoint 帧属于旧 SSE 传输的语义）。
        if (data.length() > 0 && "message".equals(eventName)) {
          collected = data.toString();
        }
        data.setLength(0);
        eventName = "message";
        continue;
      }
      if (line.charAt(0) == ':') {
        continue; // 注释/心跳
      }
      int colon = line.indexOf(':');
      String field = colon < 0 ? line : line.substring(0, colon);
      String value = colon < 0 ? "" : line.substring(colon + 1);
      if (value.startsWith(" ")) {
        value = value.substring(1);
      }
      if ("event".equals(field)) {
        eventName = value;
      } else if ("data".equals(field)) {
        if (data.length() > 0) {
          data.append('\n');
        }
        data.append(value);
      }
    }
    // 末帧可能没有以空行结尾。
    if (data.length() > 0 && "message".equals(eventName)) {
      collected = data.toString();
    }
    return collected.isEmpty() ? body : collected;
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
    // **两个类型都要声明**：规范要求客户端能接受服务端的任一种回包方式，
    // 官方 SDK 的实现会在缺任一类型时直接回 406 Not Acceptable。
    headers.put("Accept", "application/json, text/event-stream");
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
