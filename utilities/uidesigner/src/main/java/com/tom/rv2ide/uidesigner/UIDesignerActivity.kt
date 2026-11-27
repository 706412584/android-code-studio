/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.uidesigner

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.app.BaseIDEActivity
import com.tom.rv2ide.uidesigner.adapters.WidgetsCategoryAdapter
import com.tom.rv2ide.uidesigner.databinding.ActivityUiDesignerBinding
import com.tom.rv2ide.uidesigner.databinding.LayoutDesignerEditPanelBinding
import com.tom.rv2ide.uidesigner.drag.WidgetDrag
import com.tom.rv2ide.uidesigner.drag.startWidgetDrag
import com.tom.rv2ide.uidesigner.fragments.DesignerWorkspaceFragment
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.uidesigner.models.Placement
import com.tom.rv2ide.uidesigner.utils.ValueCompletionProvider
import com.tom.rv2ide.uidesigner.utils.Widgets
import com.tom.rv2ide.uidesigner.viewmodel.WorkspaceSnapshot
import com.tom.rv2ide.uidesigner.viewmodel.WorkspaceViewModel
import com.tom.rv2ide.uidesigner.views.LayoutFilesView
import com.tom.rv2ide.uidesigner.views.LayoutHierarchyView
import com.tom.rv2ide.uidesigner.views.PropertyPanel
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashInfo
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * 画布铺满，左抽屉放添加与布局结构，右抽屉只放属性；点顶栏文件名切换布局文件。
 * 只有显式「应用」才把结果交回编辑器；切换文件也经由编辑器写回后再重新打开设计器。
 */
class UIDesignerActivity : BaseIDEActivity() {
  private lateinit var binding: ActivityUiDesignerBinding
  private val model by viewModels<WorkspaceViewModel>()
  private val workspace: DesignerWorkspaceFragment?
    get() = supportFragmentManager.findFragmentById(R.id.workspace) as? DesignerWorkspaceFragment
  private lateinit var undo: MenuItem
  private lateinit var redo: MenuItem
  private lateinit var apply: MenuItem
  private var openError: String? = null
  private var panel: Panel? = null
  private var editPanel: LayoutDesignerEditPanelBinding? = null
  private var hierarchy: LayoutHierarchyView? = null
  private var properties: PropertyPanel? = null
  private var basicExpanded = true
  private var structureExpanded = true

  private enum class Panel(val start: Boolean) { EDIT(true), PROPERTIES(false) }

  override fun preSetContentLayout() {
    if (model.isOpen) return
    try {
      val file = File(requireNotNull(intent.getStringExtra(EXTRA_FILE))).absoluteFile
      val disk = file.readText()
      val xml = intent.getStringExtra(EXTRA_XML) ?: disk
      require(xml.length <= MAX_XML_LENGTH) { getString(R.string.designer_xml_too_large) }
      model.open(file, xml, sourceFingerprint(disk))
    } catch (error: Exception) {
      openError = error.message.orEmpty()
    }
  }

