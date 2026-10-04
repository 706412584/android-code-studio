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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 通用 HTTP 请求工具：让模型直接验证接口返回。
 *
 * <p><b>为什么需要它</b>：{@code web_fetch} 只做「抓网页转文本」，遇到需要自定义请求头
 * （鉴权、{@code Content-Type}）、非 GET 方法（上传、删除）、或想看状态码与响应头的
 * 场景就无能为力。模型在调试自己刚写的服务端代码时，最需要的是「我发了什么、它回了什么」，
 * 而 {@code web_fetch} 把非 2xx 直接当成失败抛出，恰好丢掉了最关键的诊断信息。
 *
 * <p><b>与 {@code web_fetch} 的分工</b>：{@code web_fetch} 面向「读人类可读的文档」，
 * 会剥离 HTML 并转纯文本；本工具面向「看机器接口的原始应答」，保留原始字节语义，
 * 只对 JSON 做缩进美化（可关）。
 *
 * <p><b>安全边界</b>：本类**不做** URL 安全判定，全部交给 {@link HttpRequestPort} 的实现
 * ——app 层的实现走 {@code SimpleHttpClient}，其中每一跳重定向都会执行
 * {@code UrlPolicy.requireHttpOrLocalCleartextUrl}（协议白名单 + cleartext 限制）并逐跳
 * 走代理。安全边界只有一处才不会漏。本类只额外做两件**协议无关**的校验：
 * method 白名单、以及一个快速的 scheme 前缀检查（用于给出清晰错误，不替代 UrlPolicy）。
 */
public final class HttpRequestTool extends BaseTool {

  /** 允许的 HTTP 方法。其余一律拒绝——不把 {@code TRACE}/{@code CONNECT} 这类
   *  不用于业务调试、却可能被拿来做探测的方法放进来。 */
  static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"};

  /** 回灌给模型的响应体字符上限。超出后截断并说明，避免大响应把上下文吃满。 */
  static final int MAX_BODY_CHARS = 32 * 1024;

  /**
   * 尝试 JSON 美化的输入上限。
   *
   * <p>美化要先 {@code new JSONObject(...)} 把整个响应解析进内存、再输出一份等长（缩进后更长）
   * 的字符串。对一个几十 MB 的响应做这件事，纯属浪费——反正展示时会被截断到
   * {@link #MAX_BODY_CHARS}。超过此上限就直接按原文截断，既省内存也省 CPU。
   */
  static final int MAX_FORMAT_INPUT_CHARS = 256 * 1024;

  /** 单次请求的超时默认值与上下限（毫秒）。 */
  static final int DEFAULT_TIMEOUT_MS = 30_000;

  static final int MIN_TIMEOUT_MS = 1_000;
  static final int MAX_TIMEOUT_MS = 120_000;

  private final HttpRequestPort http;

  public HttpRequestTool(HttpRequestPort http) {
    this.http = http;
  }

  @Override
  public String getName() {
    return ToolNames.HTTP_REQUEST;
  }

  @Override
  public String getDescription() {
    return "Send an arbitrary HTTP request (GET/POST/PUT/DELETE/PATCH/HEAD) with custom headers "
        + "and body, and see the status code, response headers and response body. "
        + "Use this to verify an API or a local server you are working on. "
        + "Unlike web_fetch, non-2xx responses are returned normally (with their body) so you "
        + "can inspect error codes and error payloads.";
  }

  @Override
  public ToolCategory getCategory() {
    // 定为 SYSTEM 而非 READ：本工具支持 POST/PUT/DELETE/PATCH，能改远端状态、也能把本地
    // 数据发出去，风险与 shell_execute（可 curl 任意东西）同级。分类会决定它在只读模式下
    // 是否放行——按 READ 分类就等于在只读模式里放行写请求。
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    // 只读模式不放行：GET 虽无害，但同一工具也能发 POST/PUT/DELETE。逐 method 分级不可行
    // （权限层只看分类与 needsConfirmation，不看参数），因此宁可让调试接口时切到 AUTO 模式。
    return false;
  }

