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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 用户一次附多张图时的负载编解码与协议序列化。
 *
 * <p>背景：此前 {@code rawInputJson} 只能承载一张图，发送链路于是只把第一张塞进多模态
 * 通道、其余降级成路径文本——用户附 3 张时模型只看得到 1 张。这里锁定三件事：
 * 数组能完整往返、旧单图格式仍能读、两条协议路径都输出全部图片块。
 */
public class MultiImagePayloadTest {

  private static final String A = "QUFB"; // "AAA"
  private static final String B = "QkJC"; // "BBB"
  private static final String C = "Q0ND"; // "CCC"

  private static ImageInputPayload.Payload threeImages() throws Exception {
    return ImageInputPayload.fromRawInputJson(
        ImageInputPayload.rawInputJson(
            "描述这三张图",
            List.of(
                new ImageInputPayload.ImagePart("image/png", A),
                new ImageInputPayload.ImagePart("image/jpeg", B),
                new ImageInputPayload.ImagePart("image/webp", C))));
  }

  @Test
  public void multiImagePayloadRoundTrips() throws Exception {
    ImageInputPayload.Payload payload = threeImages();
    assertNotNull(payload);
    assertEquals("描述这三张图", payload.getPrompt());
    assertEquals(3, payload.getImages().size());
    assertEquals(A, payload.getImages().get(0).getDataBase64());
    assertEquals("image/jpeg", payload.getImages().get(1).getMimeType());
    assertEquals(C, payload.getImages().get(2).getDataBase64());
  }

  /**
   * 旧格式兼容：只写 {@code data_base64} 的历史消息必须仍能解析出一张图。
   *
   * <p>会话日志是 append-only 的，改动线格式不能把已落盘的历史图片读丢。
   */
  @Test
  public void legacySingleImagePayloadStillParses() throws Exception {
    String legacy = ImageInputPayload.rawInputJson("老消息", "image/png", A);
    assertTrue(!legacy.contains("\"images\""), "单图构造不应写出 images 字段");

    ImageInputPayload.Payload payload = ImageInputPayload.fromRawInputJson(legacy);
    assertNotNull(payload);
    assertEquals(1, payload.getImages().size());
    assertEquals(A, payload.getDataBase64());
  }

  @Test
  public void multiImagePayloadKeepsFirstImageInLegacyFields() throws Exception {
    String json =
        ImageInputPayload.rawInputJson(
            "hi",
            List.of(
                new ImageInputPayload.ImagePart("image/png", A),
                new ImageInputPayload.ImagePart("image/png", B)));
    JSONObject object = new JSONObject(json);
    // 旧字段仍指向第一张：没升级的读取端看到的行为与从前一致。
    assertEquals(A, object.getString("data_base64"));
    assertEquals(2, object.getJSONArray("images").length());
  }

  @Test
  public void emptyImageListIsNotAnImagePayload() throws Exception {
    ImageInputPayload.Payload payload =
        ImageInputPayload.fromRawInputJson(
            ImageInputPayload.rawInputJson("hi", List.of()));
    // 没有图片时不该产出「可用的图片负载」，否则发送端会发一条空 image block。
    assertTrue(payload == null || payload.getImages().isEmpty());
  }

  @Test
  public void openAiSerializesEveryImageInOrder() throws Exception {
    OpenAiMessageSerializer serializer = new OpenAiMessageSerializer();
    JSONArray messages =
        serializer.messagesJsonForTest(
            List.of(new UserModelMessage("描述这三张图", payloadJson())));

    JSONArray content = messages.getJSONObject(0).getJSONArray("content");
    // 1 个 text + 3 个 image_url
    assertEquals(4, content.length());
    assertEquals("text", content.getJSONObject(0).getString("type"));
    for (int i = 1; i <= 3; i++) {
      assertEquals("image_url", content.getJSONObject(i).getString("type"));
    }
    assertEquals(
        "data:image/png;base64," + A,
        content.getJSONObject(1).getJSONObject("image_url").getString("url"));
    assertEquals(
        "data:image/jpeg;base64," + B,
        content.getJSONObject(2).getJSONObject("image_url").getString("url"));
    assertEquals(
        "data:image/webp;base64," + C,
        content.getJSONObject(3).getJSONObject("image_url").getString("url"));
  }

  @Test
  public void anthropicSerializesEveryImageInOrder() throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    JSONArray messages =
        protocol.messagesJsonForTest(List.of(new UserModelMessage("描述这三张图", payloadJson())));

    JSONArray content = messages.getJSONObject(0).getJSONArray("content");
    assertEquals(4, content.length());
    assertEquals("text", content.getJSONObject(0).getString("type"));
    String[] expected = {A, B, C};
    String[] expectedMime = {"image/png", "image/jpeg", "image/webp"};
    for (int i = 0; i < 3; i++) {
      JSONObject image = content.getJSONObject(i + 1);
      assertEquals("image", image.getString("type"));
      assertEquals(expectedMime[i], image.getJSONObject("source").getString("media_type"));
      assertEquals(expected[i], image.getJSONObject("source").getString("data"));
    }
  }

  private static String payloadJson() throws Exception {
    return ImageInputPayload.rawInputJson(
        "描述这三张图",
        List.of(
            new ImageInputPayload.ImagePart("image/png", A),
            new ImageInputPayload.ImagePart("image/jpeg", B),
            new ImageInputPayload.ImagePart("image/webp", C)));
  }
}
