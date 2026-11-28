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

package com.tom.rv2ide.inflater.internal

import android.view.View
import android.view.ViewGroup
import com.android.SdkConstants.EXT_XML
import com.android.aapt.Resources.SourcePosition
import com.android.aapt.Resources.XmlAttribute
import com.android.aapt.Resources.XmlElement
import com.android.aapt.Resources.XmlNode.NodeCase.ELEMENT
import com.android.aaptcompiler.AaptResourceType.ID
import com.android.aaptcompiler.AaptResourceType.LAYOUT
import com.android.aaptcompiler.XmlProcessor
import com.tom.rv2ide.inflater.DefaultComponentFactory
import com.tom.rv2ide.inflater.IAttribute
import com.tom.rv2ide.inflater.IComponentFactory
import com.tom.rv2ide.inflater.IComponentFactory.Companion.LAYOUT_INFLATER_COMPONENT_FACTORY_KEY
import com.tom.rv2ide.inflater.ILayoutInflater
import com.tom.rv2ide.inflater.INamespace
import com.tom.rv2ide.inflater.IViewAdapterIndex
import com.tom.rv2ide.inflater.IView
import com.tom.rv2ide.inflater.IViewGroup
import com.tom.rv2ide.inflater.InflateException
import com.tom.rv2ide.inflater.events.IInflateEventsListener
import com.tom.rv2ide.inflater.events.InflationFinishEvent
import com.tom.rv2ide.inflater.events.InflationStartEvent
import com.tom.rv2ide.inflater.events.OnApplyAttributeEvent
import com.tom.rv2ide.inflater.events.OnInflateViewEvent
import com.tom.rv2ide.inflater.internal.utils.IDTable
import com.tom.rv2ide.inflater.internal.utils.ViewFactory.createViewInstance
import com.tom.rv2ide.inflater.internal.utils.ViewFactory.generateLayoutParams
import com.tom.rv2ide.inflater.internal.utils.parseLayoutReference
import com.tom.rv2ide.inflater.utils.endParse
import com.tom.rv2ide.inflater.utils.isParsing
import com.tom.rv2ide.inflater.utils.startParse
import com.tom.rv2ide.inflater.viewAdapter
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.xml.widgets.Widget
import com.tom.rv2ide.xml.widgets.WidgetTable
import com.tom.rv2ide.xml.widgets.WidgetType
import java.io.File

/**
 * Default implementation of [ILayoutInflater].
 *
 * @author Akash Yadav
 */
open class LayoutInflaterImpl : ILayoutInflater {

  override var inflationEventListener: IInflateEventsListener? = null
  override var module: AndroidModule? = null
  private var manuallyStartedParse = false
  private var _primaryInflatingFile: File? = null
  private var _currentLayoutFile: LayoutFile? = null
  private var sourceNodeListener: ((List<Int>, List<IView>) -> Unit)? = null
  private var sourcePath: List<Int>? = null
  private val includeStack = mutableSetOf<File>()
  private val styles = mutableMapOf<String, com.tom.rv2ide.inflater.internal.utils.PreviewStyle>()

  private fun styleOf(element: XmlElement): com.tom.rv2ide.inflater.internal.utils.PreviewStyle {
    val ref = element.attributeList.firstOrNull { it.name == "style" && it.namespaceUri.isNullOrEmpty() }?.value
        ?: return com.tom.rv2ide.inflater.internal.utils.PreviewStyle.EMPTY
    return styles.getOrPut(ref) { com.tom.rv2ide.inflater.internal.utils.resolvePreviewStyle(ref) }
  }

  override var componentFactory: IComponentFactory = DefaultComponentFactory()
    set(value) {
      field = value
      Lookup.getDefault().update(LAYOUT_INFLATER_COMPONENT_FACTORY_KEY, value)
    }

  protected val primaryInflatingFile: File
    get() = this._primaryInflatingFile!!

  protected val currentLayoutFile: LayoutFile
    get() = this._currentLayoutFile!!

  protected val tracksSourceNodes: Boolean
    get() = sourceNodeListener != null

  override fun inflate(file: File, parent: ViewGroup): List<IView> {
    startInflation(file)
    return doInflate(file, parent).apply { finishInflation() }
  }

  override fun inflate(file: File, parent: IViewGroup): List<IView> {
    startInflation(file)
    return doInflate(file, parent).apply { finishInflation() }
  }

