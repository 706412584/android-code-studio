/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.drag

import android.annotation.SuppressLint
import android.content.ClipData
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.tom.rv2ide.inflater.models.UiWidget

internal sealed interface WidgetDrag {
  data class Existing(val id: Long) : WidgetDrag
  data class New(val widget: UiWidget) : WidgetDrag
}

internal fun View.startWidgetDrag(item: WidgetDrag, shadow: View = this): Boolean = startDragAndDrop(
    ClipData("Layout designer", arrayOf("application/x-acs-layout-widget"), ClipData.Item("widget")),
    WidgetDragShadowBuilder(shadow), item, 0,
)

/** 从覆盖层选择源节点，包括禁用控件、include 和空容器；不触发预览控件自身的操作。 */
internal class WidgetTouchListener(
    surface: View,
    onSelect: (Float, Float) -> Unit,
    onDragStart: (Float, Float) -> Unit,
) : View.OnTouchListener {
  private val detector = GestureDetector(surface.context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent): Boolean = true

    override fun onSingleTapUp(e: MotionEvent): Boolean {
      onSelect(e.x, e.y)
      surface.performClick()
      return true
    }

    override fun onLongPress(e: MotionEvent) = onDragStart(e.x, e.y)
  })

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouch(v: View, event: MotionEvent): Boolean {
    detector.onTouchEvent(event)
    return true
  }
}
