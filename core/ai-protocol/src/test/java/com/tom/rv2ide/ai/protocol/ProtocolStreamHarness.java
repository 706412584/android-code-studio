/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.protocol;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 HTTP 回放装置：多个协议测试共用。
 *
 * <p>起一个只监听回环的 {@link HttpServer}，把预设响应（SSE 或普通 JSON）原样回给
 * 被测协议实现，从而在不联网的前提下覆盖「请求构造 → 解析 → 回调」整条链路。
 * 同时记录收到的请求体与请求头，供断言请求侧的行为。
 */
final class ProtocolStreamHarness implements AutoCloseable {

  /** 一次收到的请求快照。 */
  static final class CapturedRequest {
    String method;
    String path;
    String body;
    final List<String> authorizationHeaders = new ArrayList<>();
    final List<String> apiKeyHeaders = new ArrayList<>();
    /** 全部请求头（名 → 首值），用于断言「空值头不被发送」这类行为。 */
    final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

    String header(String name) {
      for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name)) {
          return entry.getValue();
        }
      }
      return null;
    }
  }

  private final HttpServer server;
  private final List<CapturedRequest> requests = new ArrayList<>();

  ProtocolStreamHarness() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  /** 回放单次响应；之后重复请求也回同一份内容。 */
  ProtocolStreamHarness replay(int status, String contentType, String body) {
    this.status = status;
    this.contentType = contentType;
    this.body = body;
    return this;
  }

  /** 按顺序回放响应；队列用尽后重复最后一个（用于「先失败后成功」的重试场景）。 */
  ProtocolStreamHarness enqueue(int status, String contentType, String body) {
    synchronized (responses) {
      responses.add(new Response(status, contentType, body));
    }
    return this;
  }

  private int status = 200;
  private String contentType = "text/event-stream";
  private String body = "";
  private final List<Response> responses = new ArrayList<>();

  private static final class Response {
    final int status;
    final String contentType;
    final String body;

    Response(int status, String contentType, String body) {
      this.status = status;
      this.contentType = contentType;
      this.body = body;
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    CapturedRequest captured = new CapturedRequest();
    captured.method = exchange.getRequestMethod();
    captured.path = exchange.getRequestURI().getPath();
    captured.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization != null) {
      captured.authorizationHeaders.add(authorization);
    }
    String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
    if (apiKey != null) {
      captured.apiKeyHeaders.add(apiKey);
    }
    exchange.getRequestHeaders().forEach((name, values) -> {
      if (!values.isEmpty()) {
        captured.headers.put(name, values.get(0));
      }
    });
    Response response;
    synchronized (responses) {
      int index = requests.size();
      response = responses.isEmpty()
          ? new Response(status, contentType, body)
          : responses.get(Math.min(index, responses.size() - 1));
    }
    synchronized (requests) {
      requests.add(captured);
    }
    byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", response.contentType);
    exchange.sendResponseHeaders(response.status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  CapturedRequest lastRequest() {
    synchronized (requests) {
      return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }
  }

  int requestCount() {
    synchronized (requests) {
      return requests.size();
    }
  }

  /** 把若干 data 行拼成 SSE 流，并以 [DONE] 收尾。 */
  static String sse(String... dataPayloads) {
    StringBuilder builder = new StringBuilder();
    for (String payload : dataPayloads) {
      builder.append("data: ").append(payload).append("\n\n");
    }
    builder.append("data: [DONE]\n\n");
    return builder.toString();
  }

  /** 带 event: 行的 SSE 流（Anthropic 使用）。 */
  static String sseEvent(String eventType, String dataPayload) {
    return "event: " + eventType + "\ndata: " + dataPayload + "\n\n";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