  @Override
  public boolean needsConfirmation() {
    // 与 shell_execute 同风险：默认需要用户确认。AUTO 模式下不拦截（见 ToolPermissionService），
    // 频繁调试接口时用 AUTO 模式即可。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject properties = new JSONObject();
    properties.put(
        "method",
        new JSONObject()
            .put("type", "string")
            .put("enum", new JSONArray(ALLOWED_METHODS))
            .put("description", "HTTP method. Defaults to GET."));
    properties.put(
        "url",
        new JSONObject()
            .put("type", "string")
            .put("description", "Absolute http(s) URL"));
    properties.put(
        "headers",
        new JSONObject()
            .put("type", "object")
            .put("description", "Optional request headers, e.g. {\"Authorization\": \"Bearer ...\"}")
            .put("additionalProperties", new JSONObject().put("type", "string")));
    properties.put(
        "body",
        new JSONObject()
            .put("type", "string")
            .put("description", "Optional request body (e.g. a JSON string)."));
    properties.put(
        "timeout_ms",
        new JSONObject()
            .put("type", "integer")
            .put(
                "description",
                "Timeout in milliseconds, "
                    + MIN_TIMEOUT_MS
                    + "-"
                    + MAX_TIMEOUT_MS
                    + ". Defaults to "
                    + DEFAULT_TIMEOUT_MS
                    + "."));
    properties.put(
        "include_headers",
        new JSONObject()
            .put("type", "boolean")
            .put(
                "description",
                "Include all response headers in the output. Content-Type is always shown. "
                    + "Defaults to false."));
    properties.put(
        "format_json",
        new JSONObject()
            .put("type", "boolean")
            .put(
                "description",
                "Pretty-print a JSON response body. Defaults to true; set false to see it raw."));

    return new JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", new JSONArray().put("url"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (http == null) {
      return error("当前环境未配置网络访问，无法发起 HTTP 请求。");
    }

    String url = input.optString("url", "").trim();
    if (url.isEmpty()) {
      return error("url 不能为空。");
    }
    // 快速前缀检查只为给出清晰错误；真正的安全判定（协议 + cleartext + 私网）在 port 实现里。
    if (!isHttpUrl(url)) {
      return error("只支持 http/https 链接：" + url);
    }

    String method = input.optString("method", "GET").trim().toUpperCase(Locale.ROOT);
    if (method.isEmpty()) {
      method = "GET";
    }
    if (!isAllowedMethod(method)) {
      return error(
          "不支持的 HTTP 方法：" + method + "。允许的方法："
              + String.join(", ", ALLOWED_METHODS) + "。");
    }

    Map<String, String> headers = readHeaders(input.optJSONObject("headers"));
    String body = input.has("body") ? input.optString("body", "") : null;
    if (body != null && body.isEmpty()) {
      body = null;
    }
    applyDefaultContentType(headers, body);

