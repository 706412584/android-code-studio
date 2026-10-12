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

package com.tom.rv2ide.artificial.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom.rv2ide.ai.tool.ImageProcessingPort
import java.io.ByteArrayOutputStream

/**
 * [ImageProcessingPort] 的 Android 实现：BitmapFactory 解码 / Bitmap.compress 编码。
 *
 * 实现保持无状态、线程安全（与 [AndroidImageDataProvider] 同款定位）；
 * 像素运算在 core/ai-tool 的 ImageOps 里，本类只做字节搬运与编解码。
 */
object AndroidImageProcessor : ImageProcessingPort {

  override fun decode(raw: ByteArray, maxPixels: Long): ImageProcessingPort.Decoded? {
    if (raw.isEmpty()) return null
    var bitmap: Bitmap? = null
    return try {
      // 两段式预查（复审 m6）：先只读边界，超像素上限直接拒绝——避免压缩炸弹
      // 先全量分配再 OOM，最终被误报成「解码失败」。先例：AndroidImageDataProvider。
      if (maxPixels > 0) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        if (w.toLong() * h.toLong() > maxPixels) return null
      }
      // ARGB_8888 显式指定：默认解码可能给出 RGB_565（丢 alpha / 降精度），
      // 像素运算必须拿到完整 8bit 四通道。
      val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
      bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
      val w = bitmap.width
      val h = bitmap.height
      val pixels = IntArray(w * h)
      bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
      ImageProcessingPort.Decoded(pixels, w, h)
    } catch (t: Throwable) {
      // OOM 也走这里：解码失败退化为 null，由工具层报明确错误。
      null
    } finally {
      bitmap?.recycle()
    }
  }

  override fun encodePng(
      pixels: IntArray, width: Int, height: Int, maxInlineBytes: Long,
  ): ImageProcessingPort.Encoded? {
    var current = pixels
    var w = width
    var h = height
    return try {
      for (attempt in 0 until 5) {
        val bytes = encode(current, w, h) ?: return null
        if (bytes.size <= maxInlineBytes) {
          return ImageProcessingPort.Encoded(bytes, w, h)
        }
        val longest = maxOf(w, h)
        if (longest <= 320) {
          return ImageProcessingPort.Encoded(bytes, w, h) // 缩到底仍超限：原样返回
        }
        val next = maxOf(320, (longest * 0.75f).toInt())
        val nw = maxOf(1, w * next / longest)
        val nh = maxOf(1, h * next / longest)
        current = scaleNearest(current, w, h, nw, nh)
        w = nw
        h = nh
      }
      null
    } catch (t: Throwable) {
      null
    }
  }

  override fun scale(
      pixels: IntArray, width: Int, height: Int,
      targetWidth: Int, targetHeight: Int, filter: String,
  ): ImageProcessingPort.Decoded? {
    if (filter == "nearest") {
      return ImageProcessingPort.Decoded(
          scaleNearest(pixels, width, height, targetWidth, targetHeight),
          targetWidth, targetHeight)
    }
    var src: Bitmap? = null
    var scaled: Bitmap? = null
    return try {
      src = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
      // filter=true：双线性；bicubic 在 Android 无独立开关，按双线性处理（工具层文本已说明）。
      scaled = Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
      val out = IntArray(targetWidth * targetHeight)
      scaled.getPixels(out, 0, targetWidth, 0, 0, targetWidth, targetHeight)
      ImageProcessingPort.Decoded(out, targetWidth, targetHeight)
    } catch (t: Throwable) {
      null
    } finally {
      scaled?.recycle()
      src?.recycle()
    }
  }

  private fun encode(pixels: IntArray, width: Int, height: Int): ByteArray? {
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    return try {
      val out = ByteArrayOutputStream()
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
      out.toByteArray()
    } finally {
      bitmap.recycle()
    }
  }

  /** 最近邻缩放（内联副本，与 ImageOps.scaleNearest 同式——app 层不依赖 ai-tool 的实现细节）。 */
  private fun scaleNearest(
      pixels: IntArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int,
  ): IntArray {
    val out = IntArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
      val sy = (y.toLong() * height / targetHeight).toInt()
      for (x in 0 until targetWidth) {
        val sx = (x.toLong() * width / targetWidth).toInt()
        out[y * targetWidth + x] = pixels[sy * width + sx]
      }
    }
    return out
  }
}
