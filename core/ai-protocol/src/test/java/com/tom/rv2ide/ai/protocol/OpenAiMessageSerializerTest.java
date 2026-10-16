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

import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 钉住 OpenAI 兼容消息序列化的关键取舍：工具结果正文保持字符串、图片被搬到一条
 * 紧随其后的 user 消息（否则部分网关静默丢图）、以及失败结果前缀工具名。
 */
final class OpenAiMessageSerializerTest {

  private final OpenAiMessageSerializer serializer = new OpenAiMessageSerializer();

  @Test
  void plainMessagesKeepRoleAndContent() throws Exception {
    JSONArray json = serializer.messagesJsonForTest(Arrays.asList(
        new SystemModelMessage("sys"),
        new UserModelMessage("你好"),
        new AssistantModelMessage("回复")));
    assertEquals("system", json.getJSONObject(0).getString("role"));
    assertEquals("sys", json.getJSONObject(0).getString("content"));
    assertEquals("你好", json.getJSONObject(1).getString("content"));
    assertEquals("回复", json.getJSONObject(2).getString("content"));
  }

  @Test
  void toolMessageKeepsStringContentAndCallId() throws Exception {
    JSONArray json = serializer.messagesJsonForTest(
        Collections.singletonList(new ToolModelMessage("结果", "call_1", "file_read")));
    JSONObject tool = json.getJSONObject(0);
    assertEquals("tool", tool.getString("role"));
    assertEquals("call_1", tool.getString("tool_call_id"));
    assertEquals("结果", tool.getString("content"));
    assertEquals(1, json.length(), "纯文本工具结果不应额外产生消息");
  }

  @Test
  void toolErrorContentIsPrefixedWithToolName() throws Exception {
    JSONArray json = serializer.messagesJsonForTest(
        Collections.singletonList(new ToolModelMessage("炸了", "call_1", "shell_execute", true)));
    String content = json.getJSONObject(0).getString("content");
    assertTrue(content.startsWith("Tool shell_execute failed:"), content);
    assertTrue(content.contains("炸了"));
  }

  @Test
  void toolErrorMessageWithoutToolNameFallsBackToCallId() throws Exception {
    JSONArray json = serializer.messagesJsonForTest(Collections.singletonList(
        new ToolModelMessage("炸了", "call_9", "", true)));
    assertTrue(json.getJSONObject(0).getString("content").contains("Tool call_9 failed:"));
  }

  @Test
  void toolResultImageBecomesFollowingUserMessage() throws Exception {
    // 关键行为：图片不能留在 role=tool 的 content 里（网关会静默丢弃），
    // 而应作为紧随其后的 user 消息投递。
    ToolModelMessage tool = new ToolModelMessage("截图完成", "call_2", "phone_screenshot", false,
        ImageInputPayload.imageResultJson("image/png", "QUJD"));
    JSONArray json = serializer.messagesJsonForTest(Collections.singletonList(tool));
    assertEquals(2, json.length());
    assertEquals("tool", json.getJSONObject(0).getString("role"));
    assertEquals("截图完成", json.getJSONObject(0).getString("content"));
    JSONObject carrier = json.getJSONObject(1);
    assertEquals("user", carrier.getString("role"));
    JSONArray content = carrier.getJSONArray("content");
    assertTrue(content.getJSONObject(0).getString("text").contains("phone_screenshot"));
    assertEquals("data:image/png;base64,QUJD",
        content.getJSONObject(1).getJSONObject("image_url").getString("url"));
  }

  @Test
  void assistantWithToolCallsSerializesFunctionSyntax() throws Exception {
    AssistantModelMessage assistant = new AssistantModelMessage("", "", Collections.singletonList(
        new ToolCall("call_3", "file_write", "{\"file_path\":\"a\"}")));
    JSONObject json = serializer.messagesJsonForTest(Collections.singletonList(assistant)).getJSONObject(0);
    // 无正文时 content 必须是 JSON null（不是空串），否则部分端点拒绝
    assertEquals(JSONObject.NULL, json.get("content"));
    JSONArray calls = json.getJSONArray("tool_calls");
    assertEquals("call_3", calls.getJSONObject(0).getString("id"));
    assertEquals("function", calls.getJSONObject(0).getString("type"));
    assertEquals("file_write", calls.getJSONObject(0).getJSONObject("function").getString("name"));
  }

  @Test
  void assistantWithTextAndToolCallsKeepsText() throws Exception {
    AssistantModelMessage assistant = new AssistantModelMessage("先说明", "", Collections.singletonList(
        new ToolCall("call_4", "glob", "{}")));
    JSONObject json = serializer.messagesJsonForTest(Collections.singletonList(assistant)).getJSONObject(0);
    assertEquals("先说明", json.getString("content"));
  }

  @Test
  void userImageAttachmentBecomesImageUrlBlock() throws Exception {
    UserModelMessage user = new UserModelMessage("看图",
        ImageInputPayload.rawInputJson("描述这张图", "image/png", "QUJD"));
    JSONObject json = serializer.messagesJsonForTest(Collections.singletonList(user)).getJSONObject(0);
    JSONArray content = json.getJSONArray("content");
    assertEquals("text", content.getJSONObject(0).getString("type"));
    assertEquals("描述这张图", content.getJSONObject(0).getString("text"));
    assertEquals("data:image/png;base64,QUJD",
        content.getJSONObject(1).getJSONObject("image_url").getString("url"));
  }

  @Test
  void userImageAttachmentFallsBackToMessageContentAsPrompt() throws Exception {
    UserModelMessage user = new UserModelMessage("兜底提示",
        ImageInputPayload.rawInputJson("", "image/png", "QUJD"));
    JSONObject json = serializer.messagesJsonForTest(Collections.singletonList(user)).getJSONObject(0);
    assertEquals("兜底提示", json.getJSONArray("content").getJSONObject(0).getString("text"));
  }

  @Test
  void preserveReasoningAddsReasoningContentForAssistantOnly() throws Exception {
    List<ModelMessage> messages = Arrays.asList(
        new AssistantModelMessage("正文", "我的思考"),
        new UserModelMessage("提问"));
    JSONArray json = serializer.messagesJson(Arrays.asList(messages.get(0)), true);
    assertEquals("我的思考", json.getJSONObject(0).getString("reasoning_content"));

    // preserve=false 时不带 reasoning_content
    JSONArray without = serializer.messagesJson(Collections.singletonList(messages.get(0)), false);
    assertFalse(without.getJSONObject(0).has("reasoning_content"));

    // 非 assistant 消息即便 preserve=true 也不带
    JSONArray userJson = serializer.messagesJson(Collections.singletonList(messages.get(1)), true);
    assertFalse(userJson.getJSONObject(0).has("reasoning_content"));
  }

  @Test
  void defaultMessagesJsonDoesNotPreserveReasoning() throws Exception {
    JSONArray json = serializer.messagesJson(Collections.singletonList(
        new AssistantModelMessage("正文", "思考")));
    assertFalse(json.getJSONObject(0).has("reasoning_content"));
  }
}
