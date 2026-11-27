package com.tom.rv2ide.uidesigner.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.uidesigner.R
import kotlin.math.roundToInt

/** 描边和拖放提示单独绘制，不修改布局控件的属性或尺寸。 */
class SelectionOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
  private val density = resources.displayMetrics.density
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = MaterialColors.getColor(context, R.attr.colorPrimary, 0xff426bc2.toInt())
    strokeWidth = 2f * density
    style = Paint.Style.STROKE
  }
  private val contrast = Paint(stroke).apply {
    color = MaterialColors.getColor(context, R.attr.colorSurface, 0xffffffff.toInt())
    strokeWidth = 4f * density
  }
  private var selection = emptyList<View>()
  private var drop: Rect? = null
  private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { invalidate() }
  private val onScroll = ViewTreeObserver.OnScrollChangedListener { invalidate() }

  internal fun setSelection(views: List<View>) {
    selection = views
    invalidate()
  }

  internal fun setDrop(bounds: Rect?) {
    if (drop == bounds) return
    drop = bounds
    invalidate()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnGlobalLayoutListener(onLayout)
    viewTreeObserver.addOnScrollChangedListener(onScroll)
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
    viewTreeObserver.removeOnScrollChangedListener(onScroll)
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val offset = Point()
    if (!getGlobalVisibleRect(Rect(), offset)) return
    fun outline(source: Rect, radius: Float) {
      val left = (source.left - offset.x).toFloat().coerceAtLeast(stroke.strokeWidth)
      val top = (source.top - offset.y).toFloat().coerceAtLeast(stroke.strokeWidth)
      val right = (source.right - offset.x).toFloat().coerceAtMost(width - stroke.strokeWidth)
      val bottom = (source.bottom - offset.y).toFloat().coerceAtMost(height - stroke.strokeWidth)
      if (right < left || bottom < top) return
      canvas.drawRoundRect(left, top, right, bottom, radius, radius, contrast)
      canvas.drawRoundRect(left, top, right, bottom, radius, radius, stroke)
    }
    selection.forEach { target -> boundsOf(target)?.let { outline(it, 4 * density) } }
    drop?.let { outline(it, 1 * density) }
  }

  internal companion object {
    /** 空的 wrap_content 容器也有可命中的范围，但不会改变测量结果。 */
    fun boundsOf(view: View): Rect? {
      if (!view.isShown || !view.isAttachedToWindow) return null
      val bounds = Rect()
      if (view.getGlobalVisibleRect(bounds)) return bounds
      if (view.width > 0 && view.height > 0) return null
      val parent = view.parent as? View ?: return null
      val visible = Rect()
      val origin = Point()
      if (!parent.getGlobalVisibleRect(visible, origin)) return null
      val size = (24 * view.resources.displayMetrics.density).roundToInt()
      val left = origin.x + view.left - parent.scrollX
      val top = origin.y + view.top - parent.scrollY
      bounds.set(left, top, left + view.width.coerceAtLeast(size), top + view.height.coerceAtLeast(size))
      return bounds.takeIf { it.intersect(visible) }
    }
  }
}
