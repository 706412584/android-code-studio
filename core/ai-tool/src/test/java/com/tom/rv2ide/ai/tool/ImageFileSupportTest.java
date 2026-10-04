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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ImageDataProvider;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code file_read} 读取图片的路径。
 *
 * <p>关键契约：按 magic bytes 而非扩展名校验内容（把 HTTP 错误页存成 .png 是常见陷阱）、
 * 无缩放实现时退化为原图、结果以图片负载而非文本承载。
 */
final class ImageFileSupportTest {

  /** 最小合法 PNG 头 + 一点数据，足以通过 magic bytes 校验。 */
  private static byte[] pngBytes() {
    return new byte[] {
      (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
      0x00, 0x00, 0x00, 0x0D
    };
  }

  private static byte[] jpegBytes() {
    return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
  }

  @Test
  void detectsPngAndJpegMagicBytes() {
    assertEquals("image/png", ImageFileSupport.detectMimeType(pngBytes()));
    assertEquals("image/jpeg", ImageFileSupport.detectMimeType(jpegBytes()));
    assertNull(ImageFileSupport.detectMimeType("not an image".getBytes()));
  }

  @Test
  void extensionFilterAcceptsPngAndJpegOnly() {
    assertTrue(ImageFileSupport.hasImageExtension(new java.io.File("a.png")));
    assertTrue(ImageFileSupport.hasImageExtension(new java.io.File("a.JPG")));
    assertTrue(ImageFileSupport.hasImageExtension(new java.io.File("a.jpeg")));
    assertFalse(ImageFileSupport.hasImageExtension(new java.io.File("a.gif")));
    assertFalse(ImageFileSupport.hasImageExtension(new java.io.File("a.txt")));
  }

  @Test
  void readsPngIntoImagePayload(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("logo.png");
    Files.write(file, pngBytes());

    ImageFileSupport.Result result = ImageFileSupport.read(file.toFile(), null);

    assertFalse(result.isError(), result.error);
    assertEquals("image/png", result.mimeType);
    assertTrue(result.base64.length() > 0);
  }

  @Test
  void rejectsImageExtensionWithNonImageContent(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("error.png");
    Files.write(file, "<html>500 Internal Server Error</html>".getBytes());

    ImageFileSupport.Result result = ImageFileSupport.read(file.toFile(), null);

    // 这正是按扩展名放行会踩的坑：把错误页当图片发给模型。
    assertTrue(result.isError(), "非图片内容应被拒绝");
  }

  @Test
  void fallsBackToOriginalBytesWhenNoProvider() throws IOException {
    Path dir = Files.createTempDirectory("img");
    Path file = dir.resolve("logo.png");
    Files.write(file, pngBytes());
    try {
      ImageFileSupport.Result result = ImageFileSupport.read(file.toFile(), null);
      assertEquals(
          java.util.Base64.getEncoder().encodeToString(pngBytes()),
          result.base64,
          "无缩放实现时应原样返回");
      assertFalse(result.downscaled);
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(dir);
    }
  }

  @Test
  void usesProviderOutputWhenItShrinksTheImage(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("big.png");
    Files.write(file, pngBytes());
    // 模拟缩放实现：返回更小的 JPEG。
    ImageDataProvider shrinker =
        (raw, max) -> new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};

    ImageFileSupport.Result result = ImageFileSupport.read(file.toFile(), shrinker);

    assertFalse(result.isError(), result.error);
    assertTrue(result.downscaled, "缩放后应标记 downscaled");
    assertEquals("image/jpeg", result.mimeType, "应跟随缩放实现的实际编码");
  }

  @Test
  void fileReadToolReturnsImagePayloadForPng(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("icon.png"), pngBytes());
    ToolContext ctx = ToolContext.builder().homePath(workspace.toString()).build();

    JSONObject args = new JSONObject().put("file_path", "icon.png");
    ToolResult result = new FileReadTool().execute(args, ctx);

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.hasImage(), "读图结果应携带图片负载");
    assertEquals("image/png", result.getImageMimeType());
    assertTrue(result.getContent().contains("icon.png"), "文字说明应指出图片来源");
  }

  @Test
  void fileReadToolRejectsFakePng(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("broken.png"), "not really an image".getBytes());
    ToolContext ctx = ToolContext.builder().homePath(workspace.toString()).build();

    ToolResult result =
        new FileReadTool().execute(new JSONObject().put("file_path", "broken.png"), ctx);

    assertTrue(result.isError(), "伪装成 png 的非图片内容应报错");
    assertFalse(result.hasImage());
  }

  @Test
  void fileReadToolRejectsUnsupportedImageFormats(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("anim.gif"), "GIF89a".getBytes());
    ToolContext ctx = ToolContext.builder().homePath(workspace.toString()).build();

    ToolResult result =
        new FileReadTool().execute(new JSONObject().put("file_path", "anim.gif"), ctx);

    // 不能被当文本读成乱码，而应明确告知格式不受支持。
    assertTrue(result.isError(), "不支持的图片格式应报错");
    assertTrue(result.getContent().contains("unsupported"), result.getContent());
  }
}
