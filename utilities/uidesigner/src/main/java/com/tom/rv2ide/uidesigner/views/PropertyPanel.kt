/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.views

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.getSystemService
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.inflater.internal.AttributeImpl
import com.tom.rv2ide.inflater.internal.NamespaceImpl
import com.tom.rv2ide.inflater.internal.ViewImpl
import com.tom.rv2ide.inflater.viewAdapter
import com.tom.rv2ide.uidesigner.R
import com.tom.rv2ide.uidesigner.fragments.DesignerWorkspaceFragment
import com.tom.rv2ide.uidesigner.models.AttrKey
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.uidesigner.utils.AttrNameSource
import com.tom.rv2ide.uidesigner.utils.AttrSchema
import com.tom.rv2ide.uidesigner.utils.ValueCompletionProvider
import com.tom.rv2ide.uidesigner.utils.ValueSuggestion
import com.tom.rv2ide.uidesigner.utils.Widgets
import com.tom.rv2ide.uidesigner.utils.duplicateIdOwners
import com.tom.rv2ide.uidesigner.utils.isIdAttr
import com.tom.rv2ide.uidesigner.utils.matchingAttrNames
import com.tom.rv2ide.uidesigner.utils.resolveAttrName
import com.tom.rv2ide.uidesigner.viewmodel.WorkspaceSnapshot
import java.io.File
import java.util.concurrent.Future
import kotlin.math.roundToInt

/**
 * 每个属性一个输入框，失焦或按「完成」提交。同一时刻只有一个草稿；
 * 草稿只预览到真实 View，提交前不进入源文档、选中态或撤销栈。
 */
