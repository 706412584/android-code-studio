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

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 用本地 HTTP 回放钉住 {@link AnthropicMessagesProtocol} 的完整链路：
 * 非流式 complete、流式 stream 的事件分派（message_start / content_block_* /
 * input_json_delta）、错误路径、以及请求体的 messages/system/tools 序列化。
 */
final class AnthropicMessagesProtocolTest {

  private ProtocolStreamHarness harness;

  private ProtocolStreamHarness harnessFor(int status, String contentType, String body) throws Exception {
    harness = new ProtocolStreamHarness().replay(status, contentType, body);
    return harness;
  }

  private ProtocolStreamHarness harnessFor(int status, String body) throws Exception {
    return harnessFor(status, "text/event-stream", body);
  }

  @AfterEach
  void tearDown() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
  }

  private static ModelConfig config(String baseUrl) {
    return ModelConfig.builder("t", "t", ModelProtocolType.ANTHROPIC_MESSAGES, "ant", baseUrl, "sk-ant", "claude-x")
        .build();
  }

  private static JSONObject bodyOf(ProtocolStreamHarness h) throws Exception {
    return new JSONObject(h.lastRequest().body);
  }

  // ---- complete（非流式）----

  @Test
  void completeParsesTextBlocks() throws Exception {
    ProtocolStreamHarness h = harnessFor(200, "text/event-stream",
        "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}");
    ModelCompletionResponse response =
        new AnthropicMessagesProtocol().complete(config(h.baseUrl()), List.of(new UserModelMessage("hi")));
    assertEquals("你好", response.getText());
    assertFalse(response.hasToolCalls());
    assertEquals("/v1/messages", h.lastRequest().path);
    assertEquals("sk-ant", h.lastRequest().apiKeyHeaders.get(0));
    assertEquals("claude-x", bodyOf(h).getString("model"));
  }

  @Test
  void completeParsesToolUseBlocks() throws Exception {
    ProtocolStreamHarness h = harnessFor(200, "text/event-stream",
        "{\"content\":[{\"type\":\"text\",\"text\":\"调用\"},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"file_read\",\"input\":{\"file_path\":\"a\"}}]}");
    ModelCompletionResponse response =
        new AnthropicMessagesProtocol().complete(config(h.baseUrl()), List.of(new UserModelMessage("read")));
    assertEquals("调用", response.getText());
    assertEquals(1, response.getToolCalls().size());
    ToolCall call = response.getToolCalls().get(0);
    assertEquals("toolu_1", call.getId());
    assertEquals("file_read", call.getName());
    assertTrue(call.getArguments().contains("\"file_path\":\"a\""));
  }

  @Test
  void completeWithoutContentArrayYieldsEmptyText() throws Exception {
    ProtocolStreamHarness h = harnessFor(200, "text/event-stream", "{}");
    assertEquals("", new AnthropicMessagesProtocol()
        .complete(config(h.baseUrl()), List.of(new UserModelMessage("x"))).getText());
  }

  @Test
  void completeSurfacesHttpErrorStatus() throws Exception {
    harness = new ProtocolStreamHarness().replay(401, "application/json", "{\"error\":\"bad key\"}");
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new AnthropicMessagesProtocol().complete(config(harness.baseUrl()), List.of(new UserModelMessage("x"))));
    assertTrue(e.getMessage().contains("401"), e.getMessage());
  }

  @Test
  void completeWrapsParseFailureWhenBodyIsNotJson() throws Exception {
    // 200 但返回 HTML（网关登录页）：解析失败必须包装成 ModelCompletionException
    ProtocolStreamHarness h = harnessFor(200, "text/event-stream", "<html>not json</html>");
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new AnthropicMessagesProtocol().complete(config(h.baseUrl()), List.of(new UserModelMessage("x"))));
    assertTrue(e.getMessage().contains("Anthropic Messages protocol parse failed"), e.getMessage());
  }

  // ---- stream ----

  @Test
  void streamAccumulatesThinkingAndTextDeltas() throws Exception {
    String sse = ProtocolStreamHarness.sseEvent("message_start",
            "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":11}}}")
        + ProtocolStreamHarness.sseEvent("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}")
        + ProtocolStreamHarness.sseEvent("content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"想\"}}")
        + ProtocolStreamHarness.sseEvent("content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"答\"}}")
        + ProtocolStreamHarness.sseEvent("message_delta",
            "{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":7}}")
        + "data: [DONE]\n\n";
    ProtocolStreamHarness h = harnessFor(200, sse);
    RecordingCallback callback = new RecordingCallback();
    ModelCompletionResponse response = new AnthropicMessagesProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), callback, new ModelCancellationToken(),
            ModelRequestOptions.defaults());
    assertEquals("答", response.getText());
    assertEquals("想", response.getReasoningContent());
    assertEquals(11, response.getInputTokens());
    assertEquals(7, response.getOutputTokens());
    assertEquals("想", callback.reasoning.get(0));
    assertEquals("答", callback.text.get(0));
  }

  @Test
  void streamEmitsRedactedThinkingAsReasoning() throws Exception {
    String sse = ProtocolStreamHarness.sseEvent("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"redacted_thinking\"}}")
        + "data: [DONE]\n\n";
    ProtocolStreamHarness h = harnessFor(200, sse);
    ModelCompletionResponse response = new AnthropicMessagesProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null);
    assertEquals("[redacted thinking]", response.getReasoningContent());
  }

  @Test
  void streamAssemblesToolUseFromPartialJsonDeltas() throws Exception {
    String sse = ProtocolStreamHarness.sseEvent("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"file_write\"}}")
        + ProtocolStreamHarness.sseEvent("content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"file_path\\\":\"}}")
        + ProtocolStreamHarness.sseEvent("content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a.txt\\\"}\"}}")
        + "data: [DONE]\n\n";
    ProtocolStreamHarness h = harnessFor(200, sse);
    ModelCompletionResponse response = new AnthropicMessagesProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null);
    assertEquals(1, response.getToolCalls().size());
    ToolCall call = response.getToolCalls().get(0);
    assertEquals("toolu_9", call.getId());
    assertEquals("file_write", call.getName());
    assertEquals("{\"file_path\":\"a.txt\"}", call.getArguments());
  }

  @Test
  void streamTakesToolInputFromContentBlockStartWhenPresent() throws Exception {
    String sse = ProtocolStreamHarness.sseEvent("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"glob\",\"input\":{\"pattern\":\"**\"}}}")
        + "data: [DONE]\n\n";
    ProtocolStreamHarness h = harnessFor(200, sse);
    ModelCompletionResponse response = new AnthropicMessagesProtocol()
        .stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null);
    assertEquals("{\"pattern\":\"**\"}", response.getToolCalls().get(0).getArguments());
  }

  @Test
  void streamThrowsWhenErrorEventArrives() throws Exception {
    String sse = ProtocolStreamHarness.sseEvent("error",
            "{\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}");
    ProtocolStreamHarness h = harnessFor(200, sse);
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new AnthropicMessagesProtocol().stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(e.getMessage().contains("Anthropic stream error"), e.getMessage());
  }

  @Test
  void streamCarriesPartialAndToolBoundaryOnDisconnect() throws Exception {
    // 已收到工具调用块后连接被截断：异常必须带 partial 且标记越过工具边界，
    // 否则上层会认为可以重发、工具被重复执行。
    String sse = ProtocolStreamHarness.sseEvent("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_3\",\"name\":\"file_write\"}}")
        + ProtocolStreamHarness.sseEvent("content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"部分正文\"}}")
        + "data: {\"type\":\"content_block_delta\""; // 截断的最后一帧
    ProtocolStreamHarness h = harnessFor(200, sse);
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> new AnthropicMessagesProtocol().stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, null));
    assertTrue(e.crossedToolBoundary(), "工具边界必须带出");
    assertEquals("部分正文", e.partialText());
  }

  @Test
  void streamSendsThinkingBudgetWhenReasoningEnabled() throws Exception {
    ProtocolStreamHarness h = harnessFor(200, "data: [DONE]\n\n");
    new AnthropicMessagesProtocol().stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null,
        new ModelRequestOptions("high", false));
    JSONObject body = bodyOf(h);
    assertTrue(body.has("thinking"));
    assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
    assertEquals(8192, body.getJSONObject("thinking").getInt("budget_tokens"));
    assertTrue(body.getBoolean("stream"));
  }

  @Test
  void streamSendsToolsWhenProvided() throws Exception {
    ProtocolStreamHarness h = harnessFor(200, "data: [DONE]\n\n");
    ModelRequestOptions options = new ModelRequestOptions("off", false,
        Collections.singletonList(stubTool("file_read")));
    new AnthropicMessagesProtocol().stream(config(h.baseUrl()), List.of(new UserModelMessage("q")), null, null, options);
    JSONArray tools = bodyOf(h).getJSONArray("tools");
    assertEquals(1, tools.length());
    assertEquals("file_read", tools.getJSONObject(0).getString("name"));
    assertTrue(tools.getJSONObject(0).has("input_schema"));
  }

  @Test
  void streamIncludesSystemPromptAndOmitsSystemMessagesFromMessages() throws Exception {
    ProtocolStreamHarness h = harnessFor(200, "data: [DONE]\n\n");
    List<ModelMessage> messages = Arrays.asList(
        new SystemModelMessage("你是助手"),
        new SystemModelMessage("第二条规则"),
        new UserModelMessage("问题"));
    new AnthropicMessagesProtocol().stream(config(h.baseUrl()), messages, null, null, null);
    JSONObject body = bodyOf(h);
    assertEquals("你是助手\n第二条规则", body.getString("system"));
    JSONArray sent = body.getJSONArray("messages");
    assertEquals(1, sent.length());
    assertEquals("user", sent.getJSONObject(0).getString("role"));
  }

  // ---- messagesJson / toolsJson 序列化（包级测试入口）----

  @Test
  void serializesToolResultMessagesAsUserToolResultBlocks() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    List<ModelMessage> messages = Arrays.asList(
        new ToolModelMessage("结果1", "toolu_1", "file_read"),
        new ToolModelMessage("失败", "toolu_2", "shell_execute", true));
    JSONArray json = protocol.messagesJsonForTest(messages);
    assertEquals(1, json.length());
    JSONObject wrapper = json.getJSONObject(0);
    assertEquals("user", wrapper.getString("role"));
    JSONArray content = wrapper.getJSONArray("content");
    assertEquals("tool_result", content.getJSONObject(0).getString("type"));
    assertEquals("toolu_1", content.getJSONObject(0).getString("tool_use_id"));
    assertTrue(content.getJSONObject(1).getBoolean("is_error"));
  }

  @Test
  void serializesAssistantToolCallsAsToolUseBlocks() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    List<ToolCall> calls = Collections.singletonList(new ToolCall("toolu_5", "glob", "{\"pattern\":\"**\"}"));
    AssistantModelMessage assistant = new AssistantModelMessage("先说一句", "", calls);
    JSONArray json = protocol.messagesJsonForTest(Collections.singletonList(assistant));
    JSONArray blocks = json.getJSONObject(0).getJSONArray("content");
    assertEquals("text", blocks.getJSONObject(0).getString("type"));
    assertEquals("tool_use", blocks.getJSONObject(1).getString("type"));
    assertEquals("glob", blocks.getJSONObject(1).getString("name"));
  }

  @Test
  void serializesNonJsonToolArgumentsAsValueWrapper() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    List<ToolCall> calls = Collections.singletonList(new ToolCall("toolu_6", "shell_execute", "ls -la"));
    AssistantModelMessage assistant = new AssistantModelMessage("", "", calls);
    JSONArray blocks = protocol.messagesJsonForTest(Collections.singletonList(assistant))
        .getJSONObject(0).getJSONArray("content");
    assertEquals("ls -la", blocks.getJSONObject(0).getJSONObject("input").getString("value"));
  }

  @Test
  void serializesUserImageAttachmentAsImageBlock() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    UserModelMessage user = new UserModelMessage("看图", ImageInputPayload.rawInputJson("描述", "image/png", "QUJD"));
    JSONArray blocks = protocol.messagesJsonForTest(Collections.singletonList(user))
        .getJSONObject(0).getJSONArray("content");
    assertEquals("text", blocks.getJSONObject(0).getString("type"));
    JSONObject image = blocks.getJSONObject(1);
    assertEquals("image", image.getString("type"));
    assertEquals("base64", image.getJSONObject("source").getString("type"));
    assertEquals("QUJD", image.getJSONObject("source").getString("data"));
  }

  @Test
  void serializesPlainUserTextAsStringContent() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    JSONArray json = protocol.messagesJsonForTest(Collections.singletonList(new UserModelMessage("纯文本")));
    assertEquals("纯文本", json.getJSONObject(0).getString("content"));
  }

  @Test
  void serializesToolResultWithImageAsBlockArray() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    ToolModelMessage tool = new ToolModelMessage("截图完成", "toolu_7", "phone_screenshot", false,
        ImageInputPayload.imageResultJson("image/png", "QUJD"));
    JSONArray content = protocol.messagesJsonForTest(Collections.singletonList(tool))
        .getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONArray("content");
    assertEquals("text", content.getJSONObject(0).getString("type"));
    assertEquals("image", content.getJSONObject(1).getString("type"));
  }

  @Test
  void supportsNativeToolsIsAlwaysTrue() {
    assertTrue(new AnthropicMessagesProtocol().supportsNativeTools(null));
  }

  @Test
  void attachPartialIsNoOpWhenExceptionAlreadyHasPartial() {
    ModelCompletionException e = new ModelCompletionException("x").withPartial("t", "r", false);
    AssistantCommitBuffer buffer = new AssistantCommitBuffer();
    buffer.appendText("other");
    assertSame(e, AnthropicMessagesProtocol.attachPartial(e, buffer));
  }

  @Test
  void attachPartialIsNoOpWhenBufferEmpty() {
    ModelCompletionException e = new ModelCompletionException("x");
    assertSame(e, AnthropicMessagesProtocol.attachPartial(e, new AssistantCommitBuffer()));
    assertSame(e, AnthropicMessagesProtocol.attachPartial(e, null));
  }

  private static ToolInfo stubTool(String name) {
    return new ToolInfo() {
      @Override public String getName() { return name; }
      @Override public String getDescription() { return "desc"; }
      @Override public ToolCategory getCategory() { return ToolCategory.READ; }
      @Override public boolean needsConfirmation() { return false; }
      @Override public String promptSupplement(String executionMode) { return null; }
      @Override public JSONObject getParameters() {
        return new JSONObject().put("type", "object");
      }
      @Override public JSONObject toJson() { return new JSONObject(); }
    };
  }

  private static final class RecordingCallback implements ModelStreamCallback {
    final List<String> text = new ArrayList<>();
    final List<String> reasoning = new ArrayList<>();

    @Override public void onTextDelta(String delta) { text.add(delta); }
    @Override public void onReasoningDelta(String delta) { reasoning.add(delta); }
  }
}
