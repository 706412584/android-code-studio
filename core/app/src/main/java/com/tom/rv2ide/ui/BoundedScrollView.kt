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

package com.tom.rv2ide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView
import androidx.core.view.ViewCompat

/**
 * 限高的 [ScrollView]，用于把长内容（思维链、工具输出）限制在固定高度内滚动。
 *
 * <p><b>为什么不能只用 XML 的 `android:maxHeight`</b>：那个属性只对少数容器生效，
 * [ScrollView] 不认。不限高的后果是内容自由撑开——思维链动辄几千字，会把单条消息
 * 撑满好几屏，用户得滑很久才能看到正文。
 *
 * <p><b>为什么还要处理触摸事件</b>：本视图嵌在 `RecyclerView` 里。默认情况下手指在
 * 子 ScrollView 上竖滑时，事件会被外层列表抢走（外层判定为「列表滚动」），
 * 于是内层永远滚不动，用户以为内容是死的。这里按 LCP 的做法，在手指按下且内层
 * 确实可滚时请求父容器不要拦截；手指抬起或内层已滚到边界时交还控制权，
 * 让列表能继续正常滑动。
 *
 * @author AndroidCodeStudio
 */
class BoundedScrollView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ScrollView(context, attrs, defStyleAttr) {

  /** 高度上限（像素）。0 表示不限制。 */
  private var maxHeightPx = 0

  private var lastTouchY = 0f

  init {
    // 读 XML 里声明的 maxHeight（单位 dp）。用 obtainStyledAttributes 而不是把
    // 属性名写死在代码里，这样布局文件改数值不必改代码。
    val typed =
        context.obtainStyledAttributes(
            attrs,
            intArrayOf(android.R.attr.maxHeight),
        )
    try {
      maxHeightPx = typed.getDimensionPixelSize(0, 0)
    } finally {
      typed.recycle()
    }
  }

  /** 设置高度上限（dp）。传 0 或负数表示不限制。 */
  fun setMaxHeightDp(maxHeightDp: Int) {
    maxHeightPx = if (maxHeightDp <= 0) 0 else (maxHeightDp * resources.displayMetrics.density).toInt()
    requestLayout()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    if (maxHeightPx > 0) {
      val size = MeasureSpec.getSize(heightMeasureSpec)
      val mode = MeasureSpec.getMode(heightMeasureSpec)
      val capped = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
      super.onMeasure(
          widthMeasureSpec,
          MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST),
      )
      return
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastTouchY = event.y
        // 按下时若内层可滚，就先声明所有权，否则外层会在第一次 MOVE 时把事件抢走。
        disallowIntercept(
            shouldClaim(visible = visibility == VISIBLE, hasChild = childCount > 0, bounded = maxHeightPx > 0))
      }
      MotionEvent.ACTION_MOVE -> {
        val nextY = event.y
        // 手指下滑（nextY 增大）时内容应向上滚，即查「还能否向上滚」(-1)。
        val direction = if (nextY - lastTouchY > 0) -1 else 1
        disallowIntercept(hasRoomToScroll(direction))
        lastTouchY = nextY
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> disallowIntercept(false)
      else -> {}
    }
    return super.dispatchTouchEvent(event)
  }

  private fun disallowIntercept(disallow: Boolean) {
    parent?.requestDisallowInterceptTouchEvent(disallow)
  }

  /** 按下时是否声明触摸所有权。 */
  private fun shouldClaim(visible: Boolean, hasChild: Boolean, bounded: Boolean): Boolean =
      visible && hasChild && bounded

  /**
   * 手指方向上是否还有可滚内容。
   *
   * <p>滚到边界时必须交还触摸所有权——否则用户滑到底后继续滑，外层列表不动，
   * 看起来像卡死。
   *
   * <p>刻意不叫 `canScrollVertically`：那是 [android.view.View] 的公开方法，
   * 同名会遮蔽父类成员（Kotlin 会要求 override，而语义并不相同）。
   */
  private fun hasRoomToScroll(direction: Int): Boolean =
      ViewCompat.canScrollVertically(this, direction)
}
