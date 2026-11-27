/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.fragments

import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.viewModels
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.fragments.BaseFragment
import com.tom.rv2ide.inflater.IView
import com.tom.rv2ide.inflater.IViewGroup
import com.tom.rv2ide.inflater.events.IInflateEventsListener
import com.tom.rv2ide.inflater.events.IInflationEvent
import com.tom.rv2ide.inflater.events.OnInflateViewEvent
import com.tom.rv2ide.inflater.internal.AttributeImpl
import com.tom.rv2ide.inflater.internal.ErrorLayout
import com.tom.rv2ide.inflater.internal.ErrorView
import com.tom.rv2ide.inflater.internal.LayoutFile
import com.tom.rv2ide.inflater.internal.NamespaceImpl
import com.tom.rv2ide.inflater.internal.ViewGroupImpl
import com.tom.rv2ide.inflater.internal.ViewImpl
import com.tom.rv2ide.inflater.models.UiWidget
import com.tom.rv2ide.inflater.utils.endParse
import com.tom.rv2ide.inflater.utils.isParsing
import com.tom.rv2ide.inflater.utils.startParse
import com.tom.rv2ide.inflater.viewAdapter
import com.tom.rv2ide.uidesigner.R
import com.tom.rv2ide.uidesigner.UIDesignerActivity
import com.tom.rv2ide.uidesigner.databinding.FragmentDesignerWorkspaceBinding
import com.tom.rv2ide.uidesigner.drag.WidgetDrag
import com.tom.rv2ide.uidesigner.drag.WidgetDragListener
import com.tom.rv2ide.uidesigner.drag.WidgetTouchListener
import com.tom.rv2ide.uidesigner.drag.startWidgetDrag
import com.tom.rv2ide.uidesigner.models.AttrKey
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.uidesigner.models.LayoutDocument.Companion.startsWith
import com.tom.rv2ide.uidesigner.models.Placement
import com.tom.rv2ide.uidesigner.utils.UiLayoutInflater
import com.tom.rv2ide.uidesigner.utils.ViewToXml
import com.tom.rv2ide.uidesigner.utils.applyDesignerPreview
import com.tom.rv2ide.uidesigner.utils.applyPreviewAttribute
import com.tom.rv2ide.uidesigner.viewmodel.WorkspaceViewModel
import com.tom.rv2ide.uidesigner.views.SelectionOverlayView
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

/** 真实 View 只属于当前预览，不写入编辑历史或跨 Activity 保存。 */
class DesignerWorkspaceFragment : BaseFragment() {
  private var binding: FragmentDesignerWorkspaceBinding? = null
  private val viewModel by viewModels<WorkspaceViewModel>(ownerProducer = { requireActivity() })
  private val activityHost get() = requireActivity() as UIDesignerActivity
  private var shown: LayoutDocument? = null
  private var workspace: IViewGroup? = null
  private var rendered = emptyMap<Long, List<IView>>()
  internal var ready = false
    private set

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
      FragmentDesignerWorkspaceBinding.inflate(inflater, container, false).also { binding = it }.root

  override fun onViewCreated(view: View, state: Bundle?) {
    super.onViewCreated(view, state)
    val overlay = checkNotNull(binding).selectionOverlay
    overlay.setOnDragListener(WidgetDragListener(overlay, ::locateDrop, activityHost::dropWidget))
    overlay.setOnTouchListener(WidgetTouchListener(overlay, { x, y ->
      if (ready) viewModel.select(hitNode(x, y)?.id ?: viewModel.current.document.root?.id)
    }, { x, y ->
      val node = if (ready) hitNode(x, y) else null
      if (node?.parentPath != null && activityHost.canStartDrag() &&
          overlay.startWidgetDrag(WidgetDrag.Existing(node.id), views(node.id).first().view)) {
        viewModel.select(node.id)
      }
    }))
    viewModel.state.observe(viewLifecycleOwner) { snapshot ->
      if (shown !== snapshot.document) render(snapshot.document, showError = true)
      select(snapshot.selectedId)
      activityHost.refreshWorkspace()
    }
  }

  internal fun views(id: Long): List<IView> = rendered[id].orEmpty()

  internal fun container(node: LayoutDocument.Node?): IViewGroup? = when {
    node == null || node.tag == "merge" -> workspace
    node.tag == "include" -> null
    else -> views(node.id).singleOrNull()?.takeUnless { it is ErrorLayout } as? IViewGroup
  }

  internal fun describePreview(node: LayoutDocument.Node): Boolean = ready && node.tag != "merge" &&
      (views(node.id).isEmpty() && node.tag != "include" || views(node.id).any { it is ErrorView || it is ErrorLayout })

