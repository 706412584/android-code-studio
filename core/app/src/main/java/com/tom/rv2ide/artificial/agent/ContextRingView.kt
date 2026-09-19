/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * 上下文占用圆环。
 *
 * <p><b>为什么是圆环而不是文字</b>：占用比例是「一眼扫过」的信息——用户想知道
 * 「还能聊多久」。写成「12.3k / 64k」要读两个数字再心算比例，而圆环的形状本身
 * 就是答案。详细数字放在长按的提示里，需要时才看。
 *
 * <p><b>为什么是自绘而不是 ProgressIndicator</b>：Material 的
 * {@code CircularProgressIndicator} 是「不确定进度」的旋转动画控件，用作静态
 * 比例指示会带上它自带的动画与最小尺寸约束（默认 40dp 起），塞不进工具条。
 * 自绘只有几十行，且尺寸完全可控。
 *
 * <p><b>配色阈值与参考项目一致</b>：≥90% 用 error（该压缩了），≥75% 用
 * tertiary（留意），其余用 primary。三档而不是连续渐变——用户需要的是「现在要不要
 * 做点什么」，不是精确的色相。
 *
 * <p><b>未配置上下文窗口时不显示</b>：此时比例无意义（分母为 0），画一个空环
 * 会让用户以为「占用 0%」而不是「不知道」。
 */
class ContextRingView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

  /** 占用比例 0..1；负值表示「未知」，不画。 */
  private var ratio = -1f

  private val rect = RectF()

  /**
   * 绑定占用比例，并更新无障碍描述。
   *
   * <p>contentDescription 在**这里**设置而不是在 XML 里：XML 里的
   * `android:contentDescription="@string/xxx"` 不会做 `%1$s` 替换，
   * 无障碍服务会逐字读出「上下文占用 %1$s」。实测 UI dump 里就是这个字符串。
   */
  fun bind(ratio: Float, contentDescription: CharSequence? = null) {
    this.ratio = ratio.coerceIn(0f, 1f)
    if (contentDescription != null) {
      this.contentDescription = contentDescription
    }
    invalidate()
  }

  /**
   * 置为未知态（未配置上下文窗口）。
   *
   * <p>同时把自己设为不可见：未知态下 [onDraw] 直接返回，视图会留下一块 24dp 的空白，
   * 用户看到工具条中间有个无法解释的空档。隐藏后右侧控件自然左移。
   */
  fun clear() {
    ratio = -1f
    // 用 View.visibility 而不是 ktx 的 isVisible 扩展：本文件没有引入
    // androidx.core.view.isVisible，而 android.view.View 自身只有 visibility。
    visibility = GONE
    invalidate()
  }

  /** 绑定后必须显式恢复可见——[clear] 会把它设为 gone。 */
  fun show(ratio: Float, contentDescription: CharSequence?) {
    visibility = VISIBLE
    bind(ratio, contentDescription)
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val size = (SIZE_DP * resources.displayMetrics.density).toInt()
    setMeasuredDimension(
        resolveSize(size, widthMeasureSpec),
        resolveSize(size, heightMeasureSpec),
    )
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (ratio < 0f) {
      return
    }
    val stroke = STROKE_DP * resources.displayMetrics.density
    paint.strokeWidth = stroke
    paint.strokeCap = Paint.Cap.ROUND

    // 内缩半个描边宽度，否则描边会有一半被裁在视图外。
    val inset = stroke / 2f
    rect.set(inset, inset, width - inset, height - inset)

    paint.color =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
    canvas.drawArc(rect, 0f, 360f, false, paint)

    paint.color = accentColor()
    // 从 12 点方向顺时针：这是「进度」的通用读法（表盘、下载进度都是这个起点）。
    canvas.drawArc(rect, -90f, 360f * ratio, false, paint)
  }

  /**
   * 按占用比例取色。
   *
   * <p>注意 `colorError` / `colorPrimary` 取的是 **android 命名空间**的 attr：
   * 它们在框架里定义，Material 库的 R.attr 里没有，写
   * `com.google.android.material.R.attr.colorError` 编译不过（本仓库
   * AssistantModelPicker 里有同样的一条注释，是同一个坑）。
   */
  private fun accentColor(): Int {
    val attr =
        when {
          ratio >= DANGER_RATIO -> android.R.attr.colorError
          ratio >= WARN_RATIO -> com.google.android.material.R.attr.colorTertiary
          else -> android.R.attr.colorPrimary
        }
    return MaterialColors.getColor(this, attr)
  }

  companion object {
    private const val SIZE_DP = 24f
    private const val STROKE_DP = 2.5f

    /** 与参考项目（cc-haha 的上下文圆环）一致的两条阈值。 */
    private const val WARN_RATIO = 0.75f
    private const val DANGER_RATIO = 0.90f
  }
}
