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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ErrorLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 用包内子类暴露 {@link AbstractHttpModelProtocol} 的 protected 出口，覆盖两条协议
 * 共用的 HTTP/SSE 基础设施：端点拼接、思考预算映射、SSE 状态错误分类与错误日志截断。
 */
final class AbstractHttpModelProtocolTest {

  private ProtocolStreamHarness harness;
  private ErrorLog.Sink previousSink;

  /** 暴露 protected 出口的最小实现。 */
  private static final class Probe extends AbstractHttpModelProtocol {
    @Override
    public ModelCompletionResponse complete(ModelConfig config, List<ModelMessage> messages) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ModelCompletionResponse stream(ModelConfig config, List<ModelMessage> messages,
                                          ModelStreamCallback callback, ModelCancellationToken token,
                                          ModelRequestOptions options) {
      throw new UnsupportedOperationException();
    }

    String post(String url, JSONObject body, Map<String, String> headers) throws ModelCompletionException {
      return postJson(url, body, headers);
    }

    void postSse(String url, JSONObject body, Map<String, String> headers, SseEventHandler handler)
        throws ModelCompletionException {
      postJsonSse(url, body, headers, null, handler);
    }

    int budget(String effort) {
      return thinkingBudget(effort);
    }

    String endpointOf(String base, String suffix) {
      return endpoint(base, suffix);
    }

    void parseError(String raw, Throwable t) {
      logParseError("parse_test", raw, t);
    }
  }

  private static JSONObject body() {
    return new JSONObject()
        .put("model", "x")
        .put("messages", new org.json.JSONArray());
  }