  override fun bindLayout(): View =
      ActivityUiDesignerBinding.inflate(layoutInflater).also { binding = it }.root

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (!model.isOpen) {
      showEditingError(openError)
      finish()
      return
    }
    // 资源表可能在上次打开后被刷新成新实例，这里让补全重新初始化 Lookup。
    ValueCompletionProvider.invalidateLookup()
    binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
    binding.toolbar.setNavigationContentDescription(R.string.btn_close)
    binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    val menu = binding.toolbar.menu
    val iconColor = MaterialColors.getColor(binding.toolbar, R.attr.colorOnSurface)
    val historyIconTint = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(MaterialColors.compositeARGBWithAlpha(iconColor, 97), iconColor),
    )
    undo = menu.add(R.string.undo).setIcon(R.drawable.ic_undo).apply {
      iconTintList = historyIconTint
      setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      setOnMenuItemClickListener { replay(redo = false); true }
    }
    redo = menu.add(R.string.redo).setIcon(R.drawable.ic_redo).apply {
      iconTintList = historyIconTint
      setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      setOnMenuItemClickListener { replay(redo = true); true }
    }
    apply = menu.add(R.string.action_apply).apply {
      setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      setOnMenuItemClickListener { applyChanges(); true }
    }
    menu.add(R.string.designer_show_xml).setOnMenuItemClickListener {
      startActivity(Intent(this, ShowXmlActivity::class.java).putExtra(ShowXmlActivity.KEY_XML, model.current.document.xml))
      true
    }

    binding.toolbarTitle.setOnClickListener { showFiles() }
    binding.editTab.setOnClickListener { showPanel(Panel.EDIT) }
    binding.propertiesTab.setOnClickListener { showPanel(Panel.PROPERTIES) }
    binding.scrim.setOnClickListener { showPanel(null) }
    binding.canvasArea.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
      if (r - l != oldR - oldL) sizePanel()
    }
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (panel != null) {
          showPanel(null)
        } else if (model.dirty) {
          MaterialAlertDialogBuilder(this@UIDesignerActivity)
              .setTitle(R.string.designer_discard_title)
              .setMessage(R.string.designer_discard_message)
              .setNegativeButton(R.string.cancel, null)
              .setPositiveButton(R.string.designer_discard) { _, _ -> finish() }
              .show()
        } else finish()
      }
    })
    showPanel(Panel.entries.find { it.name == savedInstanceState?.getString(STATE_PANEL) })
    refreshWorkspace()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(STATE_PANEL, panel?.name)
    super.onSaveInstanceState(outState)
  }

  private fun showPanel(target: Panel?) {
    if (target == panel && target != null) return
    properties?.cancelDraft()
    hideKeyboard()
    binding.panel.removeAllViews()
    panel = target
    binding.panel.isVisible = target != null
    binding.scrim.isVisible = target != null
    val content = when (target) {
      Panel.EDIT -> editPanel?.root ?: createEditPanel()
      Panel.PROPERTIES -> properties ?: PropertyPanel(this, model.file, { workspace }, ::edit).also { properties = it }
      null -> null
    }
    content?.let { binding.panel.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    sizePanel()
    refreshPanel()
  }

  private fun createEditPanel(): View {
    val b = LayoutDesignerEditPanelBinding.inflate(layoutInflater, binding.panel, false)
    editPanel = b
    b.widgets.adapter = WidgetsCategoryAdapter(Widgets.categories, { widget ->
      val placement = suggestedPlacement()
      if (placement == null) flashError(R.string.designer_no_add_target)
      else dropWidget(WidgetDrag.New(widget), placement)
    }, { view, widget ->
      (canStartDrag() && view.startWidgetDrag(WidgetDrag.New(widget))).also { if (it) showPanel(null) }
    })
    val tree = LayoutHierarchyView(this, model.collapsed, model::select)
    hierarchy = tree
    b.treeHost.addView(tree, FrameLayout.LayoutParams(-1, -2))
    fun section(header: View, chevron: View, content: View, expanded: () -> Boolean, toggle: () -> Unit) {
      fun show() {
        content.isVisible = expanded()
        chevron.rotation = if (expanded()) 90f else 0f
      }
      header.setOnClickListener { toggle(); show() }
      show()
    }
    section(b.basicHeader, b.basicChevron, b.basicContent, { basicExpanded }) { basicExpanded = !basicExpanded }
    section(b.structureHeader, b.structureChevron, b.structureContent, { structureExpanded }) { structureExpanded = !structureExpanded }
    return b.root
  }

  /** 弹出同模块的布局文件列表；选中后走 [switchTo]，有未应用改动时照常提示。 */
  private fun showFiles() {
    showPanel(null)
    var dialog: AlertDialog? = null
    val files = LayoutFilesView(this, layoutFiles()) { next ->
      dialog?.dismiss()
      switchTo(next)
    }
    files.bind(model.file)
    val height = (resources.displayMetrics.heightPixels * 0.6f).roundToInt()
    dialog = MaterialAlertDialogBuilder(this)
        .setView(FrameLayout(this).apply { addView(files, FrameLayout.LayoutParams(-1, height)) })
        .setNegativeButton(R.string.cancel, null)
        .show()
  }

  /** 与当前文件同一 res 目录下所有 layout* 限定符目录里的 XML。 */
  private fun layoutFiles(): List<File> {
    val current = model.file
    val folder = current.parentFile ?: return listOf(current)
    val res = folder.parentFile?.takeIf { folder.name.isLayoutFolder() } ?: return listOf(current)
    return res.listFiles { dir -> dir.isDirectory && dir.name.isLayoutFolder() }.orEmpty()
        .flatMap { dir -> dir.listFiles { file -> file.isFile && file.extension == "xml" }.orEmpty().asList() }
        .map { it.absoluteFile }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.parentFile?.name }))
  }

  private fun String.isLayoutFolder() = this == "layout" || startsWith("layout-")

  private fun switchTo(next: File) {
    if (next == model.file) {
      showPanel(null)
      return
    }
    if (!model.dirty) {
      finishWith(next, apply = false)
      return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.designer_switch_title, next.name))
        .setMessage(R.string.designer_switch_message)
        .setNegativeButton(R.string.cancel, null)
        .setNeutralButton(R.string.designer_switch_discard) { _, _ -> finishWith(next, apply = false) }
        .apply {
          // 预览不可用时生成的 XML 不可信，只允许放弃。
          if (workspace?.ready == true) setPositiveButton(R.string.designer_switch_apply) { _, _ -> finishWith(next, apply = true) }
        }
        .show()
  }

  private fun suggestedPlacement(): Placement? {
    val canvas = workspace?.takeIf { it.ready } ?: return null
    val state = model.current
    val document = state.document
    if (document.root == null) return Placement(null, 0)
    val node = document.node(state.selectedId) ?: return null
    if (canvas.container(node) != null && Widgets.categories.any { category ->
          category.widgets.any { canvas.canInsert(node, it.name) }
        }) {
      return Placement(node.id, document.children(node).size)
    }
    val parent = node.parentPath?.let(document::at) ?: return null
    return Placement(parent.id, node.path.last() + 1).takeIf {
      Widgets.categories.any { category -> category.widgets.any { canvas.canInsert(parent, it.name) } }
    }
  }

  private fun refreshPanel() {
    val state = model.current
    when (panel) {
      Panel.EDIT -> {
        bindLibrary(state)
        hierarchy?.bind(state) { workspace?.describePreview(it) == true }
        bindStructure(state.document.node(state.selectedId))
      }
      Panel.PROPERTIES -> properties?.bind(state)
      null -> Unit
    }
  }

  /** 选中容器则追加到末尾，选中普通控件则插到其后；顺序之后用前移/后移调整。 */
  private fun bindLibrary(state: WorkspaceSnapshot) {
    val b = editPanel ?: return
    val target = suggestedPlacement()
    val parent = state.document.node(target?.parentId)
    val selected = state.document.node(state.selectedId)
    b.target.text = when {
      target == null -> getString(R.string.designer_no_add_target)
      parent == null -> getString(R.string.designer_new_root)
      selected != null && selected.id != parent.id -> getString(R.string.designer_add_after, Widgets.displayLabel(this, selected))
      else -> getString(R.string.designer_add_to, Widgets.displayLabel(this, parent))
    }
  }

  private fun bindStructure(node: LayoutDocument.Node?) {
    val row = editPanel?.structureActions ?: return
    row.removeAllViews()
    val document = model.current.document
    val parent = node?.parentPath?.let(document::at)
    val target = suggestedPlacement()
    val index = node?.path?.lastOrNull() ?: 0
    val canMove = document.editable && workspace?.ready == true
    fun action(label: Int, enabled: Boolean, run: () -> Unit) {
      row.addView(MaterialButton(this, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
        setText(label)
        isAllCaps = false
        isEnabled = enabled
        minWidth = 0
        minimumWidth = 0
        insetTop = 0
        insetBottom = 0
        cornerRadius = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { run() }
      }, LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).roundToInt(), 1f))
    }
    action(R.string.designer_move_up, canMove && index > 0) {
      edit(document.move(node!!, parent!!, index - 1), node.id)
    }
    action(R.string.designer_move_down, canMove && parent != null && index + 1 < document.children(parent).size) {
      edit(document.move(node!!, parent!!, index + 2), node.id)
    }
    action(R.string.copy, node != null && node.tag != "merge" && document.editable) {
      model.copiedNode = WorkspaceViewModel.CopiedNode(workspace?.views(node!!.id)?.firstOrNull()?.name ?: node!!.tag, document.fragment(node))
      flashInfo(R.string.copied)
      refreshPanel()
    }
    action(R.string.designer_paste, target != null && model.copiedNode != null) { paste(target!!) }
    action(R.string.delete, node != null && parent != null && document.editable) {
      MaterialAlertDialogBuilder(this).setTitle(getString(R.string.designer_delete_title, Widgets.displayLabel(this, node!!)))
          .setNegativeButton(R.string.cancel, null)
          .setPositiveButton(R.string.delete) { _, _ -> edit(document.remove(node), parent!!.id) }
          .show()
    }
  }

  private fun paste(placement: Placement) {
    val copy = model.copiedNode ?: return
    val document = model.current.document
    val parent = document.node(placement.parentId)
    if (workspace?.canInsert(parent, copy.name) != true) {
      flashError(R.string.designer_attachment_rejected)
      return
    }
    val fragment = LayoutDocument(copy.xml, model.file.toURI().toString())
    val ids = document.nodes.mapNotNull { it.xmlId?.substringAfter('/') }.toSet()
    val duplicate = fragment.nodes.firstNotNullOfOrNull { it.xmlId?.substringAfter('/')?.takeIf(ids::contains) }
    if (duplicate != null) {
      flashError(getString(R.string.designer_paste_id_conflict, duplicate))
      return
    }
    val inserted = document.insert(parent, placement.index, copy.xml)
    edit(inserted, inserted.childAt(placement)?.id)
  }

  internal fun canStartDrag(): Boolean = workspace?.ready == true && allowEdit()

  internal fun dropWidget(item: WidgetDrag, placement: Placement): Boolean {
    if (!allowEdit()) return false
    val canvas = workspace ?: return false
    val document = model.current.document
    val parent = document.node(placement.parentId)
    return try {
      val edited = when (item) {
        is WidgetDrag.New -> {
          if (!canvas.canInsert(parent, item.widget.name)) {
            flashError(R.string.designer_attachment_rejected)
            return false
          }
          val inserted = document.insert(parent, placement.index, canvas.newWidgetXml(item.widget, parent))
          edit(inserted, inserted.childAt(placement)?.id)
        }
        is WidgetDrag.Existing -> {
          val node = document.node(item.id) ?: return false
          val name = canvas.views(node.id).firstOrNull()?.name ?: node.tag
          if (parent == null || !canvas.canInsert(parent, name, node)) return false
          edit(document.move(node, parent, placement.index), node.id)
        }
      }
      if (edited) showPanel(null)
      edited
    } catch (error: Exception) {
      showEditingError(error.message)
      false
    }
  }

  private fun allowEdit(): Boolean {
    if (properties?.hasDraft != true) return true
    flashError(R.string.designer_finish_property)
    return false
  }

  private fun hideKeyboard() {
    currentFocus?.let { getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(it.windowToken, 0) }
  }

  private fun sizePanel() {
    val target = panel ?: return
    if (binding.canvasArea.width == 0) return
    val density = resources.displayMetrics.density
    val width = minOf((360 * density).roundToInt(), binding.canvasArea.width * 4 / 5)
    val params = binding.panel.layoutParams as FrameLayout.LayoutParams
    val gravity = Gravity.TOP or if (target.start) Gravity.START else Gravity.END
    if (params.width == width && params.gravity == gravity) return
    params.width = width
    params.gravity = gravity
    binding.panel.layoutParams = params
  }

  internal fun refreshWorkspace() {
    if (!::undo.isInitialized) return
    binding.toolbarTitle.text = getString(if (model.dirty) R.string.designer_dirty_title else R.string.designer_title, model.file.name)
    undo.isEnabled = model.canUndo
    redo.isEnabled = model.canRedo
    apply.isEnabled = model.dirty && workspace?.ready == true
    refreshPanel()
  }

  internal fun edit(document: LayoutDocument, selectedId: Long?): Boolean {
    if (model.current.document.xml == document.xml) return false
    if (document.xml.length > MAX_XML_LENGTH) {
      flashError(R.string.designer_xml_too_large)
      return false
    }
    if (workspace?.render(document) != true) return false
    model.commit(document, selectedId)
    return true
  }

  private fun replay(redo: Boolean) {
    if (!allowEdit()) return
    val target = model.historyTarget(redo) ?: return
    if (workspace?.render(target.document) == true) model.replay(redo)
  }

  private fun applyChanges() {
    if (!model.dirty || workspace?.ready != true || !allowEdit()) return
    finishWith(next = null, apply = true)
  }

  /** 写回与打开下一个文件都由编辑器完成：它持有源缓冲并负责指纹校验。 */
  private fun finishWith(next: File?, apply: Boolean) {
    val data = Intent().putExtra(EXTRA_FILE, model.file.absolutePath)
    if (apply) {
      data.putExtra(RESULT_GENERATED_XML, model.current.document.xml)
      data.putExtra(RESULT_SOURCE_FINGERPRINT, sourceFingerprint(model.originalXml))
      data.putExtra(RESULT_DISK_FINGERPRINT, model.diskFingerprint)
    }
    next?.let { data.putExtra(RESULT_NEXT_FILE, it.absolutePath) }
    setResult(RESULT_OK, data)
    finish()
  }

  internal fun showEditingError(detail: String?) {
    flashError(getString(R.string.designer_preview_error, detail.orEmpty()))
  }

  companion object {
    const val EXTRA_FILE = "layout_file"
    const val EXTRA_XML = "ide.uidesigner.sourceXml"
    const val RESULT_GENERATED_XML = "ide.uidesigner.generatedXml"
    const val RESULT_SOURCE_FINGERPRINT = "ide.uidesigner.sourceFingerprint"
    const val RESULT_DISK_FINGERPRINT = "ide.uidesigner.diskFingerprint"
    const val RESULT_NEXT_FILE = "ide.uidesigner.nextFile"
    // 保证 Intent 字符串和其余 Activity 状态留在 Binder 的事务上限之内。
    const val MAX_XML_LENGTH = 200_000
    private const val STATE_PANEL = "designer_panel"

    fun sourceFingerprint(xml: String): String =
        MessageDigest.getInstance("SHA-256").digest(xml.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
  }
}
