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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 用本地 HTTP 回放钉住 {@link OpenAiCompatibleProtocol} 的完整链路：
 * 非流式 complete、流式文本/思考/tool_calls、错误事件、内容过滤、
 * 以及请求体的 reasoning / tools 构造与错误分类。
 */
final class OpenAiCompatibleProtocolTest {

  private ProtocolStreamHarness harness;

  private ProtocolStreamHarness replay(int status, String body) throws Exception {
    harness = new ProtocolStreamHarness().replay(status, "text/event-stream", body);
    return harness;
  }

  @AfterEach
  void tearDown() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
  }

  private static ModelConfig config(String baseUrl) {
    return ModelConfig.builder("t", "t", ModelProtocolType.OPENAI_COMPATIBLE, "openai", baseUrl, "sk-k", "gpt-x")
        .build();
  }

  // ---- complete ----

  @Test
  void completeReadsTextAndUsage() throws Exception {
    ProtocolStreamHarness h = replay(200,
        "{\"choices\":[{\"message\":{\"content\":\"回答\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":9}}");
    ModelCompletionResponse response =
        new OpenAiCompatibleProtocol().complete(config(h.baseUrl()), List.of(new UserModelMessage("q")));
    assertEquals("回答", response.getText());
    assertEquals(5, response.getInputTokens());
    assertEquals(9, response.getOutputTokens());
    assertEquals("/chat/completions", h.lastRequest().path);
    assertEquals("Bearer sk-k", h.lastRequest().authorizationHeaders.get(0));
  }

  @Test
  void completeWithoutUsageYieldsZeroTokens() throws Exception {
    ProtocolStreamHarness h = replay(200, "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}");
    ModelCompletionResponse response =
        new OpenAiCompatibleProtocol().complete(config(h.baseUrl()), List.of(new UserModelMessage("q")));
    assertEquals(0, response.getInputTokens());
    assertEquals(0, response.getOutputTokens());
  }

  @Test
  void completeWrapsParseFailure() throws Exception {
    ProtocolStreamHarness h = replay(200, "not json at all");
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().complete(config(h.baseUrl()), List.of(new UserModelMessage("q"))));
    assertTrue(e.getMessage().contains("OpenAI compatible protocol parse failed"), e.getMessage());
  }

  @Test
  void completeSurfacesHttpErrorStatus() throws Exception {
    // 用 400（CLIENT_ERROR，不可重试）避免真实退避等待
    ProtocolStreamHarness h = replay(400, "{\"error\":\"boom\"}");
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().complete(config(h.baseUrl()), List.of(new UserModelMessage("q"))));
    assertTrue(e.getMessage().contains("400"), e.getMessage());
    assertEquals(1, h.requestCount());
  }

  @Test
  void completeRetriesOnceOnRetryableStatusThenSucceeds() throws Exception {
    // 500 属可重试分类：postJson 应重发一次并成功返回
    harness = new ProtocolStreamHarness()
        .enqueue(500, "application/json", "{\"error\":\"boom\"}")
        .enqueue(200, "application/json", "{\"choices\":[{\"message\":{\"content\":\"恢复\"}}]}");
    ModelCompletionResponse response = new OpenAiCompatibleProtocol()
        .complete(config(harness.baseUrl()), List.of(new UserModelMessage("q")));
    assertEquals("恢复", response.getText());
    assertEquals(2, harness.requestCount());
  }

  // ---- stream：文本 / 思考 ----

  @Test
  void streamAccumulatesContentDeltas() throws Exception {
    String sse = ProtocolStreamHarness.sse(
        "{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
        "{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}");
    ProtocolStreamHarness h = replay(200, sse);
    RecordingCallback callback = new RecordingCallback();
    ModelCompletionResponse response = new OpenAiCompatibleProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), callback, null, null);
    assertEquals("你好", response.getText());
    assertEquals(2, callback.text.size());
  }

  @Test
  void streamExtractsReasoningContentField() throws Exception {
    String sse = ProtocolStreamHarness.sse(
        "{\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\"}}]}");
    ProtocolStreamHarness h = replay(200, sse);
    ModelCompletionResponse response = new OpenAiCompatibleProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null);
    assertEquals("思考", response.getReasoningContent());
  }

  @Test
  void streamHandlesUsageOnlyChunkBeforeChoices() throws Exception {
    // 兼容端点常在最后一个 chunk（空 choices）里带 usage
    String sse = ProtocolStreamHarness.sse(
        "{\"usage\":{\"prompt_tokens\":13,\"completion_tokens\":4},\"choices\":[]}");
    ProtocolStreamHarness h = replay(200, sse);
    ModelCompletionResponse response = new OpenAiCompatibleProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null);
    assertEquals(13, response.getInputTokens());
    assertEquals(4, response.getOutputTokens());
  }

  @Test
  void streamParsesThinkTagsIntoReasoning() throws Exception {
    String sse = ProtocolStreamHarness.sse(
        "{\"choices\":[{\"delta\":{\"content\":\"<think>内心</think>正文\"}}]}");
    ProtocolStreamHarness h = replay(200, sse);
    ModelCompletionResponse response = new OpenAiCompatibleProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null);
    assertEquals("正文", response.getText());
    assertEquals("内心", response.getReasoningContent());
  }

  @Test
  void streamThrowsOnStreamErrorEvent() throws Exception {
    String sse = ProtocolStreamHarness.sse(
        "{\"error\":{\"message\":\"quota exhausted\",\"type\":\"insufficient_quota\"}}");
    ProtocolStreamHarness h = replay(200, sse);
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(e.getMessage().contains("quota exhausted"), e.getMessage());
  }

  @Test
  void streamThrowsWhenOutputBlockedByContentFilter() throws Exception {
    String sse = ProtocolStreamHarness.sse(
        "{\"choices\":[{\"finish_reason\":\"content_filter\"}]}");
    ProtocolStreamHarness h = replay(200, sse);
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(e.getMessage().contains("content safety policy"), e.getMessage());
  }

  @Test
  void streamSkipsEmptyOrNullChoiceChunks() throws Exception {
    String sse = ProtocolStreamHarness.sse(
        "{\"choices\":[]}",
        "{\"choices\":[null]}",
        "{\"choices\":[{\"delta\":null}]}");
    ProtocolStreamHarness h = replay(200, sse);
    ModelCompletionResponse response = new OpenAiCompatibleProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null);
    assertEquals("", response.getText());
  }

  @Test
  void streamCarriesBoundaryAndPartialOnParseFailure() throws Exception {
    // 出现工具调用后 SSE 数据非法：必须带出已累积内容与工具边界
    String sse = ProtocolStreamHarness.sse(
        "{\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}",
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
            + "\"function\":{\"name\":\"file_write\"}}]}}]}",
        "{invalid json");
    ProtocolStreamHarness h = replay(200, sse);
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new OpenAiCompatibleProtocol().stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(e.crossedToolBoundary());
    assertEquals("部分", e.partialText());
  }

  // ---- 请求体构造 ----

  @Test
  void streamRequestIncludesToolsWhenProvided() throws Exception {
    ProtocolStreamHarness h = replay(200, "data: [DONE]\n\n");
    ModelRequestOptions options = new ModelRequestOptions("off", false,
        Collections.singletonList(stubTool("file_read")));
    new OpenAiCompatibleProtocol().stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, options);
    JSONObject body = new JSONObject(h.lastRequest().body);
    assertEquals(1, body.getJSONArray("tools").length());
    assertEquals("auto", body.getString("tool_choice"));
    assertTrue(body.getBoolean("stream"));
  }

  @Test
  void reasoningRequestBodyAddsProviderSpecificFields() throws Exception {
    OpenAiCompatibleProtocol protocol = new OpenAiCompatibleProtocol();
    ModelConfig deepseek = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE,
        "deepseek", "https://api.deepseek.com", "k", "deepseek-chat").build();
    JSONObject body = protocol.reasoningRequestBodyForTest(deepseek, new ModelRequestOptions("high", false));
    assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
    assertEquals("high", body.getString("reasoning_effort"));

    ModelConfig qwen = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE,
        "qwen", "https://dashscope.aliyuncs.com", "k", "qwen-max").build();
    JSONObject qwenBody = protocol.reasoningRequestBodyForTest(qwen, new ModelRequestOptions("low", true));
    assertTrue(qwenBody.getBoolean("enable_thinking"));
    assertTrue(qwenBody.getBoolean("preserve_thinking"));
  }

  @Test
  void reasoningRequestBodyOmittedForNvidiaGateway() throws Exception {
    ModelConfig nvidia = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE,
        "NVIDIA", "https://integrate.api.nvidia.com", "k", "m").build();
    JSONObject body = new OpenAiCompatibleProtocol()
        .reasoningRequestBodyForTest(nvidia, new ModelRequestOptions("high", false));
    assertFalse(body.has("reasoning"));
    assertFalse(body.has("thinking"));
  }

  @Test
  void capabilityFlagsAreExposed() {
    OpenAiCompatibleProtocol protocol = new OpenAiCompatibleProtocol();
    assertTrue(protocol.supportsDedicatedCompression());
    assertTrue(protocol.supportsImageGeneration());
  }

  // ---- attachPartial ----

  @Test
  void attachPartialNoOpsForNullBufferOrExistingPartial() {
    ModelCompletionException plain = new ModelCompletionException("x");
    assertSame(plain, OpenAiCompatibleProtocol.attachPartial(plain, null));
    ModelCompletionException withPartial = new ModelCompletionException("x").withPartial("t", "r", false);
    AssistantCommitBuffer buffer = new AssistantCommitBuffer();
    buffer.appendText("y");
    assertSame(withPartial, OpenAiCompatibleProtocol.attachPartial(withPartial, buffer));
  }

  @Test
  void attachPartialNoOpsWhenBufferEmpty() {
    ModelCompletionException plain = new ModelCompletionException("x");
    assertSame(plain, OpenAiCompatibleProtocol.attachPartial(plain, new AssistantCommitBuffer()));
  }

  @Test
  void attachPartialCarriesBoundaryEvenWithoutText() {
    ModelCompletionException plain = new ModelCompletionException("x");
    AssistantCommitBuffer buffer = new AssistantCommitBuffer();
    buffer.markToolUseStarted();
    ModelCompletionException result = OpenAiCompatibleProtocol.attachPartial(plain, buffer);
    assertTrue(result.crossedToolBoundary());
    assertFalse(result.hasPartial());
  }

  private static com.tom.rv2ide.ai.tool.api.ToolInfo stubTool(String name) {
    return new com.tom.rv2ide.ai.tool.api.ToolInfo() {
      @Override public String getName() { return name; }
      @Override public String getDescription() { return "desc"; }
      @Override public com.tom.rv2ide.ai.tool.api.ToolCategory getCategory() {
        return com.tom.rv2ide.ai.tool.api.ToolCategory.READ;
      }
      @Override public boolean needsConfirmation() { return false; }
      @Override public String promptSupplement(String executionMode) { return null; }
      @Override public JSONObject getParameters() { return new JSONObject().put("type", "object"); }
      @Override public JSONObject toJson() {
        return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name));
      }
    };
  }

  private static final class RecordingCallback implements ModelStreamCallback {
    final List<String> text = new ArrayList<>();
    final List<String> reasoning = new ArrayList<>();

    @Override public void onTextDelta(String delta) { text.add(delta); }
    @Override public void onReasoningDelta(String delta) { reasoning.add(delta); }
  }
}
