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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 工具结果携带图片时的协议序列化。
 *
 * <p>背景：{@code file_read} 读图片时，图片不是正文，而是附加在工具结果上的负载。
 * 两条协议序列化路径必须把它编码成各自格式的 image block，否则模型只看到一句
 * 「图片已附加」的文字说明，却拿不到像素——这正是本次要修的缺陷。
 */
public class ToolResultImageSerializationTest {

  private static final String BASE64 = "QUJD"; // "ABC"

  private static ToolModelMessage imageToolMessage() throws Exception {
    return new ToolModelMessage(
        "Image logo.png (image/png, 3KB) is attached below.",
        "call-1",
        "file_read",
        false,
        ImageInputPayload.imageResultJson("image/png", BASE64));
  }

  @Test
  public void toolResultKindIsDistinctFromUserAttachmentKind() throws Exception {
    String toolJson = ImageInputPayload.imageResultJson("image/png", BASE64);
    // 用户附件的解析器不该认工具结果图片（kind 不同），否则二者会互相误读。
    assertNull(ImageInputPayload.fromRawInputJson(toolJson));
    // 反之亦然。
    assertNull(
        ImageInputPayload.fromImageResult(
            ImageInputPayload.rawInputJson("hi", "image/png", BASE64)));
    // 各自的解析器认自己的 kind。
    assertEquals(BASE64, ImageInputPayload.fromImageResult(toolJson).getDataBase64());
  }

  @Test
  public void anthropicToolResultCarriesImageBlock() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    JSONArray messages = protocol.messagesJsonForTest(List.of(imageToolMessage()));

    JSONObject userMessage = messages.getJSONObject(0);
    assertEquals("user", userMessage.getString("role"));
    JSONArray content = userMessage.getJSONArray("content");
    JSONObject toolResult = content.getJSONObject(0);
    assertEquals("tool_result", toolResult.getString("type"));
    assertEquals("call-1", toolResult.getString("tool_use_id"));

    // 有图片时 content 必须是块数组，而不是字符串。
    JSONArray blocks = toolResult.getJSONArray("content");
    assertEquals("text", blocks.getJSONObject(0).getString("type"));
    JSONObject image = blocks.getJSONObject(1);
    assertEquals("image", image.getString("type"));
    assertEquals("base64", image.getJSONObject("source").getString("type"));
    assertEquals("image/png", image.getJSONObject("source").getString("media_type"));
    assertEquals(BASE64, image.getJSONObject("source").getString("data"));
  }

  @Test
  public void anthropicPlainToolResultStaysString() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    JSONArray messages =
        protocol.messagesJsonForTest(
            List.of(new ToolModelMessage("plain output", "call-2", "file_read", false)));

    JSONObject toolResult = messages.getJSONObject(0).getJSONArray("content").getJSONObject(0);
    assertEquals("plain output", toolResult.getString("content"));
    assertTrue(toolResult.get("content") instanceof String);
  }

  @Test
  public void openAiToolResultCarriesImageUrl() throws Exception {
    OpenAiMessageSerializer serializer = new OpenAiMessageSerializer();
    JSONArray messages = serializer.messagesJsonForTest(List.of(imageToolMessage()));

    JSONObject tool = messages.getJSONObject(0);
    assertEquals("tool", tool.getString("role"));
    assertEquals("call-1", tool.getString("tool_call_id"));

    JSONArray content = tool.getJSONArray("content");
    assertEquals("text", content.getJSONObject(0).getString("type"));
    JSONObject image = content.getJSONObject(1);
    assertEquals("image_url", image.getString("type"));
    assertEquals(
        "data:image/png;base64," + BASE64, image.getJSONObject("image_url").getString("url"));
  }

  @Test
  public void openAiPlainToolResultStaysString() throws Exception {
    OpenAiMessageSerializer serializer = new OpenAiMessageSerializer();
    JSONArray messages =
        serializer.messagesJsonForTest(
            List.of(new ToolModelMessage("plain output", "call-2", "file_read", false)));

    JSONObject tool = messages.getJSONObject(0);
    assertTrue(tool.get("content") instanceof String);
    assertEquals("plain output", tool.getString("content"));
  }

  @Test
  public void openAiToolErrorKeepsPrefixedText() throws Exception {
    OpenAiMessageSerializer serializer = new OpenAiMessageSerializer();
    JSONArray messages =
        serializer.messagesJsonForTest(
            List.of(new ToolModelMessage("boom", "call-3", "file_read", true)));

    String content = messages.getJSONObject(0).getString("content");
    assertTrue(content.contains("boom"), content);
    assertTrue(content.contains("failed"), content);
  }
}
