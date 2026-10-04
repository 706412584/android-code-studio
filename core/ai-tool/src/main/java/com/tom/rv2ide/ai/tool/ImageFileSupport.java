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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Base64;
import java.util.Locale;

/**
 * 图片文件的识别与编码，供 {@link FileReadTool} 把图片读成模型可理解的负载。
 *
 * <p><b>参考 cc-haha 的 {@code FileReadTool}</b>：它支持 png/jpg/jpeg/gif/webp，
 * 读取前先校验文件内容确为图片（magic bytes），再按 vision token 预算缩放后以 base64
 * 放进 {@code tool_result} 的内容块。本类取其关键语义，但做两处裁剪：
 * <ul>
 *   <li><b>只认 PNG 与 JPEG</b>。GIF 只取首帧、WebP 的兼容面因模型而异，容易「读到了但
 *       模型看不了」，不如明确报错让模型改用别的手段。
 *   <li><b>校验的是 magic bytes 而不是扩展名</b>。把 HTTP 错误页存成 {@code .png} 是常见
 *       陷阱，按扩展名放行会把一段文本当作图片发给模型。
 * </ul>
 *
 * <p>本类不引用任何 Android 类型：缩放通过 {@link com.tom.rv2ide.ai.tool.api.ImageDataProvider}
 * 端口由 app 层注入，未注入时退化为原图（可能过大，由大小上限兜底）。
 */
final class ImageFileSupport {

  /** 支持的最大原始文件字节数。超过时直接拒绝，避免把几十 MB 的图读进内存。 */
  static final long MAX_IMAGE_FILE_BYTES = 20L * 1024L * 1024L;

  /** 交给模型前缩放的默认最长边（像素）。与主流 vision 输入的经验值一致。 */
  static final int DEFAULT_MAX_DIMENSION_PX = 1568;

  private ImageFileSupport() {}

  /**
   * 判断文件是否为受支持的图片（按扩展名快速筛选，内容仍需 {@link #detectMimeType} 校验）。
   */
  static boolean hasImageExtension(File file) {
    String name = file.getName().toLowerCase(Locale.ROOT);
    return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
  }

  /**
   * 判断文件扩展名是否「看起来是图片」但本工具不支持。
   *
   * <p>用于给出一句明确的「格式不支持」，而不是把二进制当文本读成乱码回灌给模型。
   */
  static boolean hasUnsupportedImageExtension(File file) {
    String name = file.getName().toLowerCase(Locale.ROOT);
    return name.endsWith(".gif")
        || name.endsWith(".webp")
        || name.endsWith(".bmp")
        || name.endsWith(".heic")
        || name.endsWith(".heif")
        || name.endsWith(".avif")
        || name.endsWith(".tif")
        || name.endsWith(".tiff");
  }

  /**
   * 按 magic bytes 判定图片 MIME 类型。
   *
   * @param head 文件起始的若干字节（至少 12 字节可覆盖本方法支持的全部格式）
   * @return {@code image/png} / {@code image/jpeg}；无法识别时返回 {@code null}
   */
  static String detectMimeType(byte[] head) {
    if (head == null) {
      return null;
    }
    // PNG: 89 50 4E 47 0D 0A 1A 0A
    if (head.length >= 8
        && (head[0] & 0xFF) == 0x89
        && head[1] == 'P'
        && head[2] == 'N'
        && head[3] == 'G'
        && (head[4] & 0xFF) == 0x0D
        && (head[5] & 0xFF) == 0x0A
        && (head[6] & 0xFF) == 0x1A
        && (head[7] & 0xFF) == 0x0A) {
      return "image/png";
    }
    // JPEG: FF D8 FF
    if (head.length >= 3
        && (head[0] & 0xFF) == 0xFF
        && (head[1] & 0xFF) == 0xD8
        && (head[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    return null;
  }

  /**
   * 读取图片文件并产出可直接交给模型的负载。
   *
   * @param file 图片文件
   * @param provider 缩放实现；可为 null（不缩放）
   * @return 读取结果；失败时 {@link Result#error} 非空
   */
  static Result read(File file, com.tom.rv2ide.ai.tool.api.ImageDataProvider provider) {
    long size = file.length();
    if (size <= 0) {
      return Result.failure("图片文件为空");
    }
    if (size > MAX_IMAGE_FILE_BYTES) {
      return Result.failure(
          "图片文件过大（" + (size / 1024 / 1024) + "MB），上限 " + (MAX_IMAGE_FILE_BYTES / 1024 / 1024) + "MB");
    }

    byte[] raw;
    try {
      raw = readAll(file, size);
    } catch (Exception e) {
      return Result.failure("读取图片失败: " + e.getMessage());
    }

    String mimeType = detectMimeType(raw);
    if (mimeType == null) {
      return Result.failure(
          "文件扩展名是图片，但内容不是可识别的 PNG/JPEG（可能被写入了非图片内容）");
    }

    byte[] encoded = raw;
    boolean downscaled = false;
    if (provider != null) {
      byte[] scaled = provider.downscaleToMaxDimension(raw, DEFAULT_MAX_DIMENSION_PX);
      if (scaled != null && scaled.length > 0 && scaled.length < raw.length) {
        encoded = scaled;
        downscaled = true;
        // 缩放实现可能改变编码格式（例如 PNG 转 JPEG），以实际字节为准重新判定。
        String scaledMime = detectMimeType(scaled);
        if (scaledMime != null) {
          mimeType = scaledMime;
        }
      }
    }

    return Result.success(mimeType, Base64.getEncoder().encodeToString(encoded), raw.length, downscaled);
  }

  private static byte[] readAll(File file, long size) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(size, 8L * 1024L * 1024L));
    byte[] buffer = new byte[64 * 1024];
    try (FileInputStream in = new FileInputStream(file)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    }
    return out.toByteArray();
  }

  /** 图片读取结果。 */
  static final class Result {
    final String error;
    final String mimeType;
    final String base64;
    final long originalSize;
    final boolean downscaled;

    private Result(
        String error, String mimeType, String base64, long originalSize, boolean downscaled) {
      this.error = error;
      this.mimeType = mimeType;
      this.base64 = base64;
      this.originalSize = originalSize;
      this.downscaled = downscaled;
    }

    static Result success(String mimeType, String base64, long originalSize, boolean downscaled) {
      return new Result("", mimeType, base64, originalSize, downscaled);
    }

    static Result failure(String error) {
      return new Result(error, "", "", 0L, false);
    }

    boolean isError() {
      return error != null && error.length() > 0;
    }
  }
}
