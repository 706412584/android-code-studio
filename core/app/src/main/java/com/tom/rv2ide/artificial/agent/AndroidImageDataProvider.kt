/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom.rv2ide.ai.tool.api.ImageDataProvider
import java.io.ByteArrayOutputStream

/**
 * [ImageDataProvider] 的 Android 实现：用 `BitmapFactory` 解码并按最长边缩放。
 *
 * <p>工具模块（`core/ai-tool`）是纯 Java、零 Android 依赖的 java-library，不能直接引用
 * `android.graphics`，因此把「缩放」这一步抽成端口，由这里提供实现并注入 `ToolContext`。
 * 端口契约见 [ImageDataProvider]。
 *
 * <p>无状态、线程安全：agent 一次运行里多个 `file_read` 可能并发读图。
 */
object AndroidImageDataProvider : ImageDataProvider {

  /** JPEG 有损压缩质量；85 是视觉与体积的常用折中。 */
  private const val JPEG_QUALITY = 85

  override fun downscaleToMaxDimension(raw: ByteArray, maxDimensionPx: Int): ByteArray? {
    if (raw.isEmpty() || maxDimensionPx <= 0) {
      return null
    }
    var decoded: Bitmap? = null
    var scaled: Bitmap? = null
    return try {
      // 先只读边界，避免为大图直接分配完整位图。
      val bounds =
          BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
      val width = bounds.outWidth
      val height = bounds.outHeight
      if (width <= 0 || height <= 0) {
        return null
      }
      val longest = maxOf(width, height)
      if (longest <= maxDimensionPx) {
        // 已在预算内，返回 null 让调用方用原字节，避免无谓重编码损失画质。
        return null
      }

      val options =
          BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longest, maxDimensionPx) }
      decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
      // inSampleSize 只能取 2 的幂，采样后可能仍大于上限，再用 createScaledBitmap 精确收口。
      scaled = scaleToMax(decoded, maxDimensionPx)

      val out = ByteArrayOutputStream()
      val format =
          if (bounds.outMimeType == "image/png") Bitmap.CompressFormat.PNG
          else Bitmap.CompressFormat.JPEG
      scaled.compress(format, JPEG_QUALITY, out)
      out.toByteArray()
    } catch (e: Throwable) {
      // 解码失败或 OOM 都退化为「不缩放」，由工具层用原图兜底（它有体积上限保护）。
      null
    } finally {
      if (scaled != null && scaled !== decoded) {
        scaled.recycle()
      }
      decoded?.recycle()
    }
  }

  /** 求不超过 `longest / maxDimensionPx` 的最大 2 的幂采样率。 */
  private fun sampleSizeFor(longest: Int, maxDimensionPx: Int): Int {
    var sample = 1
    while (longest / (sample * 2) >= maxDimensionPx) {
      sample *= 2
    }
    return sample
  }

  /** 按最长边缩放到 `maxDimensionPx`；已在范围内时原样返回。 */
  private fun scaleToMax(bitmap: Bitmap, maxDimensionPx: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxDimensionPx) {
      return bitmap
    }
    val ratio = maxDimensionPx.toFloat() / longest
    val targetWidth = maxOf(1, (bitmap.width * ratio).toInt())
    val targetHeight = maxOf(1, (bitmap.height * ratio).toInt())
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
  }
}
