/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
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

package com.tom.rv2ide.ai.tool.mcp;

import com.tom.rv2ide.ai.tool.HttpPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MCP（Model Context Protocol）客户端：连接外部 MCP server，把它的工具引入本应用。
 *
 * <p><b>方向澄清</b>：这是**客户端**——本应用作为 host 去调用别人提供的 MCP server，
 * 不是把本应用变成 server 供他人连接。
 *
 * <p><b>为什么值得做</b>：内置工具再多也是有限的。MCP 让用户可以接入任意现成的工具
 * 服务（数据库查询、CI、内部系统），而无需改本项目的代码。
 *
 * <p><b>实现范围</b>：只做 {@code initialize} / {@code tools/list} / {@code tools/call}
 * 三个方法——这是「拉取工具列表并调用」的最小闭环。资源、提示词、采样等 MCP 能力
 * 不在范围内。
 *
 * <p><b>协议要点</b>：
 * <ul>
 *   <li>JSON-RPC 2.0 over HTTP POST。
 *   <li>会话 id 由 {@code initialize} 的 {@code Mcp-Session-Id} **响应头**下发，
 *       后续请求必须回传。拿不到它就无法建立会话——这也是 {@link HttpPort#postJson}
 *       必须返回响应头的原因。
 *   <li>{@code initialize} 后按规范应发 {@code notifications/initialized} 通知；
 *       它没有响应体，失败也不阻断后续调用。
 * </ul>
 *
 * <p>本类不引用 Android 类型，可单测（用假的 {@link HttpPort}）。
 */
public final class McpClient {

  /** 本实现声明的 MCP 协议版本。 */
  public static final String PROTOCOL_VERSION = "2025-03-26";

  /** 客户端名称，会随 initialize 上报给 server。 */
  private static final String CLIENT_NAME = "AndroidCodeStudio";

  private static final String CLIENT_VERSION = "1.0.0";

  /** 会话 id 的响应头名。 */
  private static final String SESSION_HEADER = "Mcp-Session-Id";

  private final HttpPort http;
  private final String serverUrl;
  private final Map<String, String> extraHeaders;
  private final AtomicLong nextId = new AtomicLong(1);

  /** 会话 id；initialize 成功后才有值。 */
  private volatile String sessionId = "";

  /** server 上报的协议版本，供诊断。 */
  private volatile String serverProtocolVersion = "";

  private volatile String serverName = "";

  public McpClient(HttpPort http, String serverUrl) {
    this(http, serverUrl, Collections.<String, String>emptyMap());
  }

  /**
   * @param http HTTP 端口；null 视作无网络
   * @param serverUrl MCP server 的 HTTP 端点
   * @param extraHeaders 附加请求头（例如鉴权）
   */
  public McpClient(HttpPort http, String serverUrl, Map<String, String> extraHeaders) {
    this.http = http == null ? HttpPort.none() : http;
    this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
    this.extraHeaders =
        extraHeaders == null
            ? Collections.<String, String>emptyMap()
            : new HashMap<>(extraHeaders);
  }

  /** 当前会话 id；未建立会话时为空串。 */
  public String getSessionId() {
    return sessionId;
  }

  /** server 上报的名称；未初始化时为空串。 */
  public String getServerName() {
    return serverName;
  }

  /** server 上报的协议版本；未初始化时为空串。 */
  public String getServerProtocolVersion() {
    return serverProtocolVersion;
  }

  /** 是否已建立会话。 */
  public boolean isInitialized() {
    return !sessionId.isEmpty();
  }

  /**
   * 建立会话。
   *
   * <p>可重复调用：已初始化时直接返回，避免每次拉工具列表都重新握手。
   */
  public void initialize() throws Exception {
    if (isInitialized()) {
      return;
    }
    requireServerUrl();

    JSONObject params = new JSONObject();
    params.put("protocolVersion", PROTOCOL_VERSION);
    params.put(
        "capabilities",
        new JSONObject().put("tools", new JSONObject()));
    params.put(
        "clientInfo",
        new JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION));

    RpcResult result = call("initialize", params);

    // 会话 id 可能缺失（无状态 server）。此时后续调用不带该头也能工作，
    // 因此不视为失败——否则一类合法实现会被完全排除。
    sessionId = result.sessionId;
    serverProtocolVersion = result.result.optString("protocolVersion", "");
    JSONObject serverInfo = result.result.optJSONObject("serverInfo");
    serverName = serverInfo == null ? "" : serverInfo.optString("name", "");

    // 按规范发送 initialized 通知。它没有响应体，失败不阻断——
    // 有些 server 不实现它，为此中断整个接入不值得。
    try {
      notifyInitialized();
    } catch (Exception ignored) {
      // 见上：通知失败不影响后续 tools/list 与 tools/call。
    }
  }

  /**
   * 拉取 server 提供的工具列表。
   *
   * <p>自动确保已初始化——调用方不必关心握手顺序。
   */
  public List<McpToolInfo> listTools() throws Exception {
    initialize();
    RpcResult result = call("tools/list", new JSONObject());

    List<McpToolInfo> tools = new ArrayList<>();
    JSONArray array = result.result.optJSONArray("tools");
    if (array == null) {
      return tools;
    }
    for (int i = 0; i < array.length(); i++) {
      JSONObject raw = array.optJSONObject(i);
      if (raw == null) {
        continue;
      }
      String name = raw.optString("name", "").trim();
      if (name.isEmpty()) {
        // 没有名字的工具无法被模型调用，跳过而不是让整个列表失败。
        continue;
      }
      tools.add(
          new McpToolInfo(
              name,
              raw.optString("description", ""),
              raw.optJSONObject("inputSchema")));
    }
    return tools;
  }

  /**
   * 调用一个工具。
   *
   * @param toolName 工具名（来自 {@link #listTools}）
   * @param arguments 参数 JSON
   * @return 工具输出文本
   */
  public String callTool(String toolName, JSONObject arguments) throws Exception {
    if (toolName == null || toolName.trim().isEmpty()) {
      throw new IllegalArgumentException("工具名不能为空");
    }
    initialize();

    JSONObject params = new JSONObject();
    params.put("name", toolName.trim());
    params.put("arguments", arguments == null ? new JSONObject() : arguments);

    RpcResult result = call("tools/call", params);
    return extractText(result.result);
  }

  /**
   * 从 {@code tools/call} 的结果里取出文本。
   *
   * <p>MCP 的结果是内容块数组（{@code [{type:"text", text:"..."}]}），可能混有图片等
   * 非文本块。这里只取文本块——非文本内容模型无法从纯文本通道使用，强行序列化只会
   * 灌进去一堆 base64。
   *
   * <p>{@code isError} 为 true 时结果仍需返回给模型（让它看到失败原因并调整），
   * 因此这里不抛异常，由调用方决定如何呈现。
   */
  static String extractText(JSONObject result) {
    if (result == null) {
      return "";
    }
    JSONArray content = result.optJSONArray("content");
    if (content == null) {
      // 某些 server 直接返回字符串字段
      String direct = result.optString("text", "");
      return direct.isEmpty() ? result.toString() : direct;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < content.length(); i++) {
      JSONObject block = content.optJSONObject(i);
      if (block == null) {
        continue;
      }
      if (!"text".equals(block.optString("type", ""))) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(block.optString("text", ""));
    }
    return sb.toString();
  }

  private void requireServerUrl() throws Exception {
    if (serverUrl.isEmpty()) {
      throw new Exception("未配置 MCP server 地址。");
    }
    if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
      throw new Exception("MCP server 地址必须是 http(s)：" + serverUrl);
    }
  }

  /** 发送 initialized 通知（无 id、无响应）。 */
  private void notifyInitialized() throws Exception {
    JSONObject payload = new JSONObject();
    payload.put("jsonrpc", "2.0");
    payload.put("method", "notifications/initialized");
    http.postJson(serverUrl, payload.toString(), buildHeaders());
  }

  /**
   * 发一次 JSON-RPC 调用并解析结果。
   *
   * <p>解析的是**响应正文**而不是 HTTP 状态码：JSON-RPC 的错误（方法不存在、参数非法）
   * 通常仍以 200 返回，错误在 {@code error} 字段里。只看状态码会把这类失败当成成功，
   * 拿到一个空结果。
   */
  private RpcResult call(String method, JSONObject params) throws Exception {
    requireServerUrl();

    long id = nextId.getAndIncrement();
    JSONObject payload = new JSONObject();
    payload.put("jsonrpc", "2.0");
    payload.put("id", id);
    payload.put("method", method);
    payload.put("params", params == null ? new JSONObject() : params);

    HttpPort.TextResponse response =
        http.postJson(serverUrl, payload.toString(), buildHeaders());

    JSONObject json;
    try {
      json = new JSONObject(response.getBody());
    } catch (org.json.JSONException e) {
      // 正文不是 JSON：可能打到了错误的端点（返回了 HTML）。把前若干字符带进错误里，
      // 否则用户只看到「解析失败」而不知道该检查什么。
      String preview = response.getBody();
      if (preview.length() > 200) {
        preview = preview.substring(0, 200) + "…";
      }
      throw new Exception("MCP server 返回的不是 JSON（可能地址填错）：" + preview);
    }

    JSONObject error = json.optJSONObject("error");
    if (error != null) {
      throw new Exception(
          "MCP 错误 " + error.optInt("code", -1) + ": " + error.optString("message", "未知错误"));
    }

    JSONObject result = json.optJSONObject("result");
    return new RpcResult(result == null ? new JSONObject() : result, response.header(SESSION_HEADER));
  }

  /** 组装请求头：JSON 内容类型 + 会话 id + 用户附加头。 */
  private Map<String, String> buildHeaders() {
    Map<String, String> headers = new HashMap<>(extraHeaders);
    headers.put("Content-Type", "application/json");
    headers.put("Accept", "application/json");
    if (!sessionId.isEmpty()) {
      headers.put(SESSION_HEADER, sessionId);
    }
    return headers;
  }

  /** 一次调用的结果。 */
  private static final class RpcResult {
    final JSONObject result;
    final String sessionId;

    RpcResult(JSONObject result, String sessionId) {
      this.result = result;
      this.sessionId = sessionId == null ? "" : sessionId;
    }
  }
}