  /** 用原文件定位资源；按源元素路径报告节点，include 对应一个或多个预览视图。 */
  fun inflate(
      file: File,
      xml: String,
      parent: IViewGroup,
      onSourceNode: (List<Int>, List<IView>) -> Unit,
  ): List<IView> {
    sourceNodeListener = onSourceNode
    sourcePath = emptyList()
    includeStack.add(file.canonicalFile)
    return try {
      startInflation(file)
      val (processor, module) = com.tom.rv2ide.inflater.utils.processXmlFile(file, LAYOUT, xml)
      doInflate(processor, parent, module).apply { finishInflation() }
    } finally {
      sourceNodeListener = null
      sourcePath = null
      includeStack.clear()
    }
  }

  override fun close() {
    this.module = null
    this.inflationEventListener = null
    this._primaryInflatingFile = null
    this._currentLayoutFile = null

    if (manuallyStartedParse) {
      // end the pare only if we called startParse()
      endParse()
    }
  }

  protected open fun doInflate(file: File, parent: ViewGroup): List<IView> {
    val (processor, module) = processXmlFile(file)
    return doInflate(processor, parent, module)
  }

  protected open fun doInflate(file: File, parent: IViewGroup): List<IView> {
    val (processor, module) = processXmlFile(file)
    return doInflate(processor, parent, module)
  }

  protected open fun doInflate(
      processor: XmlProcessor,
      parent: ViewGroup,
      module: AndroidModule,
  ): List<IView> {
    return doInflate(processor, module) { wrap(parent) }
  }

  protected open fun doInflate(
      processor: XmlProcessor,
      parent: IViewGroup,
      module: AndroidModule,
  ): List<IView> {
    return doInflate(processor, module) { parent }
  }

  protected open fun doInflate(
      processor: XmlProcessor,
      module: AndroidModule,
      parent: () -> IViewGroup,
  ): List<IView> {
    // TODO(itsaky) : Add test for multiple view as root layout
    //  The inflater should fail in such cases
    val (file, node) =
        processor.xmlResources.find { it.file == processor.primaryFile }
            ?: throw InflateException("Unable to find primary XML resource from XmlProcessor")

    val origin = if (sourceNodeListener != null) File(file.source.path) else primaryInflatingFile
    this._currentLayoutFile = LayoutFile(origin, file.name.entry!!)

    if (node.nodeCase != ELEMENT) {
      throw InflateException(
          "Found ${node.nodeCase} but $ELEMENT was expected at ${node.source.lineCol()}"
      )
    }

    // Store all IDs
    file.exportedSymbols
        .filter { it.name.type == ID }
        .forEach { IDTable.set(currentLayoutFile.resName, it.name.entry!!, View.generateViewId()) }

    val element = node.element

    val widgets = module.getWidgetTable() ?: throw InflateException("No widget table for $module")
    return inflateElement(element, parent(), module, widgets)
  }

  private fun inflateElement(
      element: XmlElement,
      parent: IViewGroup,
      module: AndroidModule,
      widgets: WidgetTable,
  ): List<IView> {
    val views = onCreateView(element, parent, module, widgets)
    sourcePath?.let { sourceNodeListener?.invoke(it, views) }
    return views
  }

  protected fun inflateChildren(
      element: XmlElement,
      parent: IViewGroup,
      module: AndroidModule,
      widgets: WidgetTable,
  ): List<IView> {
    val parentPath = sourcePath
    val views = mutableListOf<IView>()
    var index = 0
    try {
      for (child in element.childList) {
        if (child.nodeCase != ELEMENT) continue
        sourcePath = parentPath?.plus(index++)
        views.addAll(inflateElement(child.element, parent, module, widgets))
      }
    } finally {
      sourcePath = parentPath
    }
    return views
  }

  protected open fun onCreateView(
      element: XmlElement,
      parent: IViewGroup,
      module: AndroidModule,
      widgets: WidgetTable =
          module.getWidgetTable()
              ?: throw IllegalStateException("No widget table found for module $module"),
  ): List<IView> {

    val parentView = parent.view as ViewGroup
    if ("merge" == element.name) {
      return onCreateMergedView(element, parent, module, widgets)
    }

    if ("include" == element.name) {
      return onCreateIncludedView(element, parent)
    }

    val widget =
        if (element.name.contains('.')) widgets.getWidget(element.name)
        else widgets.findWidgetWithSimpleName(element.name)

    // 控件表只来自 Android SDK 的 `data/widgets.txt`（仅平台控件）。库控件（androidx /
    // material）不在表里，但可能已经有 ViewAdapter（经 @ViewAdapter 注册进
    // ViewAdapterIndex）——先问适配器，之后才退到占位提示。否则模板默认的
    // ConstraintLayout 在预览里会渲染成 "View with name '…' not found"，
    // 看起来像依赖缺失，实际是设计器不认识库控件。
    val view: ViewImpl =
        (if (widget != null) {
          if (widget.qualifiedName == android.widget.ProgressBar::class.java.name && styleOf(element).horizontalProgress) {
            val bar = android.widget.ProgressBar(parentView.context, null, android.R.attr.progressBarStyleHorizontal)
            componentFactory.createView(currentLayoutFile, widget.qualifiedName, bar)
          } else onCreatePlatformView(widget, parentView, module, widgets)
        } else {
          onCreateLibraryView(element.name, element.childCount > 0, parentView)
        })
            as ViewImpl

    addNamespaceDecls(element, view)
    applyAttributes(element, view, parent)

    if (element.childCount > 0 && view is IViewGroup) {
      if (sourceNodeListener == null) {
        for (child in element.childList) {
          if (child.nodeCase != ELEMENT) {
            throw InflateException("Unexpected node at ${child.source.lineCol()}")
          }
        }
      }
      inflateChildren(element, view, module, widgets)
    }

    inflationEventListener?.onEvent(OnInflateViewEvent(view))

    return listOf(view)
  }