    int timeoutMs = input.optInt("timeout_ms", DEFAULT_TIMEOUT_MS);
    if (timeoutMs <= 0) {
      timeoutMs = DEFAULT_TIMEOUT_MS;
    }
    timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(timeoutMs, MAX_TIMEOUT_MS));

    boolean includeHeaders = input.optBoolean("include_headers", false);
    boolean formatJson = input.optBoolean("format_json", true);

    if (context != null) {
      context.reportProgress(method + " " + url);
    }

    HttpRequestPort.Response response;
    try {
      response = http.request(method, url, headers, body, timeoutMs);
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("请求失败：" + ExceptionUtils.describeException(e));
    }
    if (response == null) {
      return error("请求失败：未收到响应。");
    }

    return ok(render(method, url, response, includeHeaders, formatJson));
  }

  /** 把响应渲染成给模型看的文本。 */
  private static String render(
      String method,
      String url,
      HttpRequestPort.Response response,
      boolean includeHeaders,
      boolean formatJson) {
    StringBuilder sb = new StringBuilder();
    sb.append(method).append(' ').append(url).append('\n');
    sb.append("HTTP ").append(response.getStatusCode());
    String statusMessage = response.getStatusMessage();
    if (!statusMessage.isEmpty()) {
      sb.append(' ').append(statusMessage);
    }
    sb.append('\n');

    String contentType = response.getContentType();
    if (!contentType.isEmpty()) {
      sb.append("Content-Type: ").append(contentType).append('\n');
    }
    if (includeHeaders) {
      for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
        // 跳过已单独展示的 Content-Type，避免重复。
        if (entry.getKey() == null || entry.getKey().equalsIgnoreCase("Content-Type")) {
          continue;
        }
        sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
      }
    }
    sb.append('\n');

    String body = response.getBody();
    if (body.isEmpty()) {
      // 空体要明说，否则模型可能以为响应体被吞了。
      sb.append("(响应体为空)");
      return sb.toString();
    }

    String rendered =
        formatJson && body.length() <= MAX_FORMAT_INPUT_CHARS && looksLikeJson(contentType, body)
            ? tryFormatJson(body)
            : null;
    if (rendered == null) {
      rendered = body;
    }

    if (rendered.length() > MAX_BODY_CHARS) {
      sb.append(rendered, 0, MAX_BODY_CHARS);
      sb.append("\n\n... (响应体过长，已截断，共 ")
          .append(rendered.length())
          .append(" 字符) ...");
    } else {
      sb.append(rendered);
    }
    return sb.toString();
  }

  /** 读取 headers 对象；非字符串值按字符串处理，null 值忽略。 */
  private static Map<String, String> readHeaders(JSONObject raw) {
    if (raw == null) {
      return new LinkedHashMap<>();
    }
    Map<String, String> headers = new LinkedHashMap<>();
    Iterator<String> keys = raw.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (key == null || key.trim().isEmpty()) {
        continue;
      }
      Object value = raw.opt(key);
      if (value == null) {
        continue;
      }
      headers.put(key.trim(), String.valueOf(value));
    }
    return headers;
  }

  /**
   * 有请求体但未显式指定 {@code Content-Type} 时补一个合理的默认值。
   *
   * <p>不补的话，服务端常按 {@code application/x-www-form-urlencoded} 处理或直接拒绝，
   * 表现为「明明发了 JSON 却解析不出参数」。按体内容猜测：以 <code>{</code> 或
   * <code>[</code> 开头视为 JSON，否则按纯文本。
   */
  private static void applyDefaultContentType(Map<String, String> headers, String body) {
    if (body == null || headers == null) {
      return;
    }
    for (String key : headers.keySet()) {
      if (key.equalsIgnoreCase("Content-Type")) {
        return;
      }
    }
    String trimmed = body.trim();
    boolean json = trimmed.startsWith("{") || trimmed.startsWith("[");
    headers.put("Content-Type", json ? "application/json" : "text/plain; charset=utf-8");
  }

  private static boolean isAllowedMethod(String method) {
    for (String allowed : ALLOWED_METHODS) {
      if (allowed.equals(method)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isHttpUrl(String url) {
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://");
  }

  /** 是否值得尝试按 JSON 美化：看 Content-Type，退化看首字符。 */
  private static boolean looksLikeJson(String contentType, String body) {
    String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    if (lower.contains("json")) {
      return true;
    }
    String trimmed = body.trim();
    return trimmed.startsWith("{") || trimmed.startsWith("[");
  }

  /**
   * 尝试把 JSON 美化；不是合法 JSON 时返回 null（调用方回退为原文）。
   *
   * <p>对象与数组都试：接口返回顶层数组（列表接口）很常见，只试对象会漏掉一半。
   */
  private static String tryFormatJson(String body) {
    String trimmed = body.trim();
    try {
      if (trimmed.startsWith("[")) {
        return new JSONArray(trimmed).toString(2);
      }
      return new JSONObject(trimmed).toString(2);
    } catch (Exception ignored) {
      // 不是合法 JSON（可能是被截断的大响应，或内容类型标错）→ 原文返回。
      return null;
    }
  }
}
