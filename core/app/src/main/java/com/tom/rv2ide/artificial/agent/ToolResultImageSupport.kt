/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.ai.protocol.ImageInputPayload

/**
 * 工具结果图片的解码与校验。
 *
 * <p><b>为什么单独抽出来</b>：这里全是「能不能显示」的判定（大小、类型、base64 是否合法），
 * 与 Android 控件无关。抽成纯逻辑后可以在 JVM 上单测——而这类判定恰恰最需要测试：
 * 判错的后果是「静默不显示」或「主线程 OOM」，两者都不会抛异常、不会留下日志。
 *
 * <p><b>为什么要校验而不是直接解</b>：base64 来自工具（`phone_screenshot`、
 * `image_generation`，以及第三方 MCP server）。第三方来源不可信，
 * 一段几 MB 的畸形 base64 在主线程解码就是 ANR 甚至 OOM。
 */
object ToolResultImageSupport {

  /**
   * 单张图片的字节上限。
   *
   * <p>8MB 与参考项目（cc-haha 的 `toolResultContent.ts`）一致，也与本仓库
   * `AssistantInputFeatures.MAX_IMAGE_BYTES` 同量级。1568px 的截图 ARGB_8888 约 4.5MB，
   * 8MB 足够覆盖正常截图，又能挡住明显异常的载荷。
   */
  const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

  /** 解码结果。 */
  sealed interface Decoded {
    /** 解码成功。 */
    data class Ok(val bytes: ByteArray) : Decoded

    /** 被拒绝的原因；调用方据此决定是否给用户提示。 */
    data class Rejected(val reason: Reason) : Decoded
  }

  /** 拒绝原因。 */
  enum class Reason {
    /** 没有图片数据。 */
    EMPTY,

    /** MIME 类型不在允许列表内（例如 svg）。 */
    UNSUPPORTED_TYPE,

    /** base64 不是合法编码。 */
    MALFORMED,

    /** 解码后超过 [MAX_IMAGE_BYTES]。 */
    TOO_LARGE,
  }

  /**
   * 校验并解码一张工具结果图片。
   *
   * <p><b>为什么 mimeType 只做「快速拒绝」</b>：协议层的 `normalizeMimeType` 会把无法识别的
   * 类型**静默兜底成 `image/png`**，所以 mimeType 字段本身不可信——真正能证明图片有效的
   * 只有「base64 能否解出来」。因此类型检查只用来挡掉明确不支持的类型（svg 等），
   * 不作为通过的充分条件。
   *
   * <p><b>必须后台调用</b>：几 MB 的 base64 解码是 CPU 密集的，在主线程做就是 ANR 风险。
   *
   * @param mimeType 协议层给出的 MIME 类型，可为空
   * @param base64 不含 data URL 前缀的 base64 数据
   */
  @JvmStatic
  fun decode(mimeType: String?, base64: String?): Decoded {
    if (base64.isNullOrEmpty()) {
      return Decoded.Rejected(Reason.EMPTY)
    }
    // 类型检查放在解码**之前**：解一段明确不支持的格式（比如 svg）纯属浪费，
    // 而且 svg 解出来的字节交给 Glide 也可能被当作位图尝试而失败。
    //
    // **必须检查原始 mimeType，不能先 normalize**：normalizeMimeType 的语义是
    // 「无法识别就兜底成 image/png」，它会把「不支持」这个信息抹掉——
    // 先 normalize 再 isSupportedMimeType，等于检查一个永远合法的值，
    // svg 之类的类型会全部放行（实测过）。
    //
    // 空 mimeType 放行：那种情况无从判断，让「能否解码」这个更可靠的判据说话。
    val raw = mimeType?.trim().orEmpty()
    if (raw.isNotEmpty() && !ImageInputPayload.isSupportedMimeType(raw)) {
      return Decoded.Rejected(Reason.UNSUPPORTED_TYPE)
    }

    // 先按 base64 长度粗筛，避免为一个必然超限的载荷分配几 MB 的字节数组。
    //
    // 留 4 字节余量：base64 每 4 字符表示 3 字节，但末组带 padding，
    // 因此 len*3/4 会**高估**最多 2 字节。不留余量会把「恰好等于上限」的合法图片误杀
    // ——实测 8MB 的图正好触发。粗筛的目的是挡掉明显异常的载荷（几十 MB），
    // 精确边界交给解码后的复核。
    val estimated = base64.length.toLong() * 3L / 4L
    if (estimated > MAX_IMAGE_BYTES + 4L) {
      return Decoded.Rejected(Reason.TOO_LARGE)
    }

    val bytes =
        try {
          // 用 java.util.Base64 而不是 android.util.Base64：前者是 JDK 标准库，
          // minSdk 26 可用，且能让这个判定在纯 JVM 单测里跑（见类注释）。
          java.util.Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
          return Decoded.Rejected(Reason.MALFORMED)
        }
    if (bytes.isEmpty()) {
      return Decoded.Rejected(Reason.EMPTY)
    }
    // 粗筛之后仍要复核实际大小：base64 里可能含空白字符，使粗筛偏小。
    if (bytes.size > MAX_IMAGE_BYTES) {
      return Decoded.Rejected(Reason.TOO_LARGE)
    }
    return Decoded.Ok(bytes)
  }