  @JvmOverloads
  protected open fun applyAttributes(
      element: XmlElement,
      view: ViewImpl,
      parent: IViewGroup,
      attachToParent: Boolean = true,
      shouldApplyAttr: (XmlAttribute) -> Boolean = { true },
  ) {
    val parentView = parent.view as ViewGroup
    val adapter =
        view.viewAdapter
            ?: throw InflateException("No attribute adapter found for view ${view.name}")

    if (attachToParent || !tracksSourceNodes) {
      view.view.layoutParams = generateLayoutParams(parentView)
    }
    if (attachToParent) {
      parent.addChild(view)
    }

    // 容器可能把控件挂到内部子容器（如 TextInputLayout），以挂载后的参数类型为准。
    val attachedParams = view.view.layoutParams
    if (attachToParent || !tracksSourceNodes) adapter.applyBasic(view)
    if (tracksSourceNodes && attachedParams != null && attachedParams.javaClass != view.view.layoutParams?.javaClass) {
      view.view.layoutParams = attachedParams
    }

    // 样式只影响实际 View；显式属性随后覆盖，不把继承值写进布局模型。
    for ((name, value) in styleOf(element).attributes) {
      view.applyAttribute(onCreateAttribute(view, INamespace.ANDROID, name, value))
    }

    if (element.attributeCount > 0) {
      for (xmlAttribute in element.attributeList) {

        val namespace =
            if (xmlAttribute.namespaceUri.isNullOrBlank()) null
            else view.findNamespaceByUri(xmlAttribute.namespaceUri)

        val attr = onCreateAttribute(view, namespace, xmlAttribute.name, xmlAttribute.value)
        view.addAttribute(attribute = attr, apply = shouldApplyAttr(xmlAttribute), update = true)
        inflationEventListener?.onEvent(OnApplyAttributeEvent(view, attr))
      }
    }
  }

  private fun onCreateMergedView(
      element: XmlElement,
      parent: IViewGroup,
      module: AndroidModule,
      widgets: WidgetTable,
  ): List<IView> {
    val group = parent as ViewImpl
    if (sourceNodeListener == null) {
      return inflateChildren(element, parent, module, widgets).also {
        addNamespaceDecls(element, group)
      }
    }

    // merge 没有实际的 View；它的命名空间仍需覆盖子元素的解析范围。
    val namespaces = group.namespaces.toMap()
    addNamespaceDecls(element, group)
    return try {
      inflateChildren(element, parent, module, widgets)
    } finally {
      group.namespaces.clear()
      group.namespaces.putAll(namespaces)
    }
  }

  private fun onCreateIncludedView(element: XmlElement, parent: IViewGroup): List<IView> {
    val layout =
        element.attributeList.find { it.namespaceUri.isNullOrBlank() && it.name == "layout" }
            ?: throw InflateException("<include> tag must have 'layout' attribute")
    val file = parseLayoutReference(layout.value)
    if (file == null || !file.exists() || !file.isFile || file.extension != EXT_XML) {
      throw InflateException("Invalid layout file reference; '${layout.value}'")
    }

    val layoutFile = currentLayoutFile
    val previousPath = sourcePath
    val trackedFile = if (sourceNodeListener != null) file.canonicalFile else null
    if (trackedFile != null && !includeStack.add(trackedFile)) {
      throw InflateException("Circular layout reference: '${layout.value}'")
    }
    sourcePath = null
    val inflated = try {
      val (processor, module) = processXmlFile(file)
      doInflate(processor, module) { parent }
    } finally {
      _currentLayoutFile = layoutFile
      sourcePath = previousPath
      if (trackedFile != null) includeStack.remove(trackedFile)
    }

    if (inflated.isEmpty() || inflated.size > 1) {
      // probably a merged view or no views at all
      // no need to apply attributes
      return inflated
    }

    val includedView = inflated[0]
    val view = IncludeView(includedView as ViewImpl)
    addNamespaceDecls(element = element, view = view)

    // The inflated <include> view is already attached to parent
    // however, we want the parent to have the 'IncludeView' as the child and not the actually
    // included view
    val index = parent.indexOfChild(includedView)
    parent.removeChild(index)
    parent.addChild(index, view)

    // also, the attribute on an <include> tag must override the attributes specified on the root
    // view of the included layout file
    applyAttributes(element = element, view = view, parent = parent, attachToParent = false) {
      !(it.namespaceUri.isNullOrBlank() && it.name == "layout")
    }

    return listOf(view)
  }