  @AfterEach
  void tearDown() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
    ErrorLog.setSink(previousSink);
  }

  // ---- endpoint / thinkingBudget ----

  @Test
  void endpointAppendsSuffixAndTrimsSlashes() {
    Probe probe = new Probe();
    assertEquals("http://a.com/chat/completions", probe.endpointOf("http://a.com", "/chat/completions"));
    assertEquals("http://a.com/chat/completions", probe.endpointOf("http://a.com///", "/chat/completions"));
    // 已含后缀则不再重复
    assertEquals("http://a.com/chat/completions", probe.endpointOf("http://a.com/chat/completions", "/chat/completions"));
    assertEquals("/chat/completions", probe.endpointOf(null, "/chat/completions"));
  }

  @Test
  void thinkingBudgetMapsEffortLevels() {
    Probe probe = new Probe();
    assertEquals(1024, probe.budget("low"));
    assertEquals(8192, probe.budget("high"));
    assertEquals(16000, probe.budget("max"));
    assertEquals(4096, probe.budget("medium"));
    assertEquals(4096, probe.budget(null));
  }

  // ---- postJson ----

  @Test
  void postJsonReturnsResponseBody() throws Exception {
    harness = new ProtocolStreamHarness().replay(200, "application/json", "{\"a\":1}");
    assertEquals("{\"a\":1}", new Probe().post(harness.baseUrl() + "/p", body(), Collections.emptyMap()));
  }

  @Test
  void postJsonRejectsDisallowedUrlBeforeConnecting() throws Exception {
    // UrlPolicy 在 openJsonPost 里最先跑：非 http(s) 的地址连连接都不会建立。
    // 这里断言「被拒绝」而非具体异常类型——它可能被包装成 ModelCompletionException。
    Exception e = assertThrows(Exception.class,
        () -> new Probe().post("ftp://evil/x", body(), Collections.emptyMap()));
    assertNotNull(e.getMessage());
  }

  // ---- postJsonSse ----

  @Test
  void sseInvokesHandlerForEachEvent() throws Exception {
    String sse = "event: message\ndata: one\n\ndata: two\n\n";
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream", sse);
    List<String> seen = new ArrayList<>();
    new Probe().postSse(harness.baseUrl() + "/p", body(), Collections.emptyMap(),
        (eventType, data) -> seen.add(eventType + ":" + data));
    assertEquals(List.of("message:one", ":two"), seen);
  }

  @Test
  void sseJoinsMultiLineDataPayload() throws Exception {
    String sse = "data: line1\ndata: line2\n\n";
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream", sse);
    List<String> seen = new ArrayList<>();
    new Probe().postSse(harness.baseUrl() + "/p", body(), Collections.emptyMap(),
        (eventType, data) -> seen.add(data));
    assertEquals(List.of("line1\nline2"), seen);
  }

  @Test
  void sseFlagsHttpStatusAndRetryAfterOnErrorResponse() throws Exception {
    // 429 + Retry-After：必须把状态码与退避提示挂到异常上，供上层决定重试节奏
    harness = new ProtocolStreamHarness().replay(429, "application/json", "{\"error\":\"slow down\"}");
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new Probe().postSse(harness.baseUrl() + "/p", body(), Collections.emptyMap(), (t, d) -> { }));
    assertEquals(429, e.httpStatus());
    assertEquals(ModelApiError.Kind.RATE_LIMIT, e.kind());
  }

  @Test
  void sseWrapsHandlerFailureAndLogsIt() throws Exception {
    String sse = "data: boom\n\n";
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream", sse);
    final List<String> logged = new ArrayList<>();
    ErrorLog.setSink((type, summary, throwable, details) -> logged.add(type));
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new Probe().postSse(harness.baseUrl() + "/p", body(), Collections.emptyMap(),
            (eventType, data) -> {
              throw new RuntimeException("handler exploded");
            }));
    assertTrue(e.getMessage().contains("Model stream communication failed"), e.getMessage());
    assertTrue(logged.contains("sse_error"), "应记录 sse_error 日志：" + logged);
  }

  @Test
  void postJsonNonSuccessLogsAndTruncatesResponse() throws Exception {
    // 400（不可重试）避免重试等待；错误响应体必须进日志并截断
    final List<String> details = new ArrayList<>();
    ErrorLog.setSink((type, summary, throwable, detail) -> details.add(type + "|" + detail));
    harness = new ProtocolStreamHarness().replay(400, "application/json", "y".repeat(20000));
    assertThrows(ModelCompletionException.class,
        () -> new Probe().post(harness.baseUrl() + "/p", body(), Collections.emptyMap()));
    assertTrue(details.get(0).startsWith("http|"), "应记录 http 类别错误日志");
    assertTrue(details.get(0).contains("已截断"), "超长响应体必须截断");
  }

  // ---- 错误日志截断 ----

  @Test
  void oversizedRequestBodyIsTruncatedInErrorLog() throws Exception {
    final List<String> details = new ArrayList<>();
    ErrorLog.setSink((type, summary, throwable, detail) -> details.add(detail));
    harness = new ProtocolStreamHarness().replay(400, "application/json", "bad");
    JSONObject huge = new JSONObject();
    huge.put("blob", "x".repeat(20000));
    assertThrows(ModelCompletionException.class,
        () -> new Probe().post(harness.baseUrl() + "/p", huge, Collections.emptyMap()));
    assertTrue(details.get(0).contains("已截断"), "超长请求体必须在日志里截断");
  }

  @Test
  void parseErrorLogsRawResponse() {
    final List<String> details = new ArrayList<>();
    ErrorLog.setSink((type, summary, throwable, detail) -> details.add(detail));
    new Probe().parseError("原始响应", new RuntimeException("解析失败"));
    assertTrue(details.get(0).contains("原始响应"));
  }

  @Test
  void blankCustomHeaderIsNotSent() throws Exception {
    harness = new ProtocolStreamHarness().replay(200, "application/json", "{}");
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Real", "v");
    headers.put("X-Blank", "");
    new Probe().post(harness.baseUrl() + "/p", body(), headers);
    assertEquals("v", harness.lastRequest().header("X-Real"));
    // 空值头不应出现在服务端请求里
    assertEquals(null, harness.lastRequest().header("X-Blank"));
  }
}
