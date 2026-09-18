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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.resources.R.string

/**
 * 「思考中…」/「处理中…」状态条：3×3 点阵旋转动画 + 文案。
 *
 * <p><b>为什么需要它</b>：模型首字节可能要等好几秒（推理模型更久），而此前的界面在等待
 * 期间只有一个静态的「正在运行 xxx…」文字。静态文字无法区分「在工作」和「卡死了」，
 * 用户只能干等或误以为程序无响应。一个持续运动的指示器是「还活着」最直接的信号。
 *
 * <p><b>两种文案的区分依据</b>：有推理内容且正文还是空 → 模型在思考（[bind] 传
 * `thinking=true`）；否则 → 在输出正文或调用工具。这个区分有实际意义：推理模型
 * 思考半分钟是正常的，用户看到「思考中」不会以为出了问题。
 *
 * <p><b>动画生命周期</b>：只在被要求且已 attach 时运行。视图从窗口分离时立即停止——
 * 否则动画会持有视图引用，在列表里造成泄漏。同时尊重系统的「动画时长」开发者选项：
 * 用户把动画关掉（scale=0）时不启动，避免无意义的持续重绘。
 */
class WorkingStatusView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val textPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        isSubpixelText = true
      }

  private val density = resources.displayMetrics.density
  private val dotRadiusPx = 1.6f * density
  private val dotStepPx = 4.4f * density
  private val matrixSizePx = dotStepPx * 2f + dotRadiusPx * 2f
  private val gapPx = 8f * density
  private val paddingPx = 4f * density
  private val minHeightPx = 20f * density

  private var animator: ValueAnimator? = null
  private var working = false
  private var thinking = false

  /** 动画进度 0..1，循环。点阵帧与文字亮度都由它推导。 */
  private var progress = 0f

  /** 当前显示文案。 */
  private val label: String
    get() =
        context.getString(
            if (thinking) string.ai_assistant_thinking_running else string.ai_assistant_working
        )

  fun bind(isThinking: Boolean) {
    thinking = isThinking
    requestLayout()
    invalidate()
  }

  fun startWorking() {
    if (working) {
      return
    }
    working = true
    if (isAttachedToWindow) {
      startAnimator()
    }
  }

  fun stopWorking() {
    working = false
    stopAnimator()
    progress = 0f
    invalidate()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    // attach 时才启动：bind 可能发生在视图尚未进入窗口时（列表预绑定），
    // 那时启动动画会白跑一段。
    if (working) {
      startAnimator()
    }
  }

  override fun onDetachedFromWindow() {
    // 必须停：动画持有本视图的引用，随列表回收会造成泄漏与后台重绘。
    stopAnimator()
    super.onDetachedFromWindow()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val desiredWidth =
        (paddingPx * 2 + matrixSizePx + gapPx + textPaint.measureText(label)).toInt()
    val desiredHeight = maxOf(suggestedMinimumHeight, minHeightPx.toInt())
    setMeasuredDimension(
        resolveSize(desiredWidth, widthMeasureSpec),
        resolveSize(desiredHeight, heightMeasureSpec),
    )
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (!working) {
      return
    }
    // colorPrimary 不在 material 的 R.attr 里（是 android: 命名空间的），
    // 这里用确实导出的 colorPrimaryContainer。
    val accent =
        MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimaryContainer,
            COLOR_FALLBACK,
        )
    drawDotMatrix(canvas, accent)
    drawLabel(canvas)
  }

  /**
   * 画 3×3 点阵。
   *
   * <p>亮点的位置按 [FRAME_OFFSETS] 顺时针轮转，形成「绕圈」的观感；前一个位置留一个
   * 半亮的拖尾点，让转动方向可辨（只有单个亮点时看不出在往哪转）。
   */
  private fun drawDotMatrix(canvas: Canvas, accent: Int) {
    val frame = (progress * FRAME_COUNT).toInt()
    val active = FRAME_OFFSETS[frame % FRAME_OFFSETS.size]
    val trailing = FRAME_OFFSETS[(frame + FRAME_OFFSETS.size - 1) % FRAME_OFFSETS.size]

    val top = (height - matrixSizePx) / 2f
    val left = paddingPx

    for (index in 0 until DOT_COUNT) {
      val col = index % DOT_COLUMNS
      val row = index / DOT_COLUMNS
      val cx = left + dotRadiusPx + col * dotStepPx
      val cy = top + dotRadiusPx + row * dotStepPx
      dotPaint.color =
          when (index) {
            active -> accent
            trailing -> withAlpha(accent, TRAILING_ALPHA)
            else -> withAlpha(accent, INACTIVE_ALPHA)
          }
      canvas.drawCircle(cx, cy, dotRadiusPx, dotPaint)
    }
  }

  private fun drawLabel(canvas: Canvas) {
    textPaint.color =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    val x = paddingPx + matrixSizePx + gapPx
    // 垂直居中：baseline = 中心 + (ascent 与 descent 的中点偏移)
    val fm = textPaint.fontMetrics
    val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
    canvas.drawText(label, x, baseline, textPaint)
  }

  private fun startAnimator() {
    if (!animationsEnabled()) {
      return
    }
    if (animator != null) {
      return
    }
    animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
          duration = CYCLE_MS
          repeatCount = ValueAnimator.INFINITE
          interpolator = LinearInterpolator()
          addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
          }
          start()
        }
  }

  private fun stopAnimator() {
    animator?.cancel()
    animator = null
  }

  /**
   * 系统「动画时长」设为 0（开发者选项里关掉动画）时不启动。
   *
   * <p>不只是省电：这类用户往往对动效敏感或需要无障碍支持，强制播放动画是不友好的。
   */
  private fun animationsEnabled(): Boolean {
    val scale =
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    return scale > 0f
  }

  private fun withAlpha(color: Int, alpha: Float): Int =
      (color and 0x00FFFFFF) or (((alpha * 255).toInt() and 0xFF) shl 24)

  companion object {
    private const val DOT_COLUMNS = 3
    private const val DOT_COUNT = 9

    /** 顺时针点序（0=左上，8=右下）。 */
    private val FRAME_OFFSETS = intArrayOf(1, 2, 5, 8, 7, 6, 3, 0)

    /** 一个完整周期的时长。每帧 = CYCLE_MS / FRAME_COUNT ≈ 90ms。 */
    private const val CYCLE_MS = 720L

    private const val FRAME_COUNT = 8

    private const val INACTIVE_ALPHA = 0.18f
    private const val TRAILING_ALPHA = 0.52f

    /** 主题未定义 colorPrimaryContainer 时的兜底色。 */
    private const val COLOR_FALLBACK = 0xFF7AA2F7.toInt()
  }
}
