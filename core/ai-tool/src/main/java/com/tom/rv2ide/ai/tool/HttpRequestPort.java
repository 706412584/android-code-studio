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

import java.util.Collections;
import java.util.Map;

/**
 * 通用 HTTP 请求端口，供 {@link HttpRequestTool} 使用。
 *
 * <p><b>为什么不复用 {@link HttpPort}</b>：{@code HttpPort} 的四个方法都是为具体消费者
 * 定制的窄口子（{@code getText} 给网页抓取、{@code postJson} 给 MCP、{@code openStream}
 * 给 SSE），它们共同的前提是「2xx 才算成功，非 2xx 直接抛异常」，且都不回传状态码。
 * 而通用 HTTP 工具的**核心价值恰恰是让模型看到 4xx/5xx 的状态码与错误体**——
 * 接口调试时「返回了什么错」比「请求成功了」更重要。用一个专门的方法承载，语义才清楚：
 * 它不抛异常表达非 2xx，而是把状态码原样交给调用方。
 *
 * <p><b>安全边界不在这里</b>：实现方必须让请求经过既有的 URL 策略与代理
 * （app 层 {@code AppHttpPort} 走 {@code SimpleHttpClient.execute}，其中逐跳执行
 * {@code UrlPolicy.requireHttpOrLocalCleartextUrl}）。工具层只做 method 白名单与参数校验，
 * 不重复实现协议/私网判定——安全边界只有一处才不会漏。
 */
public interface HttpRequestPort {

  /**
   * 发起一次 HTTP 请求。
   *
   * <p><b>非 2xx 不抛异常</b>：状态码与响应体一并返回，由调用方决定如何呈现。
   * 仅在**连接层失败**（DNS、连接被拒、超时、URL 非法）时抛出。
   *
   * @param method 已归一化为大写的 HTTP 方法（GET/POST/PUT/DELETE/PATCH）
   * @param url 完整 URL
   * @param headers 请求头，可为空
   * @param body 请求体；无体时为 null 或空串
   * @param timeoutMs 连接与读取超时（毫秒）
   * @return 状态码、响应头与响应体
   * @throws Exception 连接层失败
   */
  Response request(
      String method, String url, Map<String, String> headers, String body, int timeoutMs)
      throws Exception;

  /** 一次 HTTP 响应的只读快照。 */
  final class Response {
    private final int statusCode;
    private final String statusMessage;
    private final String contentType;
    private final Map<String, String> headers;
    private final String body;

    public Response(
        int statusCode,
        String statusMessage,
        String contentType,
        Map<String, String> headers,
        String body) {
      this.statusCode = statusCode;
      this.statusMessage = statusMessage == null ? "" : statusMessage;
      this.contentType = contentType == null ? "" : contentType;
      this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
      this.body = body == null ? "" : body;
    }

    public int getStatusCode() {
      return statusCode;
    }

    public String getStatusMessage() {
      return statusMessage;
    }

    public String getContentType() {
      return contentType;
    }

    public Map<String, String> getHeaders() {
      return headers;
    }

    public String getBody() {
      return body;
    }
  }
}
