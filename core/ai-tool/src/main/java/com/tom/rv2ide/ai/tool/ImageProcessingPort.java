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

/**
 * 图像处理管线端口：解码 / 编码的 Android 实现由 app 层注入。
 *
 * <p><b>为什么是端口</b>：工具模块（{@code core/ai-tool}）是纯 Java、零 Android 依赖的
 * {@code java-library}（{@code options.release = 8}），不能在工具里引用
 * {@code android.graphics.Bitmap}。像素运算本身用 {@code int[]}（ARGB_8888 打包格式，
 * 与 {@code Bitmap.getPixels} 一致）在工具层实现、可 JVM 单测；只有「字节 ↔ 位图 ↔ 像素数组」
 * 的搬运委托给本端口。
 *
 * <p>实现方无状态、可并发调用（agent 一批可并行调多个 image_tool）。
 */
public interface ImageProcessingPort {

  /** 解码结果：ARGB_8888 打包像素（{@code a<<24 | r<<16 | g<<8 | b}）与尺寸。 */
  final class Decoded {
    public final int[] pixels;
    public final int width;
    public final int height;

    public Decoded(int[] pixels, int width, int height) {
      this.pixels = pixels;
      this.width = width;
      this.height = height;
    }
  }

  /** 编码结果：PNG 字节与实际尺寸（可能因内联预算被收缩）。 */
  final class Encoded {
    public final byte[] bytes;
    public final int width;
    public final int height;

    public Encoded(byte[] bytes, int width, int height) {
      this.bytes = bytes;
      this.width = width;
      this.height = height;
    }
  }

  /**
   * 解码图片字节为 ARGB_8888 像素数组。
   *
   * <p>实现语义：
   * <ul>
   *   <li>解码失败（损坏、格式不支持、OOM）返回 {@code null}
   *   <li>含 alpha 的源图保留 alpha；JPEG 等无 alpha 的源 alpha 恒为 255
   *   <li>返回的像素必须是 ARGB_8888 打包格式（{@code getPixels} 的原生格式）
   *   <li>解码前先做两段式 bounds 预查（{@code inJustDecodeBounds}）：像素数超过
   *       {@code maxPixels} 时直接返回 {@code null}（避免压缩炸弹先全量分配再 OOM，
   *       错误归因错——见计划 §1.6 / AndroidImageDataProvider.kt:47-54 先例）
   * </ul>
   *
   * @param raw 原始图片字节（调用方已校验 magic bytes 为 PNG/JPEG）
   * @param maxPixels 解码后像素数上限；{@code <= 0} 表示不限制
   */
  Decoded decode(byte[] raw, long maxPixels);

  /**
   * 把 ARGB_8888 像素编码为 PNG 字节。
   *
   * <p>实现语义：
   * <ul>
   *   <li>结果超过 {@code maxInlineBytes} 时，按 0.75 比例逐步缩小最长边重试
   *       （最多 5 轮，下限 320px），返回最后一次的编码结果与实际尺寸——
   *       与 {@code PhoneScreenshotTool.encodeWithinBudget} 同款语义
   *   <li>编码失败（OOM 等）返回 {@code null}
   * </ul>
   *
   * @param maxInlineBytes 内联预算；{@code Long.MAX_VALUE} 表示不收缩（落盘用全尺寸）
   */
  Encoded encodePng(int[] pixels, int width, int height, long maxInlineBytes);

  /**
   * 高质量缩放（bilinear/bicubic 用；nearest 由工具层 ImageOps 纯数组实现）。
   *
   * <p>实现用 {@code Bitmap.createScaledBitmap}（filter=true 双线性）。失败返回 {@code null}。
   *
   * @param filter {@code "bilinear"} 或 {@code "bicubic"}（bicubic 在 Android 上退化双线性）
   */
  Decoded scale(int[] pixels, int width, int height, int targetWidth, int targetHeight,
      String filter);
}
