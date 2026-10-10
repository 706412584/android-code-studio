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
import java.util.List;
import java.util.Locale;
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
 * <p><b>传输</b>：本类只管 JSON-RPC 语义（报文组装、错误处理、握手顺序），线上差异交给
 * {@link McpTransport} 的两个实现——{@link StreamableHttpTransport}（单端点 POST）与
 * {@link HttpStreamTransport}（SSE 长连接）。{@link Transport#HTTP} /
 * {@link Transport#SSE} 是配置里 {@code type} 字段的取值。
 *
 * <p><b>协议要点</b>：
 * <ul>
 *   <li>JSON-RPC 2.0。HTTP 传输下结果在 POST 响应体里；SSE 传输下结果从长连接推回来，
 *       POST 本身可能只回 {@code 202 Accepted}。</li>
 *   <li>会话 id 由 {@code initialize} 下发（HTTP 走 {@code Mcp-Session-Id} 响应头，
 *       SSE 通常已含在 endpoint 事件给出的 URL 里）。拿不到它不影响本类工作——
 *       无状态 server 是合法实现。</li>
 *   <li>{@code initialize} 后按规范应发 {@code notifications/initialized} 通知；
 *       它没有响应体，失败也不阻断后续调用。</li>
 * </ul>
 *
 * <p>本类不引用 Android 类型，可单测（用假的 {@link HttpPort}）。
 */
public final class McpClient {

  /** 本实现声明的 MCP 协议版本。 */
  public static final String PROTOCOL_VERSION = "2025-03-26";

  /** 会话 id 的响应头名。 */
  static final String SESSION_HEADER = "Mcp-Session-Id";

  /** 客户端名称，会随 initialize 上报给 server。 */
  private static final String CLIENT_NAME = "AndroidCodeStudio";

  private static final String CLIENT_VERSION = "1.0.0";

  /**
   * 传输类型，对应配置里的 {@code type} 字段。
   *
   * <p>{@link #HTTP} 是默认值：已有用户的配置里没有这个字段，必须落到「行为不变」的那一支，
   * 否则升级后所有旧配置会突然改用 SSE 连接而全部失效。
   */
  public enum Transport {
    /** streamable HTTP（现行规范，单端点 POST）。 */
    HTTP("http"),
    /** SSE（旧规范，长连接推送）。 */
    SSE("sse"),
    /** stdio 子进程（Termux 起本地 server，newline-delimited JSON over stdin/stdout）。 */
    STDIO("stdio");

    private final String id;

    Transport(String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }

    /** 解析配置值；无法识别时回落到 {@link #HTTP}（兼容缺失字段的旧配置）。 */
    public static Transport fromId(String raw) {
      if (raw != null) {
        String value = raw.trim().toLowerCase(Locale.US);
        if (SSE.id.equals(value)) {
          return SSE;
        }
        if (STDIO.id.equals(value)) {
          return STDIO;
        }
      }
      return HTTP;
    }
  }

  private final McpTransport transport;
  private final AtomicLong nextId = new AtomicLong(1);

  /** server 上报的协议版本，供诊断。 */
  private volatile String serverProtocolVersion = "";

  private volatile String serverName = "";

  /**
   * 握手是否已完成。
   *
   * <p><b>为什么不复用 {@link #isInitialized()}</b>：那个对外回答的是「会话 id 拿到了吗」，
   * 而本字段回答的是「还要不要再握手」。无状态 server 不下发会话 id，若用会话 id 决定
   * 是否重握手，这类 server 每次调用都会重新 initialize——而 initialize 可能改变
   * server 侧的会话状态。
   */
  private volatile boolean handshakeDone = false;

  /** 并发保护：初始化只做一次，且不让两个线程同时握手。 */
  private final Object initLock = new Object();

  public McpClient(HttpPort http, String serverUrl) {
    this(http, serverUrl, Collections.<String, String>emptyMap());
  }

  public McpClient(HttpPort http, String serverUrl, Map<String, String> extraHeaders) {
    this(http, serverUrl, extraHeaders, Transport.HTTP);
  }

  /**
   * @param http HTTP 端口；null 视作无网络
   * @param serverUrl MCP server 的端点（HTTP 为消息端点，SSE 为事件流端点）
   * @param extraHeaders 附加请求头（例如鉴权）
   * @param transport 传输类型
   */
  public McpClient(
      HttpPort http, String serverUrl, Map<String, String> extraHeaders, Transport transport) {
    this(http, serverUrl, extraHeaders, transport, null, null, null, null);
  }

  /**
   * @param http HTTP 端口；null 视作无网络
   * @param serverUrl MCP server 的端点（HTTP 为消息端点，SSE 为事件流端点；
   *     stdio 时不使用，可传命令名便于诊断）
   * @param extraHeaders 附加请求头（例如鉴权）；stdio 时为附加环境变量（用户 env 优先）
   * @param transport 传输类型
   * @param stdioCommand stdio 启动命令；非 stdio 传输忽略
   * @param stdioArgs stdio 命令参数
   * @param stdioEnv stdio 附加环境变量（叠加在父环境之上）
   * @param stdioCwd stdio 子进程工作目录；空则继承父进程
   */
  public McpClient(
      HttpPort http,
      String serverUrl,
      Map<String, String> extraHeaders,
      Transport transport,
      String stdioCommand,
      java.util.List<String> stdioArgs,
      Map<String, String> stdioEnv,
      String stdioCwd) {
    HttpPort port = http == null ? HttpPort.none() : http;
    Transport type = transport == null ? Transport.HTTP : transport;
    if (type == Transport.STDIO) {
      this.transport =
          new StdioTransport(
              stdioCommand == null || stdioCommand.isEmpty() ? serverUrl : stdioCommand,
              stdioArgs,
              stdioEnv == null ? extraHeaders : stdioEnv,
              stdioCwd);
      return;
    }
    this.transport =
        type == Transport.SSE
            ? new HttpStreamTransport(port, serverUrl, extraHeaders)
            : new StreamableHttpTransport(port, serverUrl, extraHeaders);
  }

  /** 当前会话 id；未建立会话时为空串。 */
  public String getSessionId() {
    return transport.getSessionId();
  }

  /** server 上报的名称；未初始化时为空串。 */
  public String getServerName() {
    return serverName;
  }

  /** server 上报的协议版本；未初始化时为空串。 */
  public String getServerProtocolVersion() {
    return serverProtocolVersion;
  }

  /**
   * 是否已建立会话（拿到会话 id）。
   *
   * <p>无状态 server 不下发会话 id，这里会返回 false，但握手本身是成功的——
   * 判定「是否还要握手」请看内部的 {@code handshakeDone}。
   */
  public boolean isInitialized() {
    return !getSessionId().isEmpty();
  }

  /**
   * 建立会话。
   *
   * <p>可重复调用：已初始化时直接返回，避免每次拉工具列表都重新握手。
   */
  public void initialize() throws Exception {
    if (handshakeDone) {
      return;
    }
    synchronized (initLock) {
      if (handshakeDone) {
        return;
      }
      transport.open();

      JSONObject params = new JSONObject();
      params.put("protocolVersion", PROTOCOL_VERSION);
      params.put(
          "capabilities",
          new JSONObject().put("tools", new JSONObject()));
      params.put(
          "clientInfo",
          new JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION));

      JSONObject result = call("initialize", params);

      serverProtocolVersion = result.optString("protocolVersion", "");
      JSONObject serverInfo = result.optJSONObject("serverInfo");
      serverName = serverInfo == null ? "" : serverInfo.optString("name", "");

      // 先置位再发通知：通知失败不该让后续调用重新握手（见下面的 catch）。
      handshakeDone = true;

      // 按规范发送 initialized 通知。它没有响应体，失败不阻断——
      // 有些 server 不实现它，为此中断整个接入不值得。
      try {
        JSONObject payload = new JSONObject();
        payload.put("jsonrpc", "2.0");
        payload.put("method", "notifications/initialized");
        transport.notify(payload.toString());
      } catch (Exception ignored) {
        // 见上：通知失败不影响后续 tools/list 与 tools/call。
      }
    }
  }

  /**
   * 拉取 server 提供的工具列表。
   *
   * <p>自动确保已初始化——调用方不必关心握手顺序。
   */
  public List<McpToolInfo> listTools() throws Exception {
    initialize();
    JSONObject result = call("tools/list", new JSONObject());

    List<McpToolInfo> tools = new ArrayList<>();
    JSONArray array = result.optJSONArray("tools");
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

    JSONObject result = call("tools/call", params);
    return extractText(result);
  }

  /** 释放传输资源（SSE 的长连接需要显式关闭）。可重复调用。 */
  public void close() {
    transport.close();
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

  /**
   * 发一次 JSON-RPC 调用并解析结果。
   *
   * <p>解析的是**响应报文**而不是 HTTP 状态码：JSON-RPC 的错误（方法不存在、参数非法）
   * 通常仍以 200 返回，错误在 {@code error} 字段里。只看状态码会把这类失败当成成功，
   * 拿到一个空结果。传输层因此返回报文原文，解析只在这一处发生。
   */
  private JSONObject call(String method, JSONObject params) throws Exception {
    long id = nextId.getAndIncrement();
    JSONObject payload = new JSONObject();
    payload.put("jsonrpc", "2.0");
    payload.put("id", id);
    payload.put("method", method);
    payload.put("params", params == null ? new JSONObject() : params);

    String body = transport.send(payload.toString());

    JSONObject json;
    try {
      json = new JSONObject(body);
    } catch (org.json.JSONException e) {
      // 正文不是 JSON：可能打到了错误的端点（返回了 HTML）。把前若干字符带进错误里，
      // 否则用户只看到「解析失败」而不知道该检查什么。
      String preview = body;
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
    return result == null ? new JSONObject() : result;
  }
}