  internal fun canInsert(parent: LayoutDocument.Node?, name: String, moving: LayoutDocument.Node? = null): Boolean {
    if (!ready) return false
    val document = viewModel.current.document
    if (parent == null && document.root != null) return false
    if (moving != null && (parent == null || parent.path.startsWith(moving.path))) return false
    val group = container(parent) ?: return false
    val child = moving?.let { views(it.id).singleOrNull() }
    val sameParent = moving != null && moving.parentPath == parent?.path
    return when (group.view) {
      is TextInputLayout -> {
        // TextInputLayout 只接受一个直接的编辑框；普通子 View 不是输入字段。
        (child?.view is EditText || isEditText(name)) && (sameParent || group.childCount == 0)
      }
      is ScrollView, is HorizontalScrollView, is NestedScrollView -> sameParent || group.childCount == 0
      else -> group.canAcceptChild(name, child)
    }
  }

  private fun isEditText(name: String): Boolean = try {
    EditText::class.java.isAssignableFrom(Class.forName(name, false, javaClass.classLoader))
  } catch (_: ClassNotFoundException) {
    false
  }

  internal fun newWidgetXml(widget: UiWidget, parent: LayoutDocument.Node?): String {
    val group = checkNotNull(container(parent))
    return parsing(viewModel.file) {
      val view = widget.createView(requireContext(), group.view as ViewGroup, (group as ViewImpl).file)
      val defaults = if (group.name == "androidx.constraintlayout.widget.ConstraintLayout") {
        listOf("app:layout_constraintStart_toStartOf" to "parent", "app:layout_constraintTop_toTopOf" to "parent")
      } else emptyList()
      ViewToXml.newWidget(view, defaults)
    }
  }

  private fun select(id: Long?) {
    val document = shown ?: return
    val views = if (document.node(id)?.tag == "merge") {
      listOfNotNull(workspace?.view)
    } else id?.let(::views).orEmpty().map { it.view }
    binding?.selectionOverlay?.setSelection(views)
  }

  private fun globalPoint(x: Float, y: Float): Point? {
    val origin = Point()
    if (binding?.selectionOverlay?.getGlobalVisibleRect(Rect(), origin) != true) return null
    return origin.apply { offset(x.roundToInt(), y.roundToInt()) }
  }

  private fun nodeBounds(node: LayoutDocument.Node): Rect? =
      views(node.id).mapNotNull { SelectionOverlayView.boundsOf(it.view) }.reduceOrNull { a, b -> a.apply { union(b) } }

  private fun hitNode(x: Float, y: Float): LayoutDocument.Node? {
    val point = globalPoint(x, y) ?: return null
    return hitNode(viewModel.current.document, viewModel.current.document.root, point)
  }

  private fun hitNode(document: LayoutDocument, node: LayoutDocument.Node?, point: Point): LayoutDocument.Node? {
    if (node == null || node.tag != "merge" && nodeBounds(node)?.contains(point.x, point.y) != true) return null
    val frontFirst = document.children(node).asReversed().sortedByDescending { child ->
      views(child.id).maxOfOrNull { it.view.z } ?: 0f
    }
    return frontFirst.firstNotNullOfOrNull { hitNode(document, it, point) } ?: node
  }

  private fun locateDrop(item: WidgetDrag, x: Float, y: Float): Pair<Placement, Rect>? {
    if (!ready) return null
    val point = globalPoint(x, y) ?: return null
    val document = viewModel.current.document
    val moving = (item as? WidgetDrag.Existing)?.let { document.node(it.id) }
    val name = when (item) {
      is WidgetDrag.New -> item.widget.name
      is WidgetDrag.Existing -> moving?.let { views(it.id).firstOrNull()?.name ?: it.tag } ?: return null
    }
    var candidate = hitNode(document, document.root, point)
    // 松手仍在被拖子树内时不是移动；向上寻找容器会把原地长按误判为移到祖先旁边。
    if (moving != null && candidate?.path?.startsWith(moving.path) == true) return null
    if (candidate == null) {
      if (document.root == null && canInsert(null, name)) {
        return workspace?.view?.let(SelectionOverlayView::boundsOf)?.let { Placement(null, 0) to it }
      }
      // 空白区域仍可向未铺满画布的根容器添加。
      candidate = document.root
    }
    while (candidate != null) {
      val parent = candidate.parentPath?.let(document::at)
      val limits = nodeBounds(candidate)
      val parentRow = (container(parent)?.view as? LinearLayout)?.takeIf { parent != null }
      if (limits != null && parent != null && parentRow != null && canInsert(parent, name, moving)) {
        val horizontal = parentRow.orientation == LinearLayout.HORIZONTAL
        val fraction = if (horizontal) (point.x - limits.left).toFloat() / limits.width()
            else (point.y - limits.top).toFloat() / limits.height()
        if (fraction in 0f..0.2f || fraction in 0.8f..1f) {
          val before = (fraction < 0.5f) != (horizontal && parentRow.layoutDirection == View.LAYOUT_DIRECTION_RTL)
          val siblings = document.children(parent)
          val index = candidate.path.last() + if (before) 0 else 1
          return Placement(parent.id, index) to insertionLine(limits, horizontal, fraction < 0.5f, siblings.isNotEmpty())
        }
      }
      if (canInsert(candidate, name, moving)) {
        val group = checkNotNull(container(candidate))
        val bounds = SelectionOverlayView.boundsOf(group.view) ?: return null
        val children = document.children(candidate)
        val row = group.view as? LinearLayout
        val horizontal = row?.orientation == LinearLayout.HORIZONTAL
        val rtl = horizontal && row?.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val childBounds = children.mapIndexedNotNull { index, child -> nodeBounds(child)?.let { index to it } }
        val before = if (row == null) null else childBounds.firstOrNull { (_, rect) ->
          if (horizontal) (point.x < rect.centerX()) != rtl else point.y < rect.centerY()
        }
        val index = before?.first ?: children.size
        val line = before?.second?.let { insertionLine(it, horizontal, !rtl) }
            ?: childBounds.lastOrNull()?.second?.let { insertionLine(it, horizontal, rtl) }.takeIf { row != null }
        return Placement(candidate.id, index) to (line ?: bounds)
      }
      candidate = parent
    }
    return null
  }

