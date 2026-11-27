/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.utils

import com.tom.rv2ide.uidesigner.models.AttrKey
import com.tom.rv2ide.uidesigner.models.LayoutDocument

// 属性值补全用到的纯函数，不依赖 Android 运行时，便于单测。

private val androidIdRefs = setOf(
    "layout_below", "layout_above", "layout_toStartOf", "layout_toEndOf", "layout_toLeftOf", "layout_toRightOf",
    "layout_alignTop", "layout_alignBottom", "layout_alignStart", "layout_alignEnd", "layout_alignLeft",
    "layout_alignRight", "layout_alignBaseline", "labelFor", "nextFocusUp", "nextFocusDown", "nextFocusLeft",
    "nextFocusRight", "nextFocusForward", "accessibilityTraversalBefore", "accessibilityTraversalAfter", "checkedButton",
)
private val constraintRef = Regex("layout_constraint\\w+_to\\w+Of|layout_constraintCircle")
// 只认本应用的 id；@android:id/ 属于框架，不参与重名判断。
private val idValue = Regex("@\\+?id/([a-zA-Z_][a-zA-Z0-9_]*)")

/** 值是对布局内某个 id 的引用的属性。 */
internal fun isIdReferenceAttr(key: AttrKey): Boolean = when (key.uri) {
  LayoutDocument.ANDROID -> key.name in androidIdRefs
  LayoutDocument.APP -> key.name == "layout_anchor" || constraintRef.matches(key.name)
  else -> false
}

/** ConstraintLayout 的约束还可以指向 `parent`。 */
internal fun acceptsParent(key: AttrKey): Boolean = key.uri == LayoutDocument.APP && constraintRef.matches(key.name)

internal fun isIdAttr(key: AttrKey): Boolean = key.uri == LayoutDocument.ANDROID && key.name == "id"

/** `@+id/x`、`@id/x` 取出 `x`；其他写法返回 null。 */
internal fun idName(value: String): String? = idValue.matchEntire(value.trim())?.groupValues?.get(1)

/** 文档里已声明的 id 名（去重、排序），排除 [exclude] 节点自身。 */
internal fun documentIds(document: LayoutDocument, exclude: Long?): List<String> =
    document.nodes.filter { it.id != exclude }.mapNotNull { node -> node.xmlId?.let(::idName) }.distinct().sorted()

/** 除 [nodeId] 外，已经使用 [value] 这个 id 的节点。 */
internal fun duplicateIdOwners(document: LayoutDocument, nodeId: Long, value: String): List<LayoutDocument.Node> {
  val name = idName(value) ?: return emptyList()
  return document.nodes.filter { it.id != nodeId && it.xmlId?.let(::idName) == name }
}

/** id 引用的候选：`@id/x`，约束属性额外给 `parent`；按 id 名包含输入过滤。 */
internal fun idSuggestions(ids: List<String>, key: AttrKey, value: String): List<String> {
  val query = value.trim().removePrefix("@+").removePrefix("@").removePrefix("id/")
  val all = (if (acceptsParent(key)) listOf("parent") else emptyList()) + ids.map { "@id/$it" }
  return all.filter { it.substringAfter('/').contains(query, ignoreCase = true) }
}

/** 多值 flag 按最后一个 `|` 拆开：前半段（含 `|`）原样保留，只补全后半段。 */
internal fun splitFlags(value: String): Pair<String, String> {
  val i = value.lastIndexOf('|')
  return if (i < 0) "" to value.trim() else value.substring(0, i + 1) to value.substring(i + 1).trim()
}

internal fun usedFlags(head: String): Set<String> = head.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
