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

package com.tom.rv2ide.artificial.agent.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对设备截屏，并把图片作为<b>图片负载</b>回灌给模型。
 *
 * <p>「运行 app 测试」闭环原本止于日志：agent 能改代码、构建、安装、启动、读日志，
 * 却<b>看不见界面</b>，无法判断「按钮有没有显示」「布局有没有错位」「弹窗有没有弹出」。
 * 本工具补上这一环。
 *
 * <p><b>为什么不需要无障碍服务</b>：截屏走 {@code screencap}（adb 级 shell，uid 2000），
 * 由 Shizuku 后端执行，与 {@code adb shell screencap} 完全等价。
 *
 * <p><b>为什么要压缩/缩放/区域</b>：一张 1080×2400 的 PNG 动辄 2~4MB，base64 后更大，
 * 直接发给模型会触发「图片过大」错误或挤爆上下文。因此默认把最长边压到 1568px
 * （与 {@code file_read} 读图的默认值一致），并在编码后仍超限时继续收缩。
 *
 * <p><b>图片通路</b>：截图文件经 shell 写入 {@code getExternalFilesDir}（app 与 shell
 * 都能访问），再由本进程读回、裁剪/缩放/编码，最后用
 * {@link ToolResult#withImage} 承载 base64。这条通路复用了 {@code file_read} 已验证的
 * 图片负载机制，协议层会自动编码成各家的 image block。
 */
public final class PhoneScreenshotTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(PhoneScreenshotTool.class);

  /** 默认最长边上限（像素），与 {@code file_read} 读图一致。 */
  private static final int DEFAULT_MAX_DIMENSION_PX = 1568;

  /** 上限的硬边界，避免模型传入过大的值。 */
  private static final int MAX_DIMENSION_LIMIT_PX = 4096;

  /** 默认 JPEG 质量。 */
  private static final int DEFAULT_JPEG_QUALITY = 85;

  /** 内联图片负载的字节上限（base64 前的原始字节）。超过时继续收缩，仍超则只返回文件。 */
  private static final long MAX_INLINE_BYTES = 4L * 1024L * 1024L;

  /** 截图命令超时。 */
  private static final long SCREENSHOT_TIMEOUT_MS = 20_000L;

  /** 屏幕尺寸查询命令超时（很小，给足裕量）。 */
  private static final long SIZE_QUERY_TIMEOUT_MS = 8_000L;

  private final Context appContext;
  private final ShellBackendRegistry shellBackends;

  public PhoneScreenshotTool(Context context, ShellBackendRegistry shellBackends) {
    this.appContext = context.getApplicationContext();
    this.shellBackends = shellBackends;
  }

  @Override
  public String getName() {
    return "phone_screenshot";
  }

  @Override
  public String getDescription() {
    return "对设备屏幕截屏，并把图片直接返回给模型（可见界面，用于验证布局、弹窗、按钮状态）。"
        + "支持压缩缩放（maxDimension/jpegQuality，默认最长边 1568px）与区域截取（region）。"
        + "需要 adb 级权限（Shizuku 后端）；无权限时会明确报错。"
        + "截图会保存到应用外部文件目录，结果里给出路径。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    // 只读取设备屏幕状态，不改变设备。允许在只读模式使用。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "maxDimension",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "缩放后最长边像素上限，默认 "
                                + DEFAULT_MAX_DIMENSION_PX
                                + "，上限 "
                                + MAX_DIMENSION_LIMIT_PX
                                + "；传 0 表示不缩放"))
                .put(
                    "jpegQuality",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "JPEG 质量 1-100，默认 " + DEFAULT_JPEG_QUALITY + "（仅 format=jpeg 时生效）"))
                .put(
                    "format",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "输出格式，默认 png（无损）；jpeg 体积更小")
                        .put("enum", new org.json.JSONArray().put("png").put("jpeg")))
                .put(
                    "region",
                    new JSONObject()
                        .put("type", "object")
                        .put("description", "只截取屏幕的某个矩形区域（像素坐标，原点为屏幕左上角）")
                        .put(
                            "properties",
                            new JSONObject()
                                .put("x", new JSONObject().put("type", "number").put("description", "左边界 x"))
                                .put("y", new JSONObject().put("type", "number").put("description", "上边界 y"))
                                .put("width", new JSONObject().put("type", "number").put("description", "宽度"))
                                .put("height", new JSONObject().put("type", "number").put("description", "高度")))
                        .put(
                            "required",
                            new org.json.JSONArray().put("x").put("y").put("width").put("height")))
                .put(
                    "inline",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否把图片作为负载返回给模型，默认 true；false 时只保存文件并返回路径")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    int maxDimension = (int) input.optDouble("maxDimension", DEFAULT_MAX_DIMENSION_PX);
    if (maxDimension <= 0) {
      maxDimension = 0; // 显式 0 = 不缩放
    } else {
      maxDimension = Math.min(maxDimension, MAX_DIMENSION_LIMIT_PX);
    }

    int jpegQuality = (int) input.optDouble("jpegQuality", DEFAULT_JPEG_QUALITY);
    jpegQuality = Math.max(1, Math.min(100, jpegQuality));

    boolean useJpeg = "jpeg".equalsIgnoreCase(input.optString("format", "png").trim());
    boolean inline = input.optBoolean("inline", true);

    if (context != null) {
      context.reportProgress("设备截屏");
    }

    // 1) 通过 Shizuku 把截图写到 app 外部目录（shell 与 app 都能访问）。
    File target;
    try {
      // 先一律存 PNG（screencap -p 的原生输出），后续按 format 转码为最终文件。
      target = newScreenFile(".png");
    } catch (IOException e) {
      return error("无法创建截图目录: " + e.getMessage());
    }
    if (target.exists()) {
      // 理论上不会重名（带时间戳），保险起见清掉旧的。
      target.delete();
    }

    PhoneUiSelector.Exec capture =
        PhoneUiSelector.exec(
            shellBackends, "screencap -p " + quote(target.getAbsolutePath()), SCREENSHOT_TIMEOUT_MS);
    if (!capture.ok) {
      return error("截图失败。\n" + capture.error);
    }
    if (!target.exists() || target.length() == 0) {
      return error(
          "截图命令已执行，但没有生成有效文件（"
              + target.getAbsolutePath()
              + "）。设备可能拒绝截屏（如设置了 FLAG_SECURE 的安全界面）。");
    }

    // 2) 解码（必要时兼容 OEM 的 raw 输出）。
    Bitmap bitmap;
    try {
      bitmap = decode(target);
    } catch (IOException e) {
      target.delete();
      return error("截图解码失败: " + e.getMessage());
    }
    if (bitmap == null) {
      target.delete();
      return error("截图解码失败：文件不是可识别的 PNG/RAW 位图。");
    }

    StringBuilder note = new StringBuilder();
    try {
      int fullWidth = bitmap.getWidth();
      int fullHeight = bitmap.getHeight();

      // 3) 区域截取。
      Region region = parseRegion(input);
      if (region != null) {
        Bitmap cropped = crop(bitmap, region, note);
        if (cropped == null) {
          return error(
              "region 无效："
                  + region.describe()
                  + "，屏幕尺寸为 "
                  + fullWidth
                  + "x"
                  + fullHeight);
        }
        if (cropped != bitmap) {
          bitmap.recycle();
        }
        bitmap = cropped;
      }

      // 4) 缩放。
      if (maxDimension > 0) {
        Bitmap scaled = downscale(bitmap, maxDimension);
        if (scaled != bitmap) {
          bitmap.recycle();
          bitmap = scaled;
        }
      }

      // 5) 编码，并在超过内联上限时继续收缩（防大图导致模型报错）。
      Encoded encoded = encodeWithinBudget(bitmap, useJpeg, jpegQuality, note);
      if (encoded == null) {
        return error("截图编码失败。");
      }

      // 6) 落盘为最终产物（覆盖最初的 PNG 临时文件）。
      File outFile = writeOutput(target, encoded.bytes, encoded.mimeType);

      note.insert(0, "截图成功。\n");
      note.append("屏幕尺寸: ").append(fullWidth).append('x').append(fullHeight).append('\n');
      note.append("输出尺寸: ").append(bitmap.getWidth()).append('x').append(bitmap.getHeight()).append('\n');
      note.append("格式: ").append(encoded.mimeType).append('\n');
      note.append("大小: ").append(encoded.bytes.length / 1024).append(" KB\n");
      // 路径单独成行且不加前缀：这是给下游工具（如 phone_screenshot_compare /
      // phone_action_capture 的 PhoneScreenshotSource）的稳定契约——它们取「该行第一个
      // 空白分隔的 token」作为文件路径。加前缀会破坏该解析。
      note.append("文件路径:\n").append(outFile.getAbsolutePath()).append('\n');

      if (!inline) {
        note.append("（inline=false，未把图片返回给模型，可用文件路径自行处理）");
        return ok(note.toString());
      }

      String base64 = Base64.getEncoder().encodeToString(encoded.bytes);
      return ToolResult.withImage(getName(), note.toString(), encoded.mimeType, base64);
    } finally {
      if (bitmap != null && !bitmap.isRecycled()) {
        bitmap.recycle();
      }
    }
  }

  // =====================================================================================
  // 解码 / 裁剪 / 缩放 / 编码
  // =====================================================================================

  private Bitmap decode(File file) throws IOException {
    // 先读文件头判断是否为 PNG。
    byte[] head = new byte[8];
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      int read = raf.read(head);
      if (read < 8) {
        throw new IOException("文件过小");
      }
    }
    boolean isPng =
        (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
    if (isPng) {
      Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath());
      if (decoded == null) {
        throw new IOException("PNG 解码返回 null");
      }
      return decoded;
    }
    // 非 PNG：某些 OEM ROM 的 `screencap -p` 实际输出 raw 位图，这里做兼容。
    return decodeRawScreencap(file);
  }

  /**
   * 兼容部分机型 {@code screencap -p} 输出 raw 位图的情况。
   *
   * <p>raw 布局：16 字节头（width / height / format / colorspace，均为小端 u32）+ 像素，
   * 像素按 {@code RGBA_8888}（每像素 R,G,B,A 四字节）排列。此处按文件长度推断行距，
   * 逐行取像素，避免行对齐差异导致花屏。
   *
   * @return 解码出的位图；无法识别时返回 null
   */
  private Bitmap decodeRawScreencap(File file) throws IOException {
    long length = file.length();
    if (length < 16) {
      return null;
    }
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      byte[] header = new byte[16];
      raf.readFully(header);
      ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
      int width = buffer.getInt();
      int height = buffer.getInt();
      int format = buffer.getInt();
      if (width <= 0 || height <= 0 || width > 20000 || height > 20000) {
        return null;
      }
      long pixels = (long) width * height * 4;
      if (16 + pixels > length + (long) height * 4) {
        // 长度明显不足，不是 raw 位图
        return null;
      }
      int stride = (int) ((length - 16) / height);
      if (stride < width * 4) {
        return null;
      }

      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      int[] row = new int[width];
      byte[] rowBytes = new byte[stride];
      for (int y = 0; y < height; y++) {
        raf.seek(16L + (long) y * stride);
        raf.readFully(rowBytes);
        for (int x = 0; x < width; x++) {
          int offset = x * 4;
          int r = rowBytes[offset] & 0xFF;
          int g = rowBytes[offset + 1] & 0xFF;
          int b = rowBytes[offset + 2] & 0xFF;
          int a = rowBytes[offset + 3] & 0xFF;
          row[x] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        bitmap.setPixels(row, 0, width, 0, y, width, 1);
      }
      log.info("按 raw 位图解析截图（format={} {}x{}）", format, width, height);
      return bitmap;
    } catch (RuntimeException | IOException e) {
      log.debug("raw 截图解析失败", e);
      return null;
    }
  }

  /** 区域参数。 */
  private static final class Region {
    final int x;
    final int y;
    final int width;
    final int height;

    Region(int x, int y, int width, int height) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }

    String describe() {
      return "x=" + x + " y=" + y + " w=" + width + " h=" + height;
    }
  }

  private static Region parseRegion(JSONObject input) {
    if (!input.has("region")) {
      return null;
    }
    JSONObject region = input.optJSONObject("region");
    if (region == null) {
      return null;
    }
    int x = region.optInt("x", 0);
    int y = region.optInt("y", 0);
    int width = region.optInt("width", 0);
    int height = region.optInt("height", 0);
    if (width <= 0 || height <= 0) {
      return null;
    }
    return new Region(Math.max(0, x), Math.max(0, y), width, height);
  }

  /** 按区域裁剪；越界时收敛到屏幕范围。无法裁剪时返回 null。 */
  private Bitmap crop(Bitmap source, Region region, StringBuilder note) {
    int left = Math.min(region.x, source.getWidth());
    int top = Math.min(region.y, source.getHeight());
    int right = Math.min(region.x + region.width, source.getWidth());
    int bottom = Math.min(region.y + region.height, source.getHeight());
    int width = right - left;
    int height = bottom - top;
    if (width <= 0 || height <= 0) {
      return null;
    }
    if (left != region.x || top != region.y || width != region.width || height != region.height) {
      note.append("region 已收敛到屏幕范围: x=")
          .append(left)
          .append(" y=")
          .append(top)
          .append(" w=")
          .append(width)
          .append(" h=")
          .append(height)
          .append('\n');
    }
    return Bitmap.createBitmap(source, left, top, width, height);
  }

  /** 按最长边缩放到 {@code maxDimension} 以内；已在范围内时原样返回。 */
  private static Bitmap downscale(Bitmap source, int maxDimension) {
    int longest = Math.max(source.getWidth(), source.getHeight());
    if (longest <= maxDimension) {
      return source;
    }
    float ratio = (float) maxDimension / longest;
    int targetWidth = Math.max(1, Math.round(source.getWidth() * ratio));
    int targetHeight = Math.max(1, Math.round(source.getHeight() * ratio));
    return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
  }

  /** 编码结果。 */
  private static final class Encoded {
    final byte[] bytes;
    final String mimeType;

    Encoded(byte[] bytes, String mimeType) {
      this.bytes = bytes;
      this.mimeType = mimeType;
    }
  }

  /**
   * 编码为 PNG/JPEG；若结果超过内联上限，则进一步缩小最长边重试（最多几轮）。
   *
   * <p>这是「防大图导致模型报错」的兜底：即使调用方不传 maxDimension，
   * 也不会把一张几 MB 的图直接塞给模型。
   */
  private Encoded encodeWithinBudget(
      Bitmap source, boolean useJpeg, int jpegQuality, StringBuilder note) {
    Bitmap current = source;
    boolean shrunk = false;
    try {
      for (int attempt = 0; attempt < 5; attempt++) {
        byte[] bytes = encode(current, useJpeg, jpegQuality);
        if (bytes == null) {
          return null;
        }
        if (bytes.length <= MAX_INLINE_BYTES) {
          if (shrunk) {
            note.append("为控制体积已自动缩小到 ")
                .append(current.getWidth())
                .append('x')
                .append(current.getHeight())
                .append('\n');
          }
          return new Encoded(bytes, useJpeg ? "image/jpeg" : "image/png");
        }
        int longest = Math.max(current.getWidth(), current.getHeight());
        if (longest <= 320) {
          // 已缩到很小仍超限：不再强求内联，交由调用方处理。
          note.append("编码后仍超过内联上限（").append(bytes.length / 1024).append(" KB），已放弃内联。\n");
          return new Encoded(bytes, useJpeg ? "image/jpeg" : "image/png");
        }
        int next = Math.max(320, (int) (longest * 0.75f));
        Bitmap scaled =
            Bitmap.createScaledBitmap(
                current,
                Math.max(1, current.getWidth() * next / longest),
                Math.max(1, current.getHeight() * next / longest),
                true);
        if (current != source) {
          current.recycle();
        }
        current = scaled;
        shrunk = true;
      }
      return null;
    } finally {
      // 只回收本方法自建的中间位图；source 的生命周期由调用方（execute 的 finally）负责。
      if (current != source && !current.isRecycled()) {
        current.recycle();
      }
    }
  }

  private static byte[] encode(Bitmap bitmap, boolean useJpeg, int jpegQuality) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Bitmap.CompressFormat format = useJpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
    int quality = useJpeg ? jpegQuality : 100;
    if (!bitmap.compress(format, quality, out)) {
      return null;
    }
    return out.toByteArray();
  }

  // =====================================================================================
  // 文件路径
  // =====================================================================================

  private File screenshotDir() throws IOException {
    File base = appContext.getExternalFilesDir(null);
    if (base == null) {
      // 外部存储不可用时退回内部目录（shell 可能读不到，但至少能落盘）。
      base = appContext.getFilesDir();
    }
    File dir = new File(base, "phone");
    if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
      throw new IOException("无法创建目录: " + dir.getAbsolutePath());
    }
    return dir;
  }

  private File newScreenFile(String suffix) throws IOException {
    File dir = screenshotDir();
    return new File(dir, "screen_" + System.currentTimeMillis() + suffix);
  }

  /** 把最终字节写到目标路径（PNG 或 JPEG），返回写好的文件。 */
  private File writeOutput(File initialFile, byte[] bytes, String mimeType) {
    boolean jpeg = "image/jpeg".equals(mimeType);
    String desired = initialFile.getAbsolutePath();
    if (jpeg && desired.endsWith(".png")) {
      desired = desired.substring(0, desired.length() - 4) + ".jpg";
    } else if (!jpeg && desired.endsWith(".jpg")) {
      desired = desired.substring(0, desired.length() - 4) + ".png";
    }
    File out = new File(desired);
    if (!out.equals(initialFile) && initialFile.exists()) {
      initialFile.delete();
    }
    try (FileOutputStream fos = new FileOutputStream(out, false)) {
      fos.write(bytes);
      fos.flush();
    } catch (IOException e) {
      log.warn("写入截图文件失败: {}", out.getAbsolutePath(), e);
    }
    return out;
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
