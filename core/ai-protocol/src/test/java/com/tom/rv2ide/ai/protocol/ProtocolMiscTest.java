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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 补齐协议层的零散分支：SSE 错误字段的可读化、无名字工具增量不静默丢弃、
 * ThinkTagParser 的跨帧标签、以及若干小类的默认方法/构造。
 */
final class ProtocolMiscTest {

  private ProtocolStreamHarness harness;

  @AfterEach
  void tearDown() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
  }

  private static ModelConfig config(String baseUrl) {
    return ModelConfig.builder("t", "t", ModelProtocolType.OPENAI_COMPATIBLE, "l", baseUrl, "k", "m").build();
  }

  // ---- SSE error 字段可读化 ----

  @Test
  void streamErrorFromPlainStringIsSurfaced() throws Exception {
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream",
        ProtocolStreamHarness.sse("{\"error\":\"plain failure\"}"));
    ModelCompletionException e = org.junit.jupiter.api.Assertions.assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().stream(config(harness.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(e.getMessage().contains("plain failure"), e.getMessage());
  }

  @Test
  void streamErrorObjectFallsBackToTypeThenCode() throws Exception {
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream",
        ProtocolStreamHarness.sse("{\"error\":{\"type\":\"invalid_request\"}}"));
    ModelCompletionException typeOnly = org.junit.jupiter.api.Assertions.assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().stream(config(harness.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(typeOnly.getMessage().contains("invalid_request"), typeOnly.getMessage());

    harness.close();
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream",
        ProtocolStreamHarness.sse("{\"error\":{\"code\":\"E_CODE\"}}"));
    ModelCompletionException codeOnly = org.junit.jupiter.api.Assertions.assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().stream(config(harness.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(codeOnly.getMessage().contains("E_CODE"), codeOnly.getMessage());
  }

  @Test
  void streamErrorObjectDecodesUnicodeEscapedMessage() throws Exception {
    // 服务端把中文错误信息序列化成 \\uXXXX，直接展示会变成一串转义符
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream",
        ProtocolStreamHarness.sse("{\"error\":{\"message\":\"\\u5bc6\\u94a5\\u65e0\\u6548\"}}"));
    ModelCompletionException e = org.junit.jupiter.api.Assertions.assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().stream(config(harness.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(e.getMessage().contains("密钥无效"), e.getMessage());
  }

  @Test
  void emptyErrorObjectYieldsEmptyDescription() throws Exception {
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream",
        ProtocolStreamHarness.sse("{\"error\":{}}"));
    ModelCompletionException e = org.junit.jupiter.api.Assertions.assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().stream(config(harness.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    // 不崩即可：描述为空也应正常抛出
    assertTrue(e.getMessage().startsWith("OpenAI stream error:"), e.getMessage());
  }

  // ---- 无名字工具增量不静默丢弃 ----

  @Test
  void namelessToolCallDeltaIsDroppedButRecorded() throws Exception {
    // 只有 arguments、始终没有 function.name 的增量会被丢弃，但不能静默——
    // 这种「模型什么都没做」最难排查，必须留下日志。
    final java.util.List<String> logged = new java.util.ArrayList<>();
    com.tom.rv2ide.ai.tool.api.ErrorLog.Sink previous = null;
    com.tom.rv2ide.ai.tool.api.ErrorLog.setSink(
        (type, summary, throwable, details) -> logged.add(type));
    try {
      harness = new ProtocolStreamHarness().replay(200, "text/event-stream",
          ProtocolStreamHarness.sse(
              "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}"));
      ModelCompletionResponse response = new OpenAiCompatibleProtocol()
          .stream(config(harness.baseUrl()), List.of(new UserModelMessage("q")), null, null, null);
      assertTrue(response.getToolCalls().isEmpty());
      assertTrue(logged.contains("parse"), "丢弃无名字增量必须记日志：" + logged);
    } finally {
      com.tom.rv2ide.ai.tool.api.ErrorLog.setSink(previous);
    }
  }

  @Test
  void streamReasoningWithCallbackAndTrailingFlush() throws Exception {
    // reasoning 分帧到达 + 回调 + 收尾 flush 路径
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream",
        ProtocolStreamHarness.sse(
            "{\"choices\":[{\"delta\":{\"reasoning\":\"思考一\"}}]}",
            "{\"choices\":[{\"delta\":{\"reasoning\":\"思考二\"}}]}"));
    final java.util.List<String> reasoningDeltas = new java.util.ArrayList<>();
    ModelCompletionResponse response = new OpenAiCompatibleProtocol().stream(
        config(harness.baseUrl()), List.of(new UserModelMessage("q")),
        new ModelStreamCallback() {
          @Override public void onTextDelta(String delta) { }
          @Override public void onReasoningDelta(String delta) { reasoningDeltas.add(delta); }
        }, null, null);
    assertTrue(response.getReasoningContent().contains("思考一"));
    assertFalse(reasoningDeltas.isEmpty());
  }

  @Test
  void streamContentWithCallbackDeliversTextDeltas() throws Exception {
    harness = new ProtocolStreamHarness().replay(200, "text/event-stream",
        ProtocolStreamHarness.sse("{\"choices\":[{\"delta\":{\"content\":\"正文\"}}]}"));
    final java.util.List<String> textDeltas = new java.util.ArrayList<>();
    new OpenAiCompatibleProtocol().stream(config(harness.baseUrl()), List.of(new UserModelMessage("q")),
        new ModelStreamCallback() {
          @Override public void onTextDelta(String delta) { textDeltas.add(delta); }
          @Override public void onReasoningDelta(String delta) { }
        }, null, null);
    assertEquals(List.of("正文"), textDeltas);
  }

  // ---- ThinkTagParser 跨帧 ----

  @Test
  void thinkTagSplitAcrossChunksIsReassembled() {
    ThinkTagParser parser = new ThinkTagParser();
    // 标签被切成两帧（"<thi" + "nk>"）：第一帧的 "<thi" 必须留到下一帧，不能被当成正文吐出
    ThinkTagParser.Result first = parser.append("a<thi");
    assertEquals("a", first.getText());
    ThinkTagParser.Result second = parser.append("nk>b</think>c");
    assertEquals("b", second.getThinking());
    assertEquals("c", second.getText());
  }

  @Test
  void unclosedThinkTagDeliversThinkingImmediately() {
    ThinkTagParser open = new ThinkTagParser();
    assertEquals("未闭合", open.append("<think>未闭合").getThinking());
    assertEquals("", open.append("").getText());
  }

  @Test
  void flushEmitsPendingPartialTagAsThinking() {
    // 帧尾是半个闭合标签：它留在缓冲里，flush 时按 thinking 交出，不能丢
    ThinkTagParser partial = new ThinkTagParser();
    partial.append("<think>abc</thi");
    assertEquals("</thi", partial.flush().getThinking());

    // 无标签正文是即时交付的，flush 之后没有残留
    ThinkTagParser plain = new ThinkTagParser();
    assertEquals("残留", plain.append("残留").getText());
    assertEquals("", plain.flush().getText());
  }

  @Test
  void nullDeltaIsTolerated() {
    ThinkTagParser parser = new ThinkTagParser();
    assertEquals("", parser.append(null).getText());
  }

  // ---- StreamWatchdog ----

  @Test
  void watchdogFiresForFirstTokenThenReportsReason() throws Exception {
    // idle 阈值下限被夹到 1000ms；这里直接构造一个极短的看门狗会等 1s，
    // 因此用 onActivity 标记首 token 后触发的是 IDLE。
    StreamWatchdog watchdog = new StreamWatchdog(1000);
    final java.util.concurrent.atomic.AtomicReference<StreamWatchdog.Reason> reason =
        new java.util.concurrent.atomic.AtomicReference<>();
    // listener 为空视为无效启动
    watchdog.start(null);
    assertFalse(watchdog.fired());
    watchdog.onActivity();
    watchdog.start(reason::set);
    // 重复 start 幂等（不抛异常）
    watchdog.start(reason::set);
    Thread.sleep(1500);
    watchdog.stop();
    assertTrue(watchdog.fired());
    assertEquals(StreamWatchdog.Reason.IDLE, reason.get());
  }

  @Test
  void watchdogReasonableDefaultAndState() {
    StreamWatchdog watchdog = new StreamWatchdog(5000);
    assertFalse(watchdog.fired());
    org.junit.jupiter.api.Assertions.assertNull(watchdog.firedReason());
    watchdog.stop();
  }

  // ---- 小类默认方法 / 构造 ----

  @Test
  void modelProtocolDefaultsAllFalse() {
    ModelProtocol protocol = new ModelProtocol() {
      @Override public ModelCompletionResponse complete(ModelConfig c, List<ModelMessage> m) { return null; }
      @Override public ModelCompletionResponse stream(ModelConfig c, List<ModelMessage> m,
          ModelStreamCallback cb, ModelCancellationToken t, ModelRequestOptions o) { return null; }
    };
    assertFalse(protocol.supportsNativeTools(null));
    assertFalse(protocol.supportsDedicatedCompression());
    assertFalse(protocol.supportsImageGeneration());
  }

  @Test
  void backoffPolicyExposesMaxDelayAndFloorsToMinimum() {
    assertEquals(32_000L, new BackoffPolicy().maxDelayMs());
    assertEquals(100L, new BackoffPolicy(100L).maxDelayMs());
    // 0/负数被夹到 1ms，避免忙循环
    assertEquals(1L, new BackoffPolicy(0L).maxDelayMs());
    assertEquals(1L, new BackoffPolicy(-5L).maxDelayMs());
  }

  @Test
  void backoffDelayRespectsInjectedRandomAndRetryAfter() {
    BackoffPolicy policy = new BackoffPolicy(1000L, null);
    assertEquals(500L, policy.delayMs(1));
    assertEquals(1000L, policy.delayMs(2));
    // 超过上限后封顶
    assertEquals(1000L, policy.delayMs(50));
    // attempt < 1 被夹到 1
    assertEquals(500L, policy.delayMs(0));
    // Retry-After 优先且绕过上限
    assertEquals(9000L, policy.delayMs(5, 9000L));
  }

  @Test
  void modelMessageDefaultReasoningContentIsEmpty() {
    assertEquals("", new UserModelMessage("x").getReasoningContent());
    assertEquals("x", new AssistantModelMessage("x", null).getContent());
  }

  @Test
  void completionResponseThreeArgConstructorDefaultsTokens() {
    ModelCompletionResponse response = new ModelCompletionResponse("t", "r", null);
    assertEquals(0, response.getInputTokens());
    assertEquals(0, response.getOutputTokens());
    assertFalse(response.hasToolCalls());
    assertEquals("r", new ModelCompletionResponse("t", "r").getReasoningContent());
  }

  @Test
  void appProxyProxyUrlEmptyWhenDisabled() {
    AppProxy.apply(null, 0);
    assertEquals("", AppProxy.proxyUrl());
  }
}
