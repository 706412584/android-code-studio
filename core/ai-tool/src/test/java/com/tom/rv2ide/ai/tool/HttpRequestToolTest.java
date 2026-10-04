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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpRequestTool} 的回归测试。
 *
 * <p><b>为什么需要它</b>：本工具的价值全在「把远端真实应答如实回灌给模型」，而失败模式
 * 都是静默的——状态码丢失、错误体被吞、大响应把上下文撑爆。这些在真机上不会报错，
 * 只会让模型拿到一份残缺的「证据」并据此下错结论。
 */
final class HttpRequestToolTest {

  /** 记录最后一次请求并返回预设响应的假端口。 */
  private static final class FakePort implements HttpRequestPort {
    String method;
    String url;
    Map<String, String> headers;
    String body;
    int timeoutMs;
    int calls;

    private final Response response;
    private final Exception failure;

    FakePort(Response response) {
      this.response = response;
      this.failure = null;
    }

    FakePort(Exception failure) {
      this.response = null;
      this.failure = failure;
    }

    @Override
    public Response request(
        String method, String url, Map<String, String> headers, String body, int timeoutMs)
        throws Exception {
      calls++;
      this.method = method;
      this.url = url;
      this.headers = headers;
      this.body = body;
      this.timeoutMs = timeoutMs;
      if (failure != null) {
        throw failure;
      }
      return response;
    }
  }

  private static HttpRequestPort.Response response(int code, String contentType, String body) {
    return new HttpRequestPort.Response(
        code, "OK", contentType, Collections.<String, String>emptyMap(), body);
  }

  @Test
  void getIsDefaultAndReturnsStatusAndBody() throws Exception {
    FakePort port = new FakePort(response(200, "application/json", "{\"ok\":true}"));
    ToolResult result =
        new HttpRequestTool(port).execute(new JSONObject().put("url", "https://api.example/v1"), null);

    assertFalse(result.isError());
    assertEquals("GET", port.method);
    assertTrue(result.getContent().contains("HTTP 200"));
    assertTrue(result.getContent().contains("\"ok\": true"));
  }

