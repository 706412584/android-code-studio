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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 图像处理工具（游戏素材制作）：色键/亮度键抠图、裁剪、缩放、主色提取。
 *
 * <p>单工具 + action 分派（对齐 {@link ArchiveTool} 的形态）。像素运算在
 * {@link ImageOps}（纯函数、JVM 可测）；字节↔位图的编解码经 {@link ImageProcessingPort}
 * 委托 app 层。
 */
public final class ImageTool extends BaseTool {

  // ---- action 常量（ArchiveTool 同款写法）----
  static final String ACTION_CHROMA_KEY = "chroma_key";
  static final String ACTION_LUMA_KEY = "luma_key";
  static final String ACTION_CROP = "crop";
  static final String ACTION_RESIZE = "resize";
  static final String ACTION_DOMINANT_COLORS = "dominant_colors";

  // ---- 上限 ----
  /**
   * 输入图解码后的像素数上限。400 万像素 ARGB_8888 ≈ 16MB。
   *
   * <p><b>为什么从 1200 万降到 400 万</b>：一次图像处理的峰值不止「解码后的位图」——
   * 还有解码出的 IntArray、处理后的输出 IntArray、以及 PNG 编码时的一份副本，
   * 合计约 4 倍。1200 万像素下单次峰值实测 150–240MB，而中端机 Java 堆上限只有
   * 256–512MB（PJA110 实测 512MB，且跑影子屏自动化时已用掉 490MB）。
   * 400 万像素把单次峰值压到约 64MB，与「图像处理」这种辅助能力的定位相称。
   */
  static final long MAX_INPUT_PIXELS = 4_000_000L;
  /** 输入文件字节上限（对齐 ImageFileSupport.MAX_IMAGE_FILE_BYTES）。 */
  static final long MAX_INPUT_BYTES = 20L * 1024L * 1024L;
  /** 内联回传上限（对齐 PhoneScreenshotTool.MAX_INLINE_BYTES）。 */
  static final long MAX_INLINE_BYTES = 4L * 1024L * 1024L;
  /** 输出 PNG 边长上限（防 resize 放大出巨图）。 */
  static final int MAX_OUTPUT_DIMENSION = 4096;
  /** dominant_colors 默认/最大返回色数。 */
  static final int DEFAULT_TOP_COLORS = 8;
  static final int MAX_TOP_COLORS = 16;
  /** 落盘目录（工作区相对路径）。 */
  private static final String OUTPUT_DIR = "ai-images";
  /**
   * 像素处理全局串行化。
   *
   * <p><b>并发度 1</b>：单次峰值约 64MB（见 {@link #MAX_INPUT_PIXELS}），两路并发就是
   * 128MB，再叠加会话历史里的图片 base64，512MB 堆很容易被顶穿。图像处理是低频操作，
   * 串行化的代价只是第二个请求多等一会儿，换来的是不会 OOM。
   */
  private static final Semaphore PIXEL_LOCK = new Semaphore(1);
  /** 等锁上限：超时报「图像处理繁忙」而不是无限等待。 */
  private static final long PIXEL_LOCK_WAIT_SECONDS = 30L;

  private final ImageProcessingPort processor;

  public ImageTool(ImageProcessingPort processor) {
    this.processor = processor;
  }

  @Override
  public String getName() {
    return ToolNames.IMAGE;
  }

  @Override
  public String getDescription() {
    return "图像处理（游戏素材制作）。action："
        + "chroma_key 纯色背景抠图（绿幕/蓝幕，输出透明 PNG）；"
        + "luma_key 亮度键抠图（黑/白背景去底）；"
        + "crop 裁剪；resize 缩放；"
        + "dominant_colors 提取主色（配色参考）。"
        + "path 为工作区相对路径，输出默认写到 ai-images/ 下（可用 output 指定）。"
        + "处理结果以图片回传并落盘，结果里给出保存路径。"
        + "仅支持 PNG/JPEG 输入，输出统一 PNG（保留透明通道）。";
  }