internal class PropertyPanel(
    context: Context,
    private val file: File,
    private val canvas: () -> DesignerWorkspaceFragment?,
    private val commit: (LayoutDocument, Long?) -> Boolean,
) : LinearLayout(context) {
  private enum class Section(val title: Int) {
    BASIC(R.string.designer_property_basic), EXTENDED(R.string.designer_property_extended),
    LAYOUT(R.string.designer_property_layout), OTHER(R.string.designer_property_other),
  }

  private class Row(val value: String?, val removable: Boolean, val field: TextInputLayout, val input: MaterialAutoCompleteTextView)

  private class Draft(val key: AttrKey, val node: LayoutDocument.Node, val row: Row) {
    var previewTouched = false
    var completion: ValueCompletionProvider? = null
    // 补全线程查到属性定义后回填；查到之前按内置规则校验。
    var schema: AttrSchema? = null
  }

  private val heading = MaterialTextView(context).apply {
    setTextAppearance(R.style.TextAppearance_Material3_BodyMedium)
    gravity = Gravity.END
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.MIDDLE
  }
  private val rows = LinearLayout(context).apply {
    orientation = VERTICAL
    setBackgroundResource(R.drawable.designer_card)
    setPadding(dp(8), dp(8), dp(8), dp(8))
  }
  private val expanded = mutableSetOf(Section.BASIC)
  private val fields = mutableMapOf<AttrKey, Row>()
  private var state: WorkspaceSnapshot? = null
  private var ready = false
  private var draft: Draft? = null
  // 焦点按属性记录：提交会重建整组输入框，重建后据此把焦点放回原属性。
  private var focusKey: AttrKey? = null
  private var rebuilding = false
  // 提交一个分组会重建整个面板；保留同一控件其他分组尚未确认的输入。
  private class NewProperty(val group: Section, var name: String = "", var value: String = "")
  private val additions = mutableListOf<NewProperty>()

  /** 只有改过值或停在错误状态的草稿才需要用户先处理。 */
  val hasDraft: Boolean
    get() = draft?.let { it.row.input.text.toString() != it.row.value.orEmpty() || it.row.field.error != null } == true

  init {
    orientation = VERTICAL
    // 承接 clearFocus 后的焦点，避免落到第一个输入框上。
    isFocusableInTouchMode = true
    setPadding(dp(8), 0, dp(8), dp(8))
    addView(LinearLayout(context).apply {
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dp(8), 0, dp(8))
      addView(MaterialTextView(context, null, 0, R.style.DesignerSectionLabel).apply {
        setText(R.string.designer_property_config)
      })
      addView(heading, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
    }, LayoutParams(-1, -2))
    addView(ScrollView(context).apply { addView(rows) }, LayoutParams(-1, 0, 1f))
  }

  fun bind(snapshot: WorkspaceSnapshot) {
    val previewReady = canvas()?.ready == true
    if (state == snapshot && ready == previewReady) return
    ready = previewReady
    if (state?.selectedId != snapshot.selectedId) {
      focusKey = null
      additions.clear()
    }
    dismissDraft(restore = state?.document === snapshot.document)
    state = snapshot
    populate()
  }

  fun cancelDraft(): Boolean {
    if (draft == null) return false
    dismissDraft(restore = true)
    return true
  }

  private fun populate() {
    rebuilding = true
    rows.removeAllViews()
    fields.clear()
    rebuilding = false
    val snapshot = state ?: return
    val node = snapshot.document.node(snapshot.selectedId)
    heading.text = node?.let { context.getString(R.string.designer_selected, Widgets.displayLabel(context, it)) }
        ?: context.getString(R.string.designer_none_selected)
    if (node == null) return
    if (node.tag == "merge" || node.tag == "include" && canvas()?.views(node.id)?.size != 1) {
      rows.addView(info(R.string.designer_property_virtual))
      return
    }
    if (!snapshot.document.editable || canvas()?.ready != true) {
      rows.addView(info(R.string.designer_invalid_xml))
      return
    }
    if (canvas()?.describePreview(node) == true) rows.addView(info(R.string.designer_property_unsupported))
    val target = canvas()?.views(node.id)?.singleOrNull() as? ViewImpl
    val values = node.attrs.filterNot { (key, _) -> node.tag == "include" && key.uri == null && key.name == "layout" }
    val present = values.map { (key, _) -> key.uri to key.name }.toSet()
    val supported = if (node.tag == "include") {
      listOf("layout_width", "layout_height", "id", "visibility").map(::androidKey)
    } else {
      commonKeys + target?.viewAdapter?.supportedAttributes.orEmpty().map { attr ->
        AttrKey(attr.namespace?.uri, attr.name, attr.namespace?.prefix.orEmpty())
      } + if ((target?.view?.layoutParams?.javaClass?.name ?: "").endsWith("ConstraintLayout\$LayoutParams")) constraintKeys else emptyList()
    }
    val missing = supported.distinctBy { it.uri to it.name }.filter { it.uri to it.name !in present }.sortedBy { it.name }

    // 布局分组保留常用的尺寸入口，未设置时也能直接编辑。
    val dimensions = commonKeys.take(2).filter { it.uri to it.name !in present }
    val sections = (values + dimensions.map { it to null }).groupBy { (key, _) -> section(key) }
    for (group in Section.entries) {
      val entries = sections[group].orEmpty()
      val content = LinearLayout(context).apply {
        orientation = VERTICAL
        isVisible = group in expanded
      }
      fun appendEditor(entry: NewProperty, focus: Boolean = false) {
        content.addView(addRow(node, entry, missing, present, target.takeUnless { node.tag == "include" }, focus),
            LayoutParams(-1, -2).apply { topMargin = dp(6) })
      }
      val plus = AppCompatImageButton(context, null, R.attr.borderlessButtonStyle).apply {
        setImageResource(R.drawable.ic_add)
        setColorFilter(MaterialColors.getColor(this, R.attr.colorPrimary))
        val inset = dp(12)
        setPadding(inset, inset, inset, inset)
        contentDescription = context.getString(R.string.designer_add_to, context.getString(group.title))
        tooltipText = contentDescription
        isEnabled = node.tag != "include" || missing.any { section(it) == group }
        setOnClickListener {
          val entry = NewProperty(group)
          additions.add(entry)
          appendEditor(entry, focus = true)
        }
      }
      fun MaterialButton.showExpansion() {
        isSelected = content.isVisible
        plus.isVisible = isSelected
        setIconResource(if (isSelected) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
      }
      val title = MaterialButton(context, null, R.attr.borderlessButtonStyle).apply {
        setText(group.title)
        isAllCaps = false
        setTextAppearance(R.style.TextAppearance_Material3_TitleMedium)
        setTextColor(MaterialColors.getColor(this, R.attr.colorOnSurface))
        iconTint = ColorStateList.valueOf(MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        iconGravity = MaterialButton.ICON_GRAVITY_START
        iconPadding = dp(20)
        minHeight = dp(56)
        cornerRadius = 0
        showExpansion()
        setOnClickListener {
          if (!expanded.add(group)) {
            if (content.hasFocus()) hideKeyboard(content)
            expanded.remove(group)
          }
          content.isVisible = group in expanded
          showExpansion()
          if (!content.isVisible && draft?.let { fields[it.key]?.field?.parent } === content) cancelDraft()
        }
      }
      rows.addView(LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LayoutParams(0, -2, 1f))
        addView(plus, LayoutParams(dp(48), dp(48)))
      })
      rows.addView(content)
      entries.forEach { (key, value) ->
        val attribute = AttributeImpl(key.uri?.let { NamespaceImpl(key.prefix, it) }, key.name, value.orEmpty())
        val removable = value != null && key.name !in sizeNames && target?.viewAdapter?.isRequiredAttribute(attribute) != true
        content.addView(propertyRow(key, value, removable), LayoutParams(-1, -2).apply { topMargin = dp(6) })
      }
      additions.filter { it.group == group }.forEach { appendEditor(it) }
    }
    focusKey?.let { fields[it]?.input?.requestFocus() }
  }

  /**
   * [initial] 是同步可得的候选，先给下拉用；[target] 不为空时再按真实类继承链查 styleable，
   * 查到后并入同一份名单（解析手输名称也用它），已设置在节点上的属性（[present]）不重复列出。
   */
  private fun addRow(
      node: LayoutDocument.Node,
      entry: NewProperty,
      initial: List<AttrKey>,
      present: Set<Pair<String?, String>>,
      target: ViewImpl?,
      focus: Boolean,
  ): LinearLayout {
    var missing = initial
    val document = checkNotNull(state).document
    val name = SuggestionInput(context).apply {
      imeOptions = EditorInfo.IME_ACTION_NEXT
      setText(entry.name, false)
    }
    val value = SuggestionInput(context).apply {
      imeOptions = EditorInfo.IME_ACTION_DONE
      setText(entry.value, false)
    }
    val nameField = outlined(context.getString(R.string.designer_property_name), name)
    val valueField = outlined(context.getString(R.string.designer_property_value), value)
    var namesTask: Future<*>? = null
    var addCompletion: ValueCompletionProvider? = null
    var activeKey: AttrKey? = null
    var schema: AttrSchema? = null

    fun currentKey() = resolveAttrName(missing, entry.name, node.element::getNamespaceURI)
    fun filterNames() {
      if (!name.hasFocus() || name.isPerformingCompletion) return
      val matches = if (entry.name.isBlank()) emptyList() else matchingAttrNames(missing, entry.name, node.element::getNamespaceURI)
      suggest(name, matches.filter { section(it) == entry.group }.map { ValueSuggestion(it.qualifiedName) })
    }
    fun validate(key: AttrKey, text: String, submitting: Boolean = false): Boolean {
      val valid = if (text.isBlank()) !submitting else validPropertyValue(key, text, schema)
      valueField.error = if (valid) null else context.getString(R.string.designer_property_invalid, key.qualifiedName)
      valueField.helperText = if (valid) duplicateIdHint(node, key, text) else null
      return valid
    }
    // 只在值框获焦、输入或提交时绑定；编辑名称时不为每个半截名字查表。
    fun bindKey(): AttrKey? {
      val key = currentKey()
      if (key == activeKey) return key
      addCompletion?.close()
      activeKey = key
      schema = null
      addCompletion = key?.let {
        completionFor(node, it, document, onSchema = { format ->
          schema = format
          validate(it, value.text.toString())
        }) { result -> if (value.hasFocus()) suggest(value, result) }
      }
      return key
    }
    // 只有用户实际编辑该栏时才读取完整候选；其它未确认的栏不抢焦点或下拉框。
    fun loadNames() {
      if (target == null || namesTask != null) return
      val viewClass = target.view.javaClass
      val parentClass = if (node.parentPath == null) null else target.parent?.view?.javaClass
      namesTask = ValueCompletionProvider.withLookup(file, { AttrNameSource.namesFor(viewClass, parentClass) }) { found ->
        if (!name.isAttachedToWindow) return@withLookup
        missing = (initial + found).distinctBy { it.uri to it.name }.filterNot { it.uri to it.name in present }.sortedBy { it.name }
        filterNames()
        if (value.hasFocus() && currentKey() != activeKey) {
          bindKey()
          addCompletion?.request(entry.value, 0)
        }
      }
    }
    name.setOnFocusChangeListener { _, focused ->
      if (focused) {
        loadNames()
        filterNames()
      }
    }
    name.doAfterTextChanged {
      entry.name = it.toString()
      addCompletion?.close()
      addCompletion = null
      activeKey = null
      schema = null
      nameField.error = null
      valueField.error = null
      valueField.helperText = null
      suggest(value, emptyList())
      filterNames()
    }
    name.setOnItemClickListener { _, _, _, _ -> value.requestFocus() }
    value.setOnFocusChangeListener { _, focused ->
      if (focused && !rebuilding) {
        loadNames()
        if (bindKey() != null) addCompletion?.request(entry.value, 0)
      }
    }
    value.doAfterTextChanged {
      entry.value = it.toString()
      if (!value.hasFocus()) return@doAfterTextChanged
      val key = bindKey() ?: return@doAfterTextChanged
      validate(key, entry.value)
      if (!value.isPerformingCompletion) addCompletion?.request(entry.value)
    }
    value.setOnItemClickListener { _, _, _, _ -> addCompletion?.close() }

    fun add() {
      val key = bindKey()
      if (key == null || (key.uri to key.name) in present ||
          node.tag == "include" && initial.none { it.uri == key.uri && it.name == key.name }) {
        nameField.error = context.getString(if (key != null && (key.uri to key.name) in present)
          R.string.designer_property_exists else R.string.designer_property_unknown)
        return
      }
      val owner = section(key)
      if (owner != entry.group) {
        nameField.error = context.getString(R.string.designer_property_wrong_section, context.getString(owner.title))
        return
      }
      val text = entry.value
      fun submit(format: AttrSchema?) {
        if (!value.isShown || currentKey() != key || entry.value != text) return
        schema = format
        if (!validate(key, text, submitting = true)) return
        val current = draft
        if (current != null && !finish(current)) return
        // 写入会同步重建分组：先取走这一栏，其它未确认的栏原样保留。
        if (state?.document !== document || !additions.remove(entry)) return
        hideKeyboard(value)
        if (!write(node, key, text, valueField)) additions.add(entry)
      }
      val provider = addCompletion
      if (provider == null) submit(null) else provider.withSchema(::submit)
    }
    value.setOnEditorActionListener { _, action, event -> isDone(action, event).also { if (it) add() } }
    return object : LinearLayout(context) {
      init {
        orientation = VERTICAL
        // 错误显示在输入框下方，不让 Material 的错误图标替换取消和确认按钮。
        nameField.errorIconDrawable = null
        valueField.errorIconDrawable = null
        nameField.endIconMode = TextInputLayout.END_ICON_CUSTOM
        nameField.setEndIconDrawable(R.drawable.ic_close)
        nameField.endIconContentDescription = context.getString(R.string.cancel)
        nameField.setEndIconOnClickListener {
          if (hasFocus()) hideKeyboard(this)
          additions.remove(entry)
          (parent as LinearLayout).removeView(this)
        }
        valueField.endIconMode = TextInputLayout.END_ICON_CUSTOM
        valueField.setEndIconDrawable(R.drawable.ic_check)
        valueField.endIconContentDescription = context.getString(R.string.designer_property_add)
        valueField.setEndIconOnClickListener { add() }
        addView(nameField, LayoutParams(-1, -2))
        addView(valueField, LayoutParams(-1, -2))
        if (focus) doOnLayout {
          if (name.requestFocus()) context.getSystemService<InputMethodManager>()?.showSoftInput(name, 0)
        }
      }

      override fun onDetachedFromWindow() {
        namesTask?.cancel(false)
        namesTask = null
        addCompletion?.close()
        super.onDetachedFromWindow()
      }
    }
  }

  private fun propertyRow(key: AttrKey, value: String?, removable: Boolean): TextInputLayout {
    val input = SuggestionInput(context).apply {
      imeOptions = EditorInfo.IME_ACTION_DONE
      setText(value.orEmpty(), false)
    }
    val field = outlined(label(key), input).apply {
      placeholderText = if (value == null) context.getString(R.string.designer_property_missing) else null
      endIconMode = TextInputLayout.END_ICON_CUSTOM
      setEndIconDrawable(R.drawable.ic_more_vert)
      endIconContentDescription = context.getString(R.string.designer_property_more)
      setEndIconOnClickListener { showMenu(it, key, removable) }
    }
    val choices = options(key)
    if (choices.isNotEmpty()) input.setAdapter(SuggestionAdapter(context, choices.map { ValueSuggestion(it) }))
    // 已获焦时再点一次也能重新打开候选。
    input.setOnClickListener { if (input.adapter?.isEmpty == false) input.showDropDown() }
    input.setOnFocusChangeListener { _, focused ->
      if (rebuilding) return@setOnFocusChangeListener
      if (focused) focusKey = key else if (focusKey == key) focusKey = null
      // 延后到焦点交接完成：提交会重建输入框，不能在另一个输入框获焦的途中进行。
      post { activate(focusKey) }
    }
    input.setOnEditorActionListener { _, action, event ->
      if (!isDone(action, event)) return@setOnEditorActionListener false
      hideKeyboard(input)
      input.clearFocus()
      true
    }
    input.doAfterTextChanged { text ->
      val active = draft?.takeIf { it.row.input === input } ?: return@doAfterTextChanged
      val value = text.toString()
      updateDraftPreview(active, value)
      if (!input.isPerformingCompletion) active.completion?.request(value)
    }
    fields[key] = Row(value, removable, field, input)
    return field
  }

  /** 收尾当前草稿，再为获焦的属性开草稿；收尾失败（值无效）时保留原草稿。 */
  private fun activate(target: AttrKey?) {
    val current = draft
    if (current != null && current.key == target) return
    if (current != null && !finish(current)) return
    val row = target?.let(fields::get) ?: return
    if (!row.input.hasFocus()) return
    startDraft(target, row)
  }

  private fun startDraft(key: AttrKey, row: Row) {
    val snapshot = state ?: return
    val node = snapshot.document.node(snapshot.selectedId) ?: return
    val active = Draft(key, node, row)
    draft = active
    row.input.setOnItemClickListener { _, _, _, _ ->
      // 选中候选后不再立刻弹出下一轮补全；flag 想继续追加时再输入「|」即可。
      active.completion?.close()
      row.input.dismissDropDown()
    }
    active.completion = completionFor(node, key, snapshot.document, onSchema = { schema ->
      if (draft === active) {
        active.schema = schema
        // 格式到达前可能已按内置规则误判，按新格式重新校验一次当前输入。
        if (row.input.text.toString() != row.value.orEmpty()) updateDraftPreview(active, row.input.text.toString())
      }
    }) { result ->
      if (draft === active) suggest(row.input, result)
    }
    // 获焦即给出候选（枚举、布尔、已有 id 等），不必先敲一个字符。
    active.completion?.request(row.input.text.toString(), delayMs = 0)
  }

  /** 补全结果为空时退回属性格式里的候选，再退回内置的少量候选（没有项目模块时宽高、可见性等仍有下拉）。 */
  private fun completionFor(
      node: LayoutDocument.Node,
      key: AttrKey,
      document: LayoutDocument,
      onSchema: (AttrSchema?) -> Unit = {},
      onResult: (List<ValueSuggestion>) -> Unit,
  ): ValueCompletionProvider? {
    val target = canvas()?.views(node.id)?.singleOrNull() as? ViewImpl ?: return null
    var schema: AttrSchema? = null
    return ValueCompletionProvider(file, target, key, document, node.id, onSchema = {
      schema = it
      onSchema(it)
    }) { result ->
      onResult(result.ifEmpty { (schema?.choices?.takeIf { it.isNotEmpty() } ?: options(key)).map { ValueSuggestion(it) } })
    }
  }

  private fun updateDraftPreview(active: Draft, value: String) {
    val field = active.row.field
    if (!validPropertyValue(active.key, value, active.schema)) {
      field.error = context.getString(R.string.designer_property_invalid, active.key.qualifiedName)
      field.helperText = null
    } else {
      field.error = null
      active.previewTouched = true
      val applied = canvas()?.previewAttribute(active.node.id, active.key, value) == true
      field.helperText = duplicateIdHint(active.node, active.key, value)
          ?: if (applied) null else context.getString(R.string.designer_property_unsupported)
    }
  }

  /** 重名只提示不拦截：Android 允许重名，但相对布局和约束引用会指向第一个同名控件。 */
  private fun duplicateIdHint(node: LayoutDocument.Node, key: AttrKey, value: String): String? {
    if (!isIdAttr(key)) return null
    val document = state?.document ?: return null
    val owner = duplicateIdOwners(document, node.id, value).firstOrNull() ?: return null
    return context.getString(R.string.designer_property_duplicate_id, Widgets.displayLabel(context, owner))
  }

  private fun suggest(input: MaterialAutoCompleteTextView, values: List<ValueSuggestion>) {
    input.setAdapter(SuggestionAdapter(context, values))
    if (input.hasFocus() && values.isNotEmpty() && input.isShown && input.windowToken != null) input.showDropDown()
    else input.dismissDropDown()
  }

  /** 值未变直接丢弃；清空视为移除；成功提交会触发整组重建。 */
  private fun finish(active: Draft): Boolean {
    val row = active.row
    val text = row.input.text.toString()
    if (text == row.value.orEmpty()) {
      dismissDraft(restore = true)
      return true
    }
    val value = text.takeUnless { it.isBlank() }
    if (value == null && row.value == null) {
      dismissDraft(restore = true)
      return true
    }
    val provider = active.completion
    if (value != null && provider != null && !provider.schemaReady) {
      provider.withSchema {
        if (draft === active && row.input.text.toString() == text && !row.input.hasFocus()) activate(focusKey)
      }
      return false
    }
    if (value == null && !row.removable || value != null && !validPropertyValue(active.key, value, active.schema)) {
      row.field.error = context.getString(R.string.designer_property_invalid, active.key.qualifiedName)
      return false
    }
    release(active)
    if (write(active.node, active.key, value, row.field)) return true
    draft = active
    return false
  }

  private fun write(node: LayoutDocument.Node, key: AttrKey, value: String?, field: TextInputLayout): Boolean {
    val document = state?.document ?: return false
    val candidate = try {
      document.attribute(node, key, value)
    } catch (_: IllegalArgumentException) {
      field.error = context.getString(R.string.designer_property_invalid, key.qualifiedName)
      return false
    }
    if (candidate === document) {
      canvas()?.restorePreview()
      return true
    }
    if (commit(candidate, node.id)) return true
    field.error = context.getString(R.string.designer_invalid_xml)
    return false
  }

  private fun release(active: Draft) {
    if (draft === active) draft = null
    active.completion?.close()
    active.row.input.dismissDropDown()
  }

  private fun dismissDraft(restore: Boolean) {
    val last = draft ?: return
    release(last)
    if (!restore) return
    last.row.field.error = null
    last.row.field.helperText = null
    last.row.input.setText(last.row.value.orEmpty(), false)
    if (last.previewTouched) canvas()?.restorePreview()
  }

  private fun showMenu(anchor: View, key: AttrKey, removable: Boolean) {
    PopupMenu(context, anchor).apply {
      if (removable) menu.add(0, MENU_REMOVE, 0, R.string.designer_property_remove)
      menu.add(0, MENU_RESET, 1, R.string.designer_property_reset).isEnabled = draft?.key == key
      menu.add(0, MENU_RESOURCE, 2, R.string.designer_property_pick_resource)
      setOnMenuItemClickListener { item ->
        when (item.itemId) {
          MENU_REMOVE -> remove(key)
          MENU_RESET -> fields[key]?.input?.let { input ->
            cancelDraft()
            hideKeyboard(input)
            input.clearFocus()
          }
          MENU_RESOURCE -> fields[key]?.input?.let { input ->
            input.requestFocus()
            context.getSystemService<InputMethodManager>()?.showSoftInput(input, 0)
            // 排在获焦触发的草稿之后，「@」才会走资源补全。
            post {
              val current = fields[key]?.input?.takeIf { draft?.key == key } ?: return@post
              current.setText("@")
              current.setSelection(1)
            }
          }
        }
        true
      }
    }.show()
  }

  private fun remove(key: AttrKey) {
    val current = draft
    if (current != null && current.key != key && !finish(current)) return
    draft?.let(::release)
    val node = state?.let { it.document.node(it.selectedId) } ?: return
    val row = fields[key] ?: return
    hideKeyboard(row.input)
    write(node, key, null, row.field)
  }

  private fun label(key: AttrKey): String {
    val name = if (key.uri == LayoutDocument.ANDROID) key.name else key.qualifiedName
    val description = descriptions[key.name]?.takeIf { key.uri == LayoutDocument.ANDROID }?.let(context::getString)
    return if (description == null) name else "$name - $description"
  }

  private fun section(key: AttrKey): Section = when {
    key.name.startsWith("layout_") && key.uri in setOf(LayoutDocument.ANDROID, LayoutDocument.APP) -> Section.LAYOUT
    key.uri == LayoutDocument.APP -> Section.EXTENDED
    key.uri == LayoutDocument.ANDROID && key.name in basicNames -> Section.BASIC
    else -> Section.OTHER
  }

  // 描边样式只能在构造时套用，事后改 boxBackgroundMode 仍是下划线外观。
  private fun outlined(label: String, input: MaterialAutoCompleteTextView) =
      TextInputLayout(context, null, MaterialR.attr.textInputOutlinedStyle).apply {
    val radius = dp(12).toFloat()
    setBoxCornerRadii(radius, radius, radius, radius)
    hint = label
    addView(input, LayoutParams(-1, -2))
  }

  private fun info(label: Int) = MaterialTextView(context).apply {
    setText(label)
    setTextAppearance(R.style.TextAppearance_Material3_BodySmall)
    setTextColor(MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant))
    setPadding(dp(8), dp(8), dp(8), dp(8))
  }

  /** 软键盘「完成」给 IME_ACTION_DONE；物理/蓝牙键盘回车给 IME_NULL，只认按下那一次。 */
  private fun isDone(action: Int, event: KeyEvent?) = action == EditorInfo.IME_ACTION_DONE ||
      action == EditorInfo.IME_NULL && event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN

  private fun hideKeyboard(view: View) {
    context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(view.windowToken, 0)
  }

  private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

  private companion object {
    const val MENU_REMOVE = 1
    const val MENU_RESET = 2
    const val MENU_RESOURCE = 3
    fun androidKey(name: String) = AttrKey(LayoutDocument.ANDROID, name, "android")
    val commonKeys = listOf("layout_width", "layout_height", "id", "visibility", "padding",
        "layout_margin", "layout_marginStart", "layout_marginTop", "layout_marginEnd", "layout_marginBottom").map(::androidKey)
    val constraintKeys = listOf("Start_toStartOf", "Start_toEndOf", "End_toStartOf", "End_toEndOf",
        "Top_toTopOf", "Top_toBottomOf", "Bottom_toTopOf", "Bottom_toBottomOf").map {
      AttrKey(LayoutDocument.APP, "layout_constraint$it", "app")
    }
    val basicNames = setOf("id", "text", "hint", "contentDescription", "visibility", "enabled", "background", "orientation")
    val descriptions = mapOf(
        "id" to R.string.designer_attr_id,
        "layout_width" to R.string.designer_attr_layout_width,
        "layout_height" to R.string.designer_attr_layout_height,
        "text" to R.string.designer_attr_text,
        "hint" to R.string.designer_attr_hint,
        "textSize" to R.string.designer_attr_text_size,
        "textColor" to R.string.designer_attr_text_color,
        "background" to R.string.designer_attr_background,
        "padding" to R.string.designer_attr_padding,
        "layout_margin" to R.string.designer_attr_layout_margin,
        "orientation" to R.string.designer_attr_orientation,
        "visibility" to R.string.designer_attr_visibility,
        "gravity" to R.string.designer_attr_gravity,
        "layout_gravity" to R.string.designer_attr_layout_gravity,
        "layout_weight" to R.string.designer_attr_layout_weight,
        "src" to R.string.designer_attr_src,
        "contentDescription" to R.string.designer_attr_content_description,
        "enabled" to R.string.designer_attr_enabled,
    )
  }
}