  @Test
  void non2xxIsReturnedNotThrown() throws Exception {
    // 这是本工具存在的理由：接口调试时，错误码与错误体比「请求成功了」更重要。
    FakePort port =
        new FakePort(response(404, "application/json", "{\"error\":\"not found\"}"));
    ToolResult result =
        new HttpRequestTool(port).execute(new JSONObject().put("url", "https://api.example/x"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("HTTP 404"));
    assertTrue(result.getContent().contains("not found"));
  }

  @Test
  void serverErrorBodyIsPreserved() throws Exception {
    FakePort port = new FakePort(response(500, "text/plain", "stack trace here"));
    ToolResult result =
        new HttpRequestTool(port).execute(new JSONObject().put("url", "https://api.example/x"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("HTTP 500"));
    assertTrue(result.getContent().contains("stack trace here"));
  }

  @Test
  void methodWhitelistRejectsUnknown() throws Exception {
    FakePort port = new FakePort(response(200, "text/plain", "x"));
    ToolResult result =
        new HttpRequestTool(port)
            .execute(new JSONObject().put("url", "https://a.example").put("method", "TRACE"), null);

    assertTrue(result.isError());
    assertEquals(0, port.calls, "被拒绝的方法不得发起请求");
  }

  @Test
  void methodIsNormalizedToUpperCase() throws Exception {
    FakePort port = new FakePort(response(200, "text/plain", "x"));
    new HttpRequestTool(port)
        .execute(new JSONObject().put("url", "https://a.example").put("method", "delete"), null);

    assertEquals("DELETE", port.method);
  }

  @Test
  void putDeletePatchAreAccepted() throws Exception {
    for (String method : new String[] {"PUT", "DELETE", "PATCH"}) {
      FakePort port = new FakePort(response(204, "", ""));
      ToolResult result =
          new HttpRequestTool(port)
              .execute(
                  new JSONObject().put("url", "https://a.example/1").put("method", method), null);
      assertFalse(result.isError(), method + " 应被接受");
      assertEquals(method, port.method);
    }
  }

  @Test
  void rejectsNonHttpUrl() throws Exception {
    FakePort port = new FakePort(response(200, "text/plain", "x"));
    ToolResult result =
        new HttpRequestTool(port)
            .execute(new JSONObject().put("url", "file:///etc/passwd"), null);

    assertTrue(result.isError());
    assertEquals(0, port.calls);
  }

  @Test
  void rejectsEmptyUrl() throws Exception {
    assertTrue(
        new HttpRequestTool(new FakePort(response(200, "", "")))
            .execute(new JSONObject(), null)
            .isError());
  }

  @Test
  void passesHeadersThrough() throws Exception {
    FakePort port = new FakePort(response(200, "application/json", "{}"));
    new HttpRequestTool(port)
        .execute(
            new JSONObject()
                .put("url", "https://a.example")
                .put("headers", new JSONObject().put("Authorization", "Bearer t")),
            null);

    assertEquals("Bearer t", port.headers.get("Authorization"));
  }

  @Test
  void defaultsContentTypeForJsonBody() throws Exception {
    // 不补默认值的话，服务端常按 form-urlencoded 解析或直接拒绝。
    FakePort port = new FakePort(response(200, "application/json", "{}"));
    new HttpRequestTool(port)
        .execute(
            new JSONObject()
                .put("url", "https://a.example")
                .put("method", "POST")
                .put("body", "{\"a\":1}"),
            null);

    assertEquals("application/json", headerIgnoreCase(port.headers, "content-type"));
  }

  @Test
  void explicitContentTypeIsNotOverridden() throws Exception {
    FakePort port = new FakePort(response(200, "text/plain", "ok"));
    new HttpRequestTool(port)
        .execute(
            new JSONObject()
                .put("url", "https://a.example")
                .put("method", "POST")
                .put("body", "a=1")
                .put("headers", new JSONObject().put("Content-Type", "application/x-www-form-urlencoded")),
            null);

    assertEquals(
        "application/x-www-form-urlencoded", headerIgnoreCase(port.headers, "content-type"));
  }

  @Test
  void getWithoutBodyHasNoContentType() throws Exception {
    FakePort port = new FakePort(response(200, "application/json", "{}"));
    new HttpRequestTool(port).execute(new JSONObject().put("url", "https://a.example"), null);

    assertNull(headerIgnoreCase(port.headers, "content-type"));
  }

  @Test
  void formatsJsonResponse() throws Exception {
    FakePort port = new FakePort(response(200, "application/json", "{\"a\":1,\"b\":[2]}"));
    ToolResult result =
        new HttpRequestTool(port).execute(new JSONObject().put("url", "https://a.example"), null);

    assertTrue(result.getContent().contains("\n"));
    assertTrue(result.getContent().contains("\"a\": 1"));
  }

  @Test
  void formatJsonCanBeDisabled() throws Exception {
    FakePort port = new FakePort(response(200, "application/json", "{\"a\":1}"));
    ToolResult result =
        new HttpRequestTool(port)
            .execute(
                new JSONObject().put("url", "https://a.example").put("format_json", false), null);

    assertTrue(result.getContent().contains("{\"a\":1}"));
  }

  @Test
  void invalidJsonBodyIsReturnedRaw() throws Exception {
    // 大响应被截断后就是非法 JSON；此时不能因为美化失败而丢内容。
    FakePort port = new FakePort(response(200, "application/json", "{\"a\":"));
    ToolResult result =
        new HttpRequestTool(port).execute(new JSONObject().put("url", "https://a.example"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("{\"a\":"));
  }

  @Test
  void truncatesHugeBody() throws Exception {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 20000; i++) {
      big.append("line ").append(i).append('\n');
    }
    FakePort port = new FakePort(response(200, "text/plain", big.toString()));
    ToolResult result =
        new HttpRequestTool(port).execute(new JSONObject().put("url", "https://a.example"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("已截断"));
    assertTrue(result.getContent().length() < big.length());
  }

  @Test
  void includesResponseHeadersOnlyWhenAsked() throws Exception {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Request-Id", "abc-123");
    headers.put("Content-Type", "application/json");
    HttpRequestPort.Response withHeaders =
        new HttpRequestPort.Response(200, "OK", "application/json", headers, "{}");

    ToolResult without =
        new HttpRequestTool(new FakePort(withHeaders))
            .execute(new JSONObject().put("url", "https://a.example"), null);
    assertFalse(without.getContent().contains("X-Request-Id"));

    ToolResult with =
        new HttpRequestTool(new FakePort(withHeaders))
            .execute(
                new JSONObject().put("url", "https://a.example").put("include_headers", true), null);
    assertTrue(with.getContent().contains("X-Request-Id"));
    assertTrue(with.getContent().contains("abc-123"));
  }

  @Test
  void networkFailureIsReportedAsError() throws Exception {
    FakePort port = new FakePort(new java.io.IOException("connection refused"));
    ToolResult result =
        new HttpRequestTool(port).execute(new JSONObject().put("url", "https://a.example"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("connection refused"));
  }

  @Test
  void clampsTimeout() throws Exception {
    FakePort low = new FakePort(response(200, "text/plain", "x"));
    new HttpRequestTool(low)
        .execute(new JSONObject().put("url", "https://a.example").put("timeout_ms", 5), null);
    assertEquals(HttpRequestTool.MIN_TIMEOUT_MS, low.timeoutMs);

    FakePort high = new FakePort(response(200, "text/plain", "x"));
    new HttpRequestTool(high)
        .execute(
            new JSONObject().put("url", "https://a.example").put("timeout_ms", 9_999_999), null);
    assertEquals(HttpRequestTool.MAX_TIMEOUT_MS, high.timeoutMs);
  }

  @Test
  void emptyBodyIsStatedExplicitly() throws Exception {
    FakePort port = new FakePort(response(204, "", ""));
    ToolResult result =
        new HttpRequestTool(port)
            .execute(new JSONObject().put("url", "https://a.example").put("method", "DELETE"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("响应体为空"));
  }

  @Test
  void nullPortReportsUnavailableInsteadOfCrashing() throws Exception {
    ToolResult result =
        new HttpRequestTool(null).execute(new JSONObject().put("url", "https://a.example"), null);
    assertTrue(result.isError());
  }

  @Test
  void requiresConfirmationAndIsNotAllowedInReadonlyMode() {
    // 本工具能发 POST/PUT/DELETE/PATCH，与 shell_execute 同风险等级：
    // 必须默认要确认，且只读模式不放行（否则只读模式里能发出写请求）。
    HttpRequestTool tool = new HttpRequestTool(null);
    assertFalse(tool.isAllowedInReadonlyMode());
    assertTrue(tool.needsConfirmation());
    assertEquals(ToolCategory.SYSTEM, tool.getCategory());
  }

  @Test
  void schemaAdvertisesAllowedMethods() throws Exception {
    JSONObject params = new HttpRequestTool(null).getParameters();
    String enumValues = params.getJSONObject("properties").getJSONObject("method").get("enum").toString();
    for (String method : HttpRequestTool.ALLOWED_METHODS) {
      assertTrue(enumValues.contains(method), "schema 应列出 " + method);
    }
  }

  private static String headerIgnoreCase(Map<String, String> headers, String name) {
    if (headers == null) {
      return null;
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
