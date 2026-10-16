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
    /** 非空时 encodePng 返回它（模拟「超过内联上限」的巨图）。 */
    Encoded forcedEncode;
    /** forcedEncode 非空时，预览编码（有限预算）是否返回小图。 */
    boolean previewSucceeds = true;
    /** 为 true 时 encodePng 返回 null（模拟编码失败/OOM）。 */
    boolean encodeFails;
    /** scale 返回 null（模拟缩放失败）。 */
    boolean scaleFails;

    @Override
    public Decoded decode(byte[] raw, long maxPixels) {
      lastMaxPixels = (int) maxPixels;
      return decodeResult;
    }

    @Override
    public Encoded encodePng(int[] pixels, int width, int height, long maxInlineBytes) {
      encodeCalls++;
      lastMaxInlineBytes = maxInlineBytes;
      if (encodeFails) {
        return null;
      }
      if (forcedEncode != null) {
        // 全尺寸编码（预算 Long.MAX_VALUE）返回巨图；预览编码（有限预算）按配置
        // 返回小图或同样巨图——两种情况分别覆盖「降采样预览」与「未回传」分支。
        if (maxInlineBytes == Long.MAX_VALUE) {
          return forcedEncode;
        }
        if (previewSucceeds) {
          byte[] small = {(byte) 0x89, 'P', 'N', 'G'};
          return new Encoded(small, width, height);
        }
        return forcedEncode;
      }
      // 固定 PNG 魔数字节（内容不合法无所谓，工具层不做解码）
      byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
      return new Encoded(png, width, height);
    }

    @Override
    public Decoded scale(int[] pixels, int width, int height,
        int targetWidth, int targetHeight, String filter) {
      if (scaleFails) {
        return null;
      }
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

  // ---- luma_key / crop（此前未覆盖的分派路径）----

  @Test
  void lumaKeyDispatchesAndWritesOutput(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "src.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ImageTool tool = new ImageTool(port);

    ToolResult r =
        tool.execute(
            new JSONObject()
                .put("action", "luma_key")
                .put("path", "src.png")
                .put("key_color", "#00FF00"),
            contextFor(dir));

    assertFalse(r.isError(), r.getContent());
    assertTrue(port.encodeCalls >= 1, "luma_key 应经端口编码输出");
    assertTrue(r.getContent().contains("luma_key"), "结果应注明所用动作：" + r.getContent());
  }

  @Test
  void lumaKeyRejectsBadColorSpec(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "src.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolResult r =
        new ImageTool(port)
            .execute(
                new JSONObject()
                    .put("action", "luma_key")
                    .put("path", "src.png")
                    .put("key_color", "not-a-color"),
                contextFor(dir));
    assertTrue(r.isError(), "非法颜色应被拒绝");
    assertTrue(r.getContent().contains("#RRGGBB"), r.getContent());
  }

  @Test
  void cropRequiresValidRectAndRejectsNonPositiveSize(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "src.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ImageTool tool = new ImageTool(port);

    // 缺 rect
    ToolResult missing =
        tool.execute(
            new JSONObject().put("action", "crop").put("path", "src.png"), contextFor(dir));
    assertTrue(missing.isError());
    assertTrue(missing.getContent().contains("rect"), missing.getContent());

    // width 非正
    ToolResult bad =
        tool.execute(
            new JSONObject()
                .put("action", "crop")
                .put("path", "src.png")
                .put(
                    "rect",
                    new JSONObject().put("x", 0).put("y", 0).put("width", 0).put("height", 5)),
            contextFor(dir));
    assertTrue(bad.isError(), "非正尺寸应被拒绝");

    // 合法裁剪
    ToolResult ok =
        tool.execute(
            new JSONObject()
                .put("action", "crop")
                .put("path", "src.png")
                .put(
                    "rect",
                    new JSONObject().put("x", 0).put("y", 0).put("width", 1).put("height", 1)),
            contextFor(dir));
    assertFalse(ok.isError(), ok.getContent());
    assertTrue(ok.getContent().contains("crop"), ok.getContent());
  }

  // ---- runResize 的宽高比与上限逻辑（此前只测了参数校验）----

  @Test
  void resizeMaxDimensionPreservesAspectRatio(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "wide.png");
    FakePort port = new FakePort();
    // 4x2 的图：max_dimension=2 应得 2x1（保持 2:1 比例，两轴分别取整）。
    port.decodeResult = new ImageProcessingPort.Decoded(
        new int[] {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000},
        4, 2);
    ToolResult r =
        new ImageTool(port)
            .execute(
                new JSONObject()
                    .put("action", "resize")
                    .put("path", "wide.png")
                    .put("max_dimension", 2),
                contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().contains("2x1"), "应保持宽高比缩到 2x1：" + r.getContent());
  }

  @Test
  void resizeMaxDimensionLargerThanImageKeepsOriginal(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "small.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    // max_dimension 大于原图最长边 → 不放大，保持原尺寸。
    ToolResult r =
        new ImageTool(port)
            .execute(
                new JSONObject()
                    .put("action", "resize")
                    .put("path", "small.png")
                    .put("max_dimension", 999),
                contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().contains("2x2"), "不应放大小图：" + r.getContent());
  }

  @Test
  void resizeSingleAxisComputesOtherFromAspect(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "rect.png");
    FakePort port = new FakePort();
    port.decodeResult = new ImageProcessingPort.Decoded(
        new int[] {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000},
        4, 2);
    // 只给 width=8 → 高度按比例算 4。
    ToolResult r =
        new ImageTool(port)
            .execute(
                new JSONObject()
                    .put("action", "resize")
                    .put("path", "rect.png")
                    .put("width", 8)
                    .put("filter", "nearest"),
                contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().contains("8x4"), "单轴缩放应按比例算另一轴：" + r.getContent());
  }

  @Test
  void resizeBicubicNotesBilinearFallback(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "b.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    // bicubic 在 Android 上按双线性处理——结果里应注明，避免用户以为真做了 bicubic。
    ToolResult r =
        new ImageTool(port)
            .execute(
                new JSONObject()
                    .put("action", "resize")
                    .put("path", "b.png")
                    .put("max_dimension", 1)
                    .put("filter", "bicubic"),
                contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().contains("双线性"), "bicubic 应注明按双线性处理：" + r.getContent());
  }

  // ---- execute 的前置校验与失败分支 ----

  @Test
  void missingInputFileIsReported(@TempDir Path dir) throws Exception {
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "nope.png").put("key_color", "#FF0000"),
        contextFor(dir));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("不存在"), r.getContent());
  }

  @Test
  void nonImageContentIsRejectedBeforeDecode(@TempDir Path dir) throws Exception {
    // magic bytes 校验在解码之前——把文本当图片解码没有意义，先拦掉。
    Files.write(dir.resolve("fake.png"), "not an image".getBytes());
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "fake.png").put("key_color", "#FF0000"),
        contextFor(dir));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("magic bytes"), r.getContent());
    assertEquals(-1, port.lastMaxPixels, "校验失败不得进入解码");
  }

  @Test
  void decodeNullIsReportedWithPixelHint(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = null;
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "a.png").put("key_color", "#FF0000"),
        contextFor(dir));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("解码失败"), r.getContent());
  }

  @Test
  void cancellationBeforeChromaKeyReturnsCancelled(@TempDir Path dir) throws Exception {
    // 取消信号在像素处理前已置位：chromaKey 返回 null → 工具报「已取消」。
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolContext ctx =
        ToolContext.builder().homePath(dir.toString()).cancellation(() -> true).build();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "a.png").put("key_color", "#FF0000"),
        ctx);
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("已取消"), r.getContent());
  }

  @Test
  void cropOutsideImageIsRejected(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "crop").put("path", "a.png")
            .put("rect", new JSONObject().put("x", 100).put("y", 100).put("width", 5).put("height", 5)),
        contextFor(dir));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("无交集"), r.getContent());
  }

  @Test
  void cropOutputWritesFileAndReturnsImage(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "crop").put("path", "a.png")
            .put("rect", new JSONObject().put("x", 0).put("y", 0).put("width", 1).put("height", 2)),
        contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.hasImage(), "crop 应内联回传图片");
    // 落盘：ai-images/ 下有产物文件。
    File outDir = new File(dir.toFile(), "ai-images");
    File[] files = outDir.listFiles();
    assertNotNull(files);
    assertEquals(1, files.length, "应恰好落盘一个文件");
    assertTrue(files[0].getName().startsWith("crop-"), files[0].getName());
  }

  @Test
  void resizeScaleFailureIsReported(@TempDir Path dir) throws Exception {
    // bilinear 走端口 scale；端口返回 null（内存不足）时明确报错。
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    port.scaleFails = true;
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "resize").put("path", "a.png")
            .put("max_dimension", 1).put("filter", "bilinear"),
        contextFor(dir));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("缩放失败"), r.getContent());
  }

  @Test
  void resizeOversizedTargetIsRejected(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "resize").put("path", "a.png").put("width", 5000),
        contextFor(dir));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("上限"), r.getContent());
  }

  @Test
  void encodeFailureIsReported(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    port.encodeFails = true;
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "a.png").put("key_color", "#FF0000"),
        contextFor(dir));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("编码失败"), r.getContent());
  }

  @Test
  void oversizedEncodedImageUsesPreviewWithoutInline(@TempDir Path dir) throws Exception {
    // 全尺寸编码超过内联上限、预览编码也失败（返回 null）→ 文本结果说明未回传。
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    // 强制「全尺寸编码」返回超过 4MB 的字节，且预览编码也失败 → 走「未回传」分支。
    port.forcedEncode = new ImageProcessingPort.Encoded(new byte[4 * 1024 * 1024 + 1], 2, 2);
    port.previewSucceeds = false;
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "a.png").put("key_color", "#FF0000"),
        contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertFalse(r.hasImage(), "超过内联上限且预览失败时不应内联");
    assertTrue(r.getContent().contains("超过内联上限"), r.getContent());
    assertTrue(r.getContent().contains("文件路径"), r.getContent());
  }

  @Test
  void explicitOutputPathMustBePngAndNotExist(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    // 已存在的输出文件（resolveOutput 快速失败）
    Files.write(dir.resolve("exists.png"), new byte[] {1});
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "a.png")
            .put("key_color", "#FF0000").put("output", "exists.png"),
        contextFor(dir));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("已存在"), r.getContent());
  }

  @Test
  void autoKeyColorFromCornersIsUsedForChromaKey(@TempDir Path dir) throws Exception {
    // key_color=auto → 四角均值；红色图四角都是红，结果里应出现 #FF0000。
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "a.png").put("key_color", "auto"),
        contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().contains("#FF0000"), "auto 应解析为四角颜色：" + r.getContent());
  }

  @Test
  void oversizedImageGetsDownscaledPreviewInline(@TempDir Path dir) throws Exception {
    // 全尺寸编码 >4MB 但预览编码成功：内联预览 + 注明降采样，文件仍是全尺寸。
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    port.forcedEncode = new ImageProcessingPort.Encoded(new byte[4 * 1024 * 1024 + 1], 2, 2);
    port.previewSucceeds = true;
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "chroma_key").put("path", "a.png").put("key_color", "#FF0000"),
        contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.hasImage(), "预览编码成功时应内联降采样图");
    assertTrue(r.getContent().contains("降采样预览"), r.getContent());
  }

  @Test
  void resizeByHeightOnlyComputesWidthFromAspect(@TempDir Path dir) throws Exception {
    // 只给 height：宽度按宽高比推算（4x2 图 height=4 → width=8）。
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = new ImageProcessingPort.Decoded(
        new int[] {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000},
        4, 2);
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "resize").put("path", "a.png")
            .put("height", 4).put("filter", "nearest"),
        contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().contains("8x4"), "单轴高度缩放应推算宽度：" + r.getContent());
  }

  @Test
  void resizeCancelledBeforeScaling(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolContext ctx =
        ToolContext.builder().homePath(dir.toString()).cancellation(() -> true).build();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "resize").put("path", "a.png").put("max_dimension", 1),
        ctx);
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("已取消"), r.getContent());
  }

  @Test
  void lumaKeyCancelReturnsCancelled(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolContext ctx =
        ToolContext.builder().homePath(dir.toString()).cancellation(() -> true).build();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "luma_key").put("path", "a.png").put("key_color", "#00FF00"),
        ctx);
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("已取消"), r.getContent());
  }

  @Test
  void dominantColorsCancelledReturnsError(@TempDir Path dir) throws Exception {
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = red2x2();
    ToolContext ctx =
        ToolContext.builder().homePath(dir.toString()).cancellation(() -> true).build();
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "dominant_colors").put("path", "a.png"), ctx);
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("已取消"), r.getContent());
  }

  @Test
  void lumaKeyFullSuccessPath(@TempDir Path dir) throws Exception {
    // luma_key 成功路径：黑色图黑底抠图 → 透明像素比例高。
    writeFakePng(dir, "a.png");
    FakePort port = new FakePort();
    port.decodeResult = new ImageProcessingPort.Decoded(
        new int[] {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000}, 2, 2);
    ToolResult r = new ImageTool(port).execute(
        new JSONObject().put("action", "luma_key").put("path", "a.png"),
        contextFor(dir));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.hasImage(), r.getContent());
    assertTrue(r.getContent().contains("luma_key"), r.getContent());
    assertTrue(r.getContent().contains("透明像素"), r.getContent());
  }
}
