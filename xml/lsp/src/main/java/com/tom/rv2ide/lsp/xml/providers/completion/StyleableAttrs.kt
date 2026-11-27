/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.lsp.xml.providers.completion

import com.android.aaptcompiler.AaptResourceType.STYLEABLE
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.Styleable
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.xml.resources.ResourceTableRegistry

/** 一条可用属性：[namespace] 是属性所属命名空间 URI，[layout] 表示来自父布局的 LayoutParams。 */
data class StyleableAttr(val namespace: String, val name: String, val layout: Boolean)

/**
 * 按 styleable 名列出可用属性，供布局设计器按真实 View 的类继承链查询（LSP 只能按标签名在控件表里找，
 * 支持库和 Material 控件会漏掉父类）。
 *
 * [names] 是控件自身及其父类的 styleable 名（如 MaterialButton、Button、TextView、View）；
 * [layoutNames] 是父布局及其父类的名字，不带 `_Layout` 后缀，为空表示没有父布局。
 * 只读取 Lookup 里已设置的资源表，调用前需先 setupLookupForCompletion。
 */
fun findStyleableAttrs(names: List<String>, layoutNames: List<String>): List<StyleableAttr> {
  val lookup = Lookup.getDefault()
  val framework = lookup.lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES)
  val tables = listOfNotNull(framework) +
      lookup.lookup(ResourceTableRegistry.COMPLETION_MODULE_RES).orEmpty() +
      lookup.lookup(ResourceTableRegistry.COMPLETION_DEP_RES).orEmpty()
  val wanted = names.map { it to false } +
      if (layoutNames.isEmpty()) emptyList()
      else (layoutNames + "ViewGroup").map { "${it}_Layout" to true } + ("ViewGroup_MarginLayout" to true)
  val androidNs = IXmlCompletionProvider.NAMESPACE_PREFIX + ResourceTableRegistry.PCK_ANDROID
  val result = linkedMapOf<Pair<String, String>, StyleableAttr>()
  for (table in tables) {
    val isFramework = table === framework
    for (pck in table.packages) {
      if (!isFramework && pck.name.isBlank()) continue
      val group = pck.findGroup(STYLEABLE) ?: continue
      for ((name, layout) in wanted) {
        val styleable = group.findEntry(name)?.findValue(ConfigDescription())?.value as? Styleable ?: continue
        for (ref in styleable.entries) {
          val entry = ref.name.entry ?: continue
          val ns = if (isFramework || ref.name.pck == ResourceTableRegistry.PCK_ANDROID) androidNs
              else IXmlCompletionProvider.NAMESPACE_AUTO
          result.putIfAbsent(ns to entry, StyleableAttr(ns, entry, layout))
        }
      }
    }
  }
  return result.values.toList()
}