  @Override
  public ToolCategory getCategory() {
    // GENERATE：只读模式放行（ToolPermissionService 只放行 READ/GENERATE），
    // 与 image_generation 同类——产物写在工作区新文件里，不改动用户既有文件。
    return ToolCategory.GENERATE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    // 复用 GENERIC，不新增枚举值：ToolDisplayCategory 是穷尽 when 的输入，
    // 新增取值会阻断 app 编译。
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 工具本身无共享可变状态；像素处理段由 PIXEL_LOCK 串行化（见类内常量）。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("action", new JSONObject()
                    .put("type", "string")
                    .put("description", "操作类型")
                    .put("enum", new JSONArray()
                        .put(ACTION_CHROMA_KEY).put(ACTION_LUMA_KEY)
                        .put(ACTION_CROP).put(ACTION_RESIZE)
                        .put(ACTION_DOMINANT_COLORS)))
                .put("path", new JSONObject()
                    .put("type", "string")
                    .put("description", "输入图片路径（工作区相对，PNG/JPEG）"))
                .put("output", new JSONObject()
                    .put("type", "string")
                    .put("description", "输出路径（工作区相对，.png）。省略时写到 ai-images/ 下按时间戳命名。"
                        + "dominant_colors 忽略此参数"))
                // ---- chroma_key ----
                .put("key_color", new JSONObject()
                    .put("type", "string")
                    .put("description", "chroma_key 的背景色，#RRGGBB 或 auto（取四角均值）。默认 auto"))
                .put("threshold", new JSONObject()
                    .put("type", "integer")
                    .put("description", "chroma_key 色距阈值 0-255：距离 ≤ 此值全透明。默认 20"))
                .put("softness", new JSONObject()
                    .put("type", "integer")
                    .put("description", "chroma_key 边缘柔化宽度 0-255：阈值~阈值+softness 之间按比例渐隐。默认 32"))
                .put("despill_strength", new JSONObject()
                    .put("type", "number")
                    .put("description", "chroma_key 去溢色强度 0-1。默认 0.85"))
                .put("halo_pixels", new JSONObject()
                    .put("type", "integer")
                    .put("description", "alpha 收缩半径（px，0-8）：消除背景色光晕。默认 0"))
                // ---- luma_key ----
                .put("black_point", new JSONObject()
                    .put("type", "integer")
                    .put("description", "luma_key 亮度黑点 0-254：≤ 此亮度全透明。默认 8"))
                .put("white_point", new JSONObject()
                    .put("type", "integer")
                    .put("description", "luma_key 亮度白点 1-255：≥ 此亮度全不透明。默认 235"))
                .put("gamma", new JSONObject()
                    .put("type", "number")
                    .put("description", "luma_key 曲线 gamma（≥0.05）。<1 提亮过渡，>1 压暗。默认 1.0"))
                .put("strength", new JSONObject()
                    .put("type", "number")
                    .put("description", "luma_key 曲线强度 0-2。默认 1.0"))
                .put("key_suppression", new JSONObject()
                    .put("type", "number")
                    .put("description", "luma_key 背景色抑制强度 0-1（抑制亮色背景被误判为发光）。默认 0.95"))
                // ---- crop ----
                .put("rect", new JSONObject()
                    .put("type", "object")
                    .put("description", "crop 区域 {x, y, width, height}（像素，越界自动收敛到图像范围）"))
                // ---- resize ----
                .put("width", new JSONObject()
                    .put("type", "integer")
                    .put("description", "resize 目标宽（px）"))
                .put("height", new JSONObject()
                    .put("type", "integer")
                    .put("description", "resize 目标高（px）。width/height 只给一个时另一边按比例"))
                .put("max_dimension", new JSONObject()
                    .put("type", "integer")
                    .put("description", "resize 最长边上限（px）：按比例缩到不超过它。与 width/height 互斥，三者至少给一个"))
                .put("filter", new JSONObject()
                    .put("type", "string")
                    .put("description", "resize 采样：nearest（像素画）| bilinear（默认）| bicubic"))
                // ---- dominant_colors ----
                .put("top_colors", new JSONObject()
                    .put("type", "integer")
                    .put("description", "dominant_colors 返回色数 1-16。默认 8")))
        // required 必须是**顶层**（与 properties 平级）。曾误嵌进 properties 内部，
        // 生成非法 schema 被严格校验的服务商 400 拒绝整个请求（CodeActionsTool 事故）。
        .put("required", new JSONArray().put("action").put("path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (processor == null) {
      return error("当前环境未接入图像处理能力（AndroidImageProcessor 未注入）。");
    }
    String action = input.optString("action", "").trim().toLowerCase(Locale.US);
    // 0) action 合法性校验提到最前——非法 action 不必白读白解一张大图。
    if (!isValidAction(action)) {
      return error("action 只能是 chroma_key / luma_key / crop / resize / dominant_colors，收到：" + action);
    }
    String rawPath = input.optString("path", "").trim();
    if (rawPath.isEmpty()) {
      return error("path 不能为空。");
    }
    // 1) 路径解析（ArchiveTool 同款）
    File source;
    try {
      source = FileToolPathPolicy.resolve(context, rawPath);
    } catch (IOException e) {
      return error("路径无效：" + e.getMessage());
    }
    if (!source.exists() || !source.isFile()) {
      return error("输入文件不存在：" + display(context, source));
    }
    if (source.length() > MAX_INPUT_BYTES) {
      return error("输入图片过大（" + (source.length() / 1024 / 1024) + "MB），上限 20MB。");
    }
    // 2) 读字节 + 校验 magic bytes
    byte[] raw;
    try {
      raw = Files.readAllBytes(source.toPath());
    } catch (IOException e) {
      return error("读取失败：" + e.getMessage());
    }
    if (ImageFileSupport.detectMimeType(raw) == null) {
      return error("不是可识别的 PNG/JPEG（magic bytes 校验失败）：" + display(context, source));
    }
    // 3) 像素处理段：全局串行化（复审 M3——单次峰值可达 ~240MB，10 路并发即 GB 级）。
    boolean acquired;
    try {
      acquired = PIXEL_LOCK.tryAcquire(PIXEL_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error("图像处理被中断（用户可能点了停止）。");
    }
    if (!acquired) {
      return error("图像处理繁忙（并发任务较多），请稍后重试。");
    }
    try {
      // 4) 解码 + action 分派。
      // catch Throwable（不只 Exception）：像素处理段峰值可达 ~240MB，
      // OutOfMemoryError 是 Error 不是 Exception——ToolExecutor 与 AgentSession
      // 的兜底都只捕获 Exception/RuntimeException，漏出去的 OOM 会直接崩掉
      // agent 循环。工具约定「失败返回 error 变体」覆盖 Error。
      //
      // 解码必须包在同一段里：解码本身就是最大的内存分配点（整幅 RGBA 数组），
      // 此前它在 try 之外，OOM 会直接穿透整个工具层。
      try {
        ImageProcessingPort.Decoded decoded = processor.decode(raw, MAX_INPUT_PIXELS);
        if (decoded == null) {
          return error("图片解码失败（文件损坏、格式不受支持，或像素超过上限 "
              + MAX_INPUT_PIXELS + "——后者请先 resize 缩小）。");
        }
        java.util.function.BooleanSupplier cancelled = cancellationSignal(context);
        if (ACTION_DOMINANT_COLORS.equals(action)) {
          return dominantColors(decoded, input, context);
        }
        if (ACTION_CHROMA_KEY.equals(action)) {
          return runChromaKey(decoded, input, context, cancelled);
        }
        if (ACTION_LUMA_KEY.equals(action)) {
          return runLumaKey(decoded, input, context, cancelled);
        }
        if (ACTION_CROP.equals(action)) {
          return runCrop(decoded, input, context);
        }
        if (ACTION_RESIZE.equals(action)) {
          return runResize(decoded, input, context, cancelled);
        }
        return error("action 只能是 chroma_key / luma_key / crop / resize / dominant_colors，收到：" + action);
      } catch (OutOfMemoryError e) {
        // decoded 可能还没赋值（解码阶段就 OOM），不能直接引用它的字段。
        return error("内存不足：图片过大，请先 resize 到更小尺寸再处理。");
      } catch (Throwable e) {
        return error("图像处理失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    } finally {
      PIXEL_LOCK.release();
    }
  }

  // ---------------------------------------------------------------------
  // action 实现
  // ---------------------------------------------------------------------

  private ToolResult runChromaKey(ImageProcessingPort.Decoded d, JSONObject input,
      ToolContext context, java.util.function.BooleanSupplier cancelled) {
    int threshold = clampInt(input.optInt("threshold", 20), 0, 255);
    int softness = clampInt(input.optInt("softness", 32), 0, 255);
    float despill = (float) clamp(input.optDouble("despill_strength", 0.85), 0.0, 2.5);
    int halo = clampInt(input.optInt("halo_pixels", 0), 0, 8);
    String keySpec = input.optString("key_color", "auto").trim();

    int[] key = resolveKeyColor(keySpec, d);
    if (key == null) {
      return error("key_color 格式无效：应为 #RRGGBB 或 auto，收到：" + keySpec);
    }

    int[] out = ImageOps.chromaKey(d.pixels, d.width, d.height,
        key[0], key[1], key[2], threshold, softness, despill, halo, cancelled);
    if (out == null) {
      return error("已取消。");
    }
    long transparent = 0;
    for (int p : out) {
      if (((p >>> 24) & 0xFF) == 0) {
        transparent++;
      }
    }
    double ratio = out.length == 0 ? 0.0 : (transparent * 100.0 / out.length);
    StringBuilder note = new StringBuilder();
    note.append("抠图完成（chroma_key）。\n");
    note.append("输入: ").append(d.width).append('x').append(d.height)
        .append("  →  输出: ").append(d.width).append('x').append(d.height).append('\n');
    note.append("参数: key=").append(keySpec.equalsIgnoreCase("auto") ? String.format(Locale.US, "#%02X%02X%02X", key[0], key[1], key[2]) : keySpec)
        .append(" threshold=").append(threshold)
        .append(" softness=").append(softness)
        .append(" despill=").append(String.format(Locale.US, "%.2f", despill))
        .append(" halo=").append(halo).append('\n');
    note.append("透明像素: ").append(String.format(Locale.US, "%.1f", ratio)).append("%\n");
    return writeAndReturn(context, ACTION_CHROMA_KEY, input.optString("output", "").trim(),
        out, d.width, d.height, note);
  }

  private ToolResult runLumaKey(ImageProcessingPort.Decoded d, JSONObject input,
      ToolContext context, java.util.function.BooleanSupplier cancelled) {
    int black = clampInt(input.optInt("black_point", 8), 0, 254);
    int white = clampInt(input.optInt("white_point", 235), 1, 255);
    if (white <= black) {
      white = Math.min(255, black + 1);
    }
    float gamma = (float) input.optDouble("gamma", 1.0);
    float strength = (float) input.optDouble("strength", 1.0);
    float keySuppression = (float) clamp(input.optDouble("key_suppression", 0.95), 0.0, 1.0);
    String keySpec = input.optString("key_color", "auto").trim();

    int[] key = resolveKeyColor(keySpec, d);
    if (key == null) {
      return error("key_color 格式无效：应为 #RRGGBB 或 auto，收到：" + keySpec);
    }

    int[] out = ImageOps.lumaKey(d.pixels, d.width, d.height,
        black, white, gamma, strength, key[0], key[1], key[2], keySuppression, cancelled);
    if (out == null) {
      return error("已取消。");
    }
    long transparent = 0;
    for (int p : out) {
      if (((p >>> 24) & 0xFF) == 0) {
        transparent++;
      }
    }
    double ratio = out.length == 0 ? 0.0 : (transparent * 100.0 / out.length);
    StringBuilder note = new StringBuilder();
    note.append("抠图完成（luma_key）。\n");
    note.append("输入: ").append(d.width).append('x').append(d.height)
        .append("  →  输出: ").append(d.width).append('x').append(d.height).append('\n');
    note.append("参数: black=").append(black).append(" white=").append(white)
        .append(" gamma=").append(String.format(Locale.US, "%.2f", gamma))
        .append(" strength=").append(String.format(Locale.US, "%.2f", strength))
        .append(" key_suppression=").append(String.format(Locale.US, "%.2f", keySuppression)).append('\n');
    note.append("透明像素: ").append(String.format(Locale.US, "%.1f", ratio)).append("%\n");
    return writeAndReturn(context, ACTION_LUMA_KEY, input.optString("output", "").trim(),
        out, d.width, d.height, note);
  }

  private ToolResult runCrop(ImageProcessingPort.Decoded d, JSONObject input,
      ToolContext context) {
    JSONObject rect = input.optJSONObject("rect");
    if (rect == null) {
      return error("crop 需要 rect 参数：{x, y, width, height}。");
    }
    int x = rect.optInt("x", 0);
    int y = rect.optInt("y", 0);
    int w = rect.optInt("width", 0);
    int h = rect.optInt("height", 0);
    if (w <= 0 || h <= 0) {
      return error("rect 无效：width/height 必须为正（收到 " + w + "x" + h + "）。");
    }
    ImageOps.CropResult cropped = ImageOps.crop(d.pixels, d.width, d.height, x, y, w, h);
    if (cropped == null) {
      return error("rect 与图像无交集：x=" + x + " y=" + y + " w=" + w + " h=" + h
          + "，图像尺寸 " + d.width + "x" + d.height + "。");
    }
    StringBuilder note = new StringBuilder();
    note.append("裁剪完成（crop）。\n");
    note.append("原尺寸: ").append(d.width).append('x').append(d.height).append('\n');
    note.append("裁剪框: x=").append(cropped.left).append(" y=").append(cropped.top)
        .append(" w=").append(cropped.width).append(" h=").append(cropped.height).append('\n');
    note.append("输出: ").append(cropped.width).append('x').append(cropped.height).append('\n');
    return writeAndReturn(context, ACTION_CROP, input.optString("output", "").trim(),
        cropped.pixels, cropped.width, cropped.height, note);
  }

  private ToolResult runResize(ImageProcessingPort.Decoded d, JSONObject input,
      ToolContext context, java.util.function.BooleanSupplier cancelled) {
    int width = input.optInt("width", 0);
    int height = input.optInt("height", 0);
    int maxDimension = input.optInt("max_dimension", 0);
    // 组合规则（复审 n7）：max_dimension 与 width/height 互斥；三者至少一个。
    if (maxDimension > 0 && (width > 0 || height > 0)) {
      return error("max_dimension 与 width/height 互斥，只给一组。");
    }
    if (maxDimension <= 0 && width <= 0 && height <= 0) {
      return error("resize 需要 width/height/max_dimension 至少一个。");
    }
    int targetW;
    int targetH;
    if (maxDimension > 0) {
      int longest = Math.max(d.width, d.height);
      if (maxDimension >= longest) {
        targetW = d.width;
        targetH = d.height;
      } else {
        targetW = Math.max(1, (int) ((long) d.width * maxDimension / longest));
        targetH = Math.max(1, (int) ((long) d.height * maxDimension / longest));
      }
    } else if (width > 0 && height > 0) {
      targetW = width;
      targetH = height;
    } else if (width > 0) {
      targetW = width;
      targetH = Math.max(1, (int) ((long) d.height * width / d.width));
    } else {
      targetH = height;
      targetW = Math.max(1, (int) ((long) d.width * height / d.height));
    }
    if (targetW > MAX_OUTPUT_DIMENSION || targetH > MAX_OUTPUT_DIMENSION) {
      return error("目标尺寸 " + targetW + "x" + targetH + " 超过上限 "
          + MAX_OUTPUT_DIMENSION + "，请缩小目标。");
    }
    String filter = input.optString("filter", "bilinear").trim().toLowerCase(Locale.US);
    if (!"nearest".equals(filter) && !"bilinear".equals(filter) && !"bicubic".equals(filter)) {
      return error("filter 只能是 nearest / bilinear / bicubic，收到：" + filter);
    }
    if (cancelled != null && cancelled.getAsBoolean()) {
      return error("已取消。");
    }

    int[] outPixels;
    if ("nearest".equals(filter)) {
      outPixels = ImageOps.scaleNearest(d.pixels, d.width, d.height, targetW, targetH);
    } else {
      ImageProcessingPort.Decoded scaled =
          processor.scale(d.pixels, d.width, d.height, targetW, targetH, filter);
      if (scaled == null) {
        return error("缩放失败（可能内存不足）。");
      }
      outPixels = scaled.pixels;
      targetW = scaled.width;
      targetH = scaled.height;
    }
    StringBuilder note = new StringBuilder();
    note.append("缩放完成（resize）。\n");
    note.append("原尺寸: ").append(d.width).append('x').append(d.height).append('\n');
    note.append("目标尺寸: ").append(targetW).append('x').append(targetH)
        .append(" filter=").append(filter);
    if ("bicubic".equals(filter)) {
      note.append("（Android 无独立 bicubic 开关，按双线性处理）");
    }
    note.append('\n');
    return writeAndReturn(context, ACTION_RESIZE, input.optString("output", "").trim(),
        outPixels, targetW, targetH, note);
  }

  private ToolResult dominantColors(ImageProcessingPort.Decoded d, JSONObject input,
      ToolContext context) {
    int topN = clampInt(input.optInt("top_colors", DEFAULT_TOP_COLORS), 1, MAX_TOP_COLORS);
    ImageOps.DominantResult result =
        ImageOps.dominantColors(d.pixels, topN, cancellationSignal(context));
    if (result == null) {
      return error("已取消。");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("主色（共 ").append(result.totalPixels).append(" 个不透明像素，显示 top ")
        .append(topN).append("）:\n");
    for (int i = 0; i < result.filled; i++) {
      int[] row = result.rows[i];
      double pct = result.totalPixels == 0 ? 0.0 : (row[3] * 100.0 / result.totalPixels);
      sb.append(i + 1).append(". ")
          .append(String.format(Locale.US, "#%02X%02X%02X", row[0], row[1], row[2]))
          .append("  ").append(String.format(Locale.US, "%.1f", pct)).append("%  ")
          .append(row[3]).append(" px\n");
    }
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  // ---------------------------------------------------------------------
  // 输出落盘与回传（chroma/luma/crop/resize 共用）
  // ---------------------------------------------------------------------

  private ToolResult writeAndReturn(
      ToolContext context, String action, String rawOutput,
      int[] outPixels, int width, int height, StringBuilder note) {
    // 1) 落盘用**全尺寸**编码（预算 Long.MAX_VALUE = 不收缩）。
    //    ⚠️ 产物文件必须是全尺寸——不能把「回传预览的降采样」写进磁盘。
    ImageProcessingPort.Encoded full = processor.encodePng(outPixels, width, height, Long.MAX_VALUE);
    if (full == null) {
      return error("PNG 编码失败（可能内存不足）。");
    }
    File out;
    try {
      out = resolveOutput(context, action, rawOutput);
    } catch (IOException e) {
      return error("输出路径无效：" + e.getMessage());
    }
    // CREATE_NEW：原子防覆盖（复审 n8——exists() 检查与写入之间有 TOCTOU 窗口）。
    try (OutputStream fos = Files.newOutputStream(out.toPath(), StandardOpenOption.CREATE_NEW)) {
      fos.write(full.bytes);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      return error("输出文件已存在（不覆盖既有文件）：" + display(context, out));
    } catch (IOException e) {
      return error("写入输出文件失败：" + e.getMessage());
    }
    // 2) 文本 + 内联判定。
    note.append("文件路径:\n").append(out.getAbsolutePath()).append('\n');
    if (full.bytes.length <= MAX_INLINE_BYTES) {
      return ToolResult.withImage(getName(), note.toString(), "image/png",
          java.util.Base64.getEncoder().encodeToString(full.bytes));
    }
    // 3) 超过内联上限：另做一份收缩编码只用于回传预览，落盘文件保持全尺寸。
    ImageProcessingPort.Encoded preview = processor.encodePng(outPixels, width, height, MAX_INLINE_BYTES);
    if (preview != null && preview.bytes.length <= MAX_INLINE_BYTES) {
      note.append("（原图 ").append(full.bytes.length / 1024 / 1024)
          .append("MB 超过内联上限，下方为降采样预览；文件路径是完整产物）");
      return ToolResult.withImage(getName(), note.toString(), "image/png",
          java.util.Base64.getEncoder().encodeToString(preview.bytes));
    }
    note.append("（图片超过内联上限 ").append(MAX_INLINE_BYTES / 1024 / 1024)
        .append("MB，未回传给模型，请用文件路径处理）");
    return ok(ToolResult.truncateContent(note.toString()));
  }

  /**
   * 解析输出路径；省略时生成 ai-images/{action}-{timestamp}.png。
   *
   * <p>已存在时抛 IOException（快速失败的好错误信息）；真正的防覆盖由写入侧的
   * CREATE_NEW 原子保证（见 {@link #writeAndReturn}）。
   */
  private File resolveOutput(ToolContext context, String action, String rawOutput)
      throws IOException {
    File target;
    if (rawOutput == null || rawOutput.trim().isEmpty()) {
      File base = new File(context.getHomePath(), OUTPUT_DIR);
      // 用 isDirectory 判定：路径存在但是**文件**时 mkdirs 不会执行，
      // 后续写入只会得到「Not a directory」，与真实原因无关。
      if (!base.isDirectory() && !base.mkdirs() && !base.isDirectory()) {
        throw new IOException("无法创建输出目录: " + base.getAbsolutePath());
      }
      String name = action.replace('_', '-') + "-" + System.currentTimeMillis() + ".png";
      target = new File(base, name);
    } else {
      target = FileToolPathPolicy.resolve(context, rawOutput);
      if (!target.getName().toLowerCase(Locale.US).endsWith(".png")) {
        throw new IOException("输出文件必须以 .png 结尾（透明通道需要 PNG）：" + rawOutput);
      }
      if (target.exists()) {
        throw new IOException("输出文件已存在（不覆盖既有文件）：" + rawOutput);
      }
      if (target.getParentFile() != null) {
        target.getParentFile().mkdirs();
      }
    }
    return target;
  }

  // ---------------------------------------------------------------------
  // 辅助
  // ---------------------------------------------------------------------

  static boolean isValidAction(String action) {
    return ACTION_CHROMA_KEY.equals(action)
        || ACTION_LUMA_KEY.equals(action)
        || ACTION_CROP.equals(action)
        || ACTION_RESIZE.equals(action)
        || ACTION_DOMINANT_COLORS.equals(action);
  }

  /** 解析 key_color：auto → 四角均值；#RRGGBB → 解析；非法返回 null。 */
  static int[] resolveKeyColor(String spec, ImageProcessingPort.Decoded d) {
    if (spec == null || spec.isEmpty() || "auto".equalsIgnoreCase(spec)) {
      return ImageOps.autoKeyColor(d.pixels, d.width, d.height);
    }
    if (!spec.matches("^#[0-9a-fA-F]{6}$")) {
      return null;
    }
    return new int[] {
        Integer.parseInt(spec.substring(1, 3), 16),
        Integer.parseInt(spec.substring(3, 5), 16),
        Integer.parseInt(spec.substring(5, 7), 16),
    };
  }

  private static java.util.function.BooleanSupplier cancellationSignal(ToolContext context) {
    ToolContext.CancellationSignal signal = context == null ? null : context.getCancellation();
    return signal == null ? null : signal::isCancelled;
  }

  private static int clampInt(int v, int lo, int hi) {
    return Math.min(hi, Math.max(lo, v));
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.min(hi, Math.max(lo, v));
  }

  private static String display(ToolContext context, File file) {
    try {
      return FileToolPathPolicy.displayPath(context.getHomePath(), file);
    } catch (IOException e) {
      return file.getAbsolutePath();
    }
  }
}