  /**
   * 图片内容的缓存键。
   *
   * <p><b>为什么必须有</b>：`Glide.load(byte[])` 的默认缓存键基于 `byte[]` 的 `equals`，
   * 而数组按**引用**比较——每次新建的数组都是新键，缓存永不命中，
   * 滚动回看会反复重新解码与上传纹理。用内容摘要做键才能命中。
   *
   * <p>取长度 + 首尾片段而不是完整 SHA-1：算完整摘要要遍历几 MB 数据，
   * 而这里只需要「不同图片大概率不同键」。长度加上首尾各 64 字符已足够区分
   * 两张不同的截图，代价是常数级。
   */
  @JvmStatic
  fun cacheKey(base64: String?): String {
    if (base64.isNullOrEmpty()) {
      return ""
    }
    val head = base64.take(64)
    val tail = if (base64.length > 64) base64.takeLast(64) else ""
    return "${base64.length}:$head:$tail"
  }

  /**
   * 弹出放大查看。
   *
   * <p>手势用 `ScaleGestureDetector` + 简单的位移跟随，不引第三方库：
   * 需求只有「放大看清小字」，一个 60 行的 Dialog 足够，而引入 PhotoView
   * 会为一张截图背上一个额外的依赖。
   *
   * <p>图片从**已解码的字节**加载而不是 base64 字符串：Glide 不能把 base64 当图片源
   * （会被当作路径解析而失败），而字节可以直接交给它。
   */
  @android.annotation.SuppressLint("ClickableViewAccessibility")
  @JvmStatic
  fun showLightbox(context: android.content.Context, bytes: ByteArray, cacheKey: String) {
    val view =
        android.view.LayoutInflater.from(context)
            .inflate(com.tom.rv2ide.R.layout.dialog_tool_image, null, false)
    val image = view.findViewById<android.widget.ImageView>(com.tom.rv2ide.R.id.lightboxImage)

    val dialog =
        android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
          setContentView(view)
          // 点任意处关闭：看图时用户不该去找关闭按钮。
          view.setOnClickListener { dismiss() }
        }

    com.bumptech.glide.Glide.with(image)
        .load(bytes)
        .signature(com.bumptech.glide.signature.ObjectKey(cacheKey))
        .into(image)

    // 缩放 + 拖动。matrix 变换直接作用在 ImageView 上。
    val matrix = android.graphics.Matrix()
    var scale = 1f
    var lastX = 0f
    var lastY = 0f
    val scaleDetector =
        android.view.ScaleGestureDetector(
            context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
              override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                // 上限 8x：再放大只会看到像素块，没有信息增益。
                scale = (scale * detector.scaleFactor).coerceIn(1f, 8f)
                matrix.setScale(scale, scale)
                image.imageMatrix = matrix
                return true
              }
            })

    image.setOnTouchListener { v, event ->
      scaleDetector.onTouchEvent(event)
      when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
          lastX = event.rawX
          lastY = event.rawY
          true
        }
        android.view.MotionEvent.ACTION_MOVE -> {
          if (scale > 1f) {
            // 只有放大后才平移：未放大时拖动没有意义，反而会让图片跑出屏幕。
            val dx = event.rawX - lastX
            val dy = event.rawY - lastY
            lastX = event.rawX
            lastY = event.rawY
            matrix.postTranslate(dx, dy)
            image.imageMatrix = matrix
          }
          true
        }
        else -> v.onTouchEvent(event)
      }
    }

    dialog.show()
  }
}
