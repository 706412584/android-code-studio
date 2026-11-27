/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.drag

import android.graphics.Rect
import android.view.DragEvent
import android.view.View
import com.tom.rv2ide.uidesigner.models.Placement
import com.tom.rv2ide.uidesigner.views.SelectionOverlayView

/** 悬停只画提示；只有有效的 DROP 才修改源文档。 */
internal class WidgetDragListener(
    private val overlay: SelectionOverlayView,
    private val locate: (WidgetDrag, Float, Float) -> Pair<Placement, Rect>?,
    private val drop: (WidgetDrag, Placement) -> Boolean,
) : View.OnDragListener {
  override fun onDrag(v: View, event: DragEvent): Boolean {
    val item = event.localState as? WidgetDrag ?: return false
    return when (event.action) {
      DragEvent.ACTION_DRAG_STARTED -> true
      DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
        overlay.setDrop(locate(item, event.x, event.y)?.second)
        true
      }
      DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
        overlay.setDrop(null)
        true
      }
      DragEvent.ACTION_DROP -> {
        overlay.setDrop(null)
        locate(item, event.x, event.y)?.first?.let { drop(item, it) } ?: false
      }
      else -> false
    }
  }
}
