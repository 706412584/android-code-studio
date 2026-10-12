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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ImageTool} 的行为：参数校验、action 分派、schema 结构、输出落盘。
 */
class ImageToolTest {

  /** 假端口：可配置解码结果，记录编码调用。 */
  private static final class FakePort implements ImageProcessingPort {
    Decoded decodeResult;
    int lastMaxPixels = -1;
    long lastMaxInlineBytes = -1;
    int encodeCalls = 0;

    @Override
    public Decoded decode(byte[] raw, long maxPixels) {
      lastMaxPixels = (int) maxPixels;
      return decodeResult;
    }

    @Override
    public Encoded encodePng(int[] pixels, int width, int height, long maxInlineBytes) {
      encodeCalls++;
      lastMaxInlineBytes = maxInlineBytes;
      // 固定 PNG 魔数字节（内容不合法无所谓，工具层不做解码）
      byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
      return new Encoded(png, width, height);
    }

    @Override
    public Decoded scale(int[] pixels, int width, int height,
        int targetWidth, int targetHeight, String filter) {
      int[] out = ImageOps.scaleNearest(pixels, width, height, targetWidth, targetHeight);
      return new Decoded(out, targetWidth, targetHeight);
    }
  }

  /** 造一个 PNG 魔数的假输入文件（工具层只校验 magic bytes）。 */
  private static File writeFakePng(Path dir, String name) throws Exception {
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 2, 0, 0, 0, 2, 8, 6, 0, 0, 0};
    Path f = dir.resolve(name);
    Files.write(f, png);
    return f.toFile();
  }

  private static ToolContext contextFor(Path home) {
    return ToolContext.builder().homePath(home.toString()).build();
  }

  private static ImageProcessingPort.Decoded red2x2() {
    int[] px = {
        0xFFFF0000, 0xFFFF0000,
        0xFFFF0000, 0xFFFF0000,
    };
    return new ImageProcessingPort.Decoded(px, 2, 2);
  }

  // ---------------------------------------------------------------------

  @Test
  void missingPortExplains(@TempDir Path dir) throws Exception {
    ImageTool tool = new ImageTool(null);
    JSONObject input = new JSONObject().put("action", "chroma_key").put("path", "a.png");
    ToolResult result = tool.execute(input, contextFor(dir));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未接入图像处理能力"), result.getContent());
  }

  @Test
  void rejectsBlankPathAndUnknownAction(@TempDir Path dir) {
    FakePort port = new FakePort();
    ImageTool tool = new ImageTool(port);
    ToolResult r1 = tool.execute(new JSONObject().put("action", "chroma_key"), contextFor(dir));
    assertTrue(r1.isError());
    assertTrue(r1.getContent().contains("path 不能为空"), r1.getContent());

    ToolResult r2 = tool.execute(
        new JSONObject().put("action", "nope").put("path", "a.png"), contextFor(dir));
    assertTrue(r2.isError());
    assertTrue(r2.getContent().contains("action 只能是"), r2.getContent());
  }

  @Test
  void chromaKeyDispatchesToOpsAndReturnsImage(@TempDir Path dir) throws Exception {
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    writeFakePng(dir, "a.png");
    ImageTool tool = new ImageTool(port);
    JSONObject input = new JSONObject()
        .put("action", "chroma_key").put("path", "a.png").put("key_color", "#FF0000");
    ToolResult result = tool.execute(input, contextFor(dir));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.hasImage(), "应内联回传图片");
    assertEquals("image/png", result.getImageMimeType());
    assertTrue(result.getContent().contains("文件路径:"), result.getContent());
    assertTrue(result.getContent().contains("透明像素:"), result.getContent());
    // 落盘的全尺寸编码预算 = Long.MAX_VALUE
    assertEquals(Long.MAX_VALUE, port.lastMaxInlineBytes, "落盘用全尺寸编码");
  }

  @Test
  void outputPathMustEndWithPng(@TempDir Path dir) throws Exception {
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    writeFakePng(dir, "a.png");
    ImageTool tool = new ImageTool(port);
    JSONObject input = new JSONObject()
        .put("action", "chroma_key").put("path", "a.png")
        .put("key_color", "#FF0000").put("output", "x.jpg");
    ToolResult result = tool.execute(input, contextFor(dir));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains(".png"), result.getContent());
  }

  @Test
  void existingOutputIsNotOverwritten(@TempDir Path dir) throws Exception {
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    writeFakePng(dir, "a.png");
    Files.write(dir.resolve("out.png"), new byte[] {1, 2, 3});
    ImageTool tool = new ImageTool(port);
    JSONObject input = new JSONObject()
        .put("action", "chroma_key").put("path", "a.png")
        .put("key_color", "#FF0000").put("output", "out.png");
    ToolResult result = tool.execute(input, contextFor(dir));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("不覆盖"), result.getContent());
  }

  @Test
  void oversizedInputRejectedByPortNull(@TempDir Path dir) throws Exception {
    FakePort port = new FakePort();
    port.decodeResult = null; // 端口内两段式预查拒绝（超像素上限）
    writeFakePng(dir, "a.png");
    ImageTool tool = new ImageTool(port);
    JSONObject input = new JSONObject()
        .put("action", "chroma_key").put("path", "a.png").put("key_color", "#FF0000");
    ToolResult result = tool.execute(input, contextFor(dir));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("像素超过上限"), result.getContent());
    assertEquals(12_000_000, port.lastMaxPixels, "端口收到 MAX_INPUT_PIXELS");
  }

  @Test
  void dominantColorsReturnsTextOnly(@TempDir Path dir) throws Exception {
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    writeFakePng(dir, "a.png");
    ImageTool tool = new ImageTool(port);
    JSONObject input = new JSONObject()
        .put("action", "dominant_colors").put("path", "a.png");
    ToolResult result = tool.execute(input, contextFor(dir));
    assertFalse(result.isError(), result.getContent());
    assertFalse(result.hasImage(), "dominant_colors 无图片负载");
    assertTrue(result.getContent().contains("#FF0000"), result.getContent());
    assertTrue(result.getContent().contains("%"), result.getContent());
  }

  @Test
  void defaultsMatchDocumentedTable(@TempDir Path dir) throws Exception {
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    writeFakePng(dir, "a.png");
    ImageTool tool = new ImageTool(port);
    JSONObject input = new JSONObject()
        .put("action", "chroma_key").put("path", "a.png").put("key_color", "#FF0000");
    ToolResult result = tool.execute(input, contextFor(dir));
    assertFalse(result.isError(), result.getContent());
    String content = result.getContent();
    assertTrue(content.contains("threshold=20"), content);
    assertTrue(content.contains("softness=32"), content);
    assertTrue(content.contains("despill=0.85"), content);
    assertTrue(content.contains("halo=0"), content);
  }

  @Test
  void resizeMutualExclusionAndFilterValidation(@TempDir Path dir) throws Exception {
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    writeFakePng(dir, "a.png");
    ImageTool tool = new ImageTool(port);
    // max_dimension 与 width 同给 → 报错
    ToolResult r1 = tool.execute(new JSONObject()
        .put("action", "resize").put("path", "a.png")
        .put("max_dimension", 10).put("width", 5), contextFor(dir));
    assertTrue(r1.isError());
    assertTrue(r1.getContent().contains("互斥"), r1.getContent());
    // filter 非法 → 报错
    ToolResult r2 = tool.execute(new JSONObject()
        .put("action", "resize").put("path", "a.png")
        .put("max_dimension", 10).put("filter", "lanczos"), contextFor(dir));
    assertTrue(r2.isError());
    assertTrue(r2.getContent().contains("filter"), r2.getContent());
    // nearest 路径（纯数组，不走端口 scale）
    ToolResult r3 = tool.execute(new JSONObject()
        .put("action", "resize").put("path", "a.png")
        .put("max_dimension", 4).put("filter", "nearest"), contextFor(dir));
    assertFalse(r3.isError(), r3.getContent());
  }

  @Test
  void parametersSchemaHasRequiredAtTopLevel() throws Exception {
    JSONObject params = new ImageTool(new FakePort()).getParameters();
    assertEquals("object", params.optString("type"));
    JSONObject props = params.optJSONObject("properties");
    assertNotNull(props);
    org.json.JSONArray required = params.optJSONArray("required");
    assertNotNull(required, "required 必须在顶层");
    assertEquals(2, required.length());
    assertFalse(props.has("required"), "required 不得嵌套在 properties 内");
    for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) {
      String key = it.next();
      Object value = props.get(key);
      assertTrue(value instanceof JSONObject,
          "properties." + key + " 必须是 schema 对象，实际是 " + value.getClass().getSimpleName());
    }
  }

  @Test
  void toolMetadataStable() {
    ImageTool tool = new ImageTool(new FakePort());
    assertEquals(ToolNames.IMAGE, tool.getName());
    assertEquals("image_tool", tool.getName());
    assertEquals(ToolCategory.GENERATE, tool.getCategory());
    assertEquals(ToolDisplayCategory.GENERIC, tool.getDisplayCategory());
    assertTrue(tool.isConcurrencySafe());
    assertFalse(tool.needsConfirmation());
  }
}