  private fun insertionLine(rect: Rect, horizontal: Boolean, start: Boolean, enabled: Boolean = true): Rect {
    if (!enabled) return rect
    val stroke = (2 * resources.displayMetrics.density).roundToInt()
    return if (horizontal) {
      val x = if (start) rect.left else rect.right
      Rect(x - stroke, rect.top, x + stroke, rect.bottom)
    } else {
      val y = if (start) rect.top else rect.bottom
      Rect(rect.left, y - stroke, rect.right, y + stroke)
    }
  }

  internal fun render(document: LayoutDocument, showError: Boolean = false): Boolean {
    val b = binding ?: return false
    val nextContainer = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = FrameLayout.LayoutParams(-1, -1)
    }
    val layoutFile = LayoutFile(viewModel.file, viewModel.file.nameWithoutExtension)
    val nextWorkspace = ViewGroupImpl(layoutFile, LinearLayout::class.java.name, nextContainer)
    val next = linkedMapOf<Long, List<IView>>()
    try {
      require(document.editable) { getString(R.string.designer_invalid_xml) }
      if (document.root != null) {
        UiLayoutInflater().use { inflater ->
          inflater.inflationEventListener = object : IInflateEventsListener {
            override fun onEvent(event: IInflationEvent<*>) {
              if (event is OnInflateViewEvent) (event.data as? ViewImpl)?.applyDesignerPreview()
            }
          }
          inflater.inflate(viewModel.file, document.xml, nextWorkspace) { path, nodes ->
            document.at(path)?.let { next[it.id] = nodes }
          }
        }
      }
    } catch (error: Exception) {
      log.warn("布局预览失败", error)
      if (showError) {
        shown = document
        ready = false
        rendered = emptyMap()
        workspace = null
        b.canvasHost.removeAllViews()
        b.previewMessage.text = getString(R.string.designer_preview_error, error.message.orEmpty())
        b.previewMessage.isVisible = true
      } else activityHost.showEditingError(error.message)
      return false
    }
    b.canvasHost.removeAllViews()
    b.canvasHost.addView(nextContainer)
    rendered = next
    workspace = nextWorkspace
    shown = document
    ready = true
    val empty = document.root == null || document.root.tag == "merge" && nextWorkspace.childCount == 0
    b.previewMessage.isVisible = empty
    b.previewMessage.text = getString(R.string.designer_empty_canvas)
    return true
  }

  internal fun restorePreview() {
    val state = viewModel.current
    if (render(state.document)) select(state.selectedId)
  }

  /** 草稿只应用到真实 View；取消时重绘已提交的源文档。 */
  internal fun previewAttribute(id: Long, key: AttrKey, value: String): Boolean {
    val target = views(id).singleOrNull() as? ViewImpl ?: return false
    val attribute = AttributeImpl(key.uri?.let { NamespaceImpl(key.prefix, it) }, key.name, value)
    return try {
      parsing(target.file.file) { target.applyPreviewAttribute(attribute) }.also { target.view.requestLayout() }
    } catch (error: Exception) {
      log.debug("无法预览属性 {}", key.qualifiedName, error)
      false
    }
  }

  private inline fun <T> parsing(file: java.io.File, action: () -> T): T {
    val started = !isParsing
    if (started) startParse(file)
    return try {
      action()
    } finally {
      if (started && isParsing) endParse()
    }
  }

  override fun onDestroyView() {
    binding = null
    workspace = null
    shown = null
    rendered = emptyMap()
    ready = false
    super.onDestroyView()
  }

  companion object {
    private val log = LoggerFactory.getLogger(DesignerWorkspaceFragment::class.java)
  }
}