private val sizeNames = setOf("layout_width", "layout_height")
private val boolNames = setOf("enabled", "checked", "clickable", "focusable", "singleLine", "clipChildren", "clipToPadding", "fillViewport")
private val unitValue = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:dp|dip|sp|px|in|mm|pt)")
private val resourceValue = Regex("[@?][a-zA-Z0-9_+.:/-]+")
private val idPattern = Regex("@\\+?(?:[a-zA-Z0-9_.]+:)?id/[a-zA-Z_][a-zA-Z0-9_]*")

private fun options(key: AttrKey): List<String> = if (key.uri != LayoutDocument.ANDROID) emptyList() else when (key.name) {
  in sizeNames -> listOf("wrap_content", "match_parent", "0dp")
  "orientation" -> listOf("vertical", "horizontal")
  "visibility" -> listOf("visible", "invisible", "gone")
  in boolNames -> listOf("true", "false")
  else -> emptyList()
}

/**
 * [schema] 是属性定义的格式；为 null（没有项目模块、查不到定义或尚未查完）时退回内置名单。
 * 资源/主题引用对任何格式都放行，与 aapt2 一致。
 */
internal fun validPropertyValue(key: AttrKey, input: String, schema: AttrSchema? = null): Boolean {
  if (input.any { it < ' ' && it != '\t' && it != '\n' && it != '\r' }) return false
  val value = input.trim()
  if (value.startsWith("@{") || value.startsWith("@={")) return value.endsWith('}')
  if (key.uri == LayoutDocument.ANDROID && key.name == "id") return idPattern.matches(value)
  if (schema != null) {
    if (resourceValue.matches(value)) return true
    // 宽高写 0 与内置规则保持一致；宽高不接受负尺寸。
    if (key.uri == LayoutDocument.ANDROID && key.name in sizeNames) {
      if (value == "0") return true
      if (value.startsWith('-')) return false
    }
    return schema.acceptsLiteral(value)
  }
  if (key.uri != LayoutDocument.ANDROID) return true
  val name = key.name
  val choices = options(key)
  val dimension = name in sizeNames || name.startsWith("padding") || name.startsWith("layout_margin") || name in setOf("textSize", "elevation")
  if (!dimension && choices.isEmpty()) return true
  if (resourceValue.matches(value) || value in choices) return true
  return dimension && (value == "0" || unitValue.matches(value) &&
      (name.startsWith("layout_margin") || !value.startsWith('-')))
}