  protected open fun onCreatePlatformView(
      widget: Widget,
      parent: ViewGroup,
      module: AndroidModule,
      widgets: WidgetTable,
  ): IView {
    return try {
      val v = createViewInstance(widget.qualifiedName, parent.context)
      return componentFactory.createView(currentLayoutFile, widget.qualifiedName, v)
    } catch (err: Throwable) {
      onCreateUnsupportedView(
          widget.qualifiedName,
          widget.type == WidgetType.LAYOUT,
          "Unable to create view for widget ${widget.qualifiedName}",
          parent,
      )
    }
  }

  /**
   * 创建控件表里没有、但已注册 [com.tom.rv2ide.inflater.IViewAdapter] 的库控件。
   *
   * <p>优先让适配器自己创建视图（它能处理构造差异），没有实现时回退到反射实例化。
   * 实例化失败（类不存在、构造签名不匹配）仍落到占位提示，与改动前一致。
   */
  protected open fun onCreateLibraryView(
      name: String,
      hasChildren: Boolean,
      parent: ViewGroup,
  ): IView {
    val actualName = com.tom.rv2ide.inflater.previewClassName(name)
    val adapter = IViewAdapterIndex.instance.getViewAdapter(actualName)
    if (adapter != null) {
      try {
        val view = createViewInstance(actualName, parent.context)
        return componentFactory.createView(currentLayoutFile, name, view)
      } catch (_: Throwable) {
        // 无法实例化：交给下面的占位提示。
      }
    }
    return onCreateUnsupportedView(
        name,
        hasChildren,
        "View with name '$name' not found",
        parent,
    )
  }

  protected open fun onCreateAttribute(
      view: ViewImpl,
      namespace: INamespace?,
      name: String,
      value: String,
  ): IAttribute {
    return componentFactory.createAttr(
        view = view,
        namespace = namespace,
        name = name,
        value = value,
    )
  }

  protected open fun addNamespaceDecls(element: XmlElement, view: ViewImpl) {
    if (element.namespaceDeclarationCount > 0) {
      for (xmlNamespace in element.namespaceDeclarationList) {
        view.namespaces[xmlNamespace.uri] = NamespaceImpl(xmlNamespace.prefix, xmlNamespace.uri)
      }
    }
  }

  protected open fun processXmlFile(file: File): Pair<XmlProcessor, AndroidModule> {
    return com.tom.rv2ide.inflater.utils.processXmlFile(file, LAYOUT)
  }

  private fun onCreateUnsupportedView(
      name: String,
      isLayout: Boolean,
      message: String,
      parent: ViewGroup,
  ): IView {
    return if (isLayout) {
      ErrorLayout(file = currentLayoutFile, name = name, context = parent.context)
    } else {
      ErrorView(file = currentLayoutFile, name = name, context = parent.context, message = message)
    }
  }

  private fun List<IView>.finishInflation() {
    inflationEventListener?.onEvent(InflationFinishEvent(this))
    _primaryInflatingFile = null
  }

  private fun startInflation(file: File) {
    this._primaryInflatingFile = file
    styles.clear()
    IDTable.newRound()
    if (!isParsing) {
      if (this.module == null) {
        startParse(file)
        this.module = com.tom.rv2ide.inflater.utils.module
      } else {
        startParse(this.module!!)
      }
      this.manuallyStartedParse = true
    } else {
      manuallyStartedParse = false
    }
    inflationEventListener?.onEvent(InflationStartEvent())
  }

  private fun wrap(parent: ViewGroup): IViewGroup {
    return componentFactory.createView(currentLayoutFile, parent.javaClass.name, parent)
        as IViewGroup
  }

  private fun SourcePosition.lineCol(): String {
    return "line $lineNumber and column $columnNumber"
  }
}
