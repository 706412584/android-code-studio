/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.utils

import com.tom.rv2ide.lsp.xml.providers.completion.StyleableAttr
import com.tom.rv2ide.lsp.xml.providers.completion.findStyleableAttrs
import com.tom.rv2ide.uidesigner.models.AttrKey
import com.tom.rv2ide.uidesigner.models.LayoutDocument

/**
 * 「添加属性」的属性名来源：按真实 View 和父布局的类继承链查 styleable。
 * LSP 只按标签名在 SDK 控件表里找，支持库和 Material 控件会漏掉父类属性；这里用运行时的类更准。
 *
 * 只在补全线程上调用（见 [ValueCompletionProvider.withLookup]），缓存也只在该线程读写。
 */
internal object AttrNameSource {
  private val cache = mutableMapOf<Pair<String, String?>, List<AttrKey>>()

  fun namesFor(viewClass: Class<*>, parentClass: Class<*>?): List<AttrKey> {
    val cacheKey = viewClass.name to parentClass?.name
    cache[cacheKey]?.let { return it }
    val names = classChain(viewClass, VIEW)
    val layoutNames = parentClass?.let { classChain(it, VIEW_GROUP) }.orEmpty()
    // 查不到任何 styleable（如没有项目模块）时不缓存，下次 Lookup 就绪后再查。
    val result = toKeys(findStyleableAttrs(names, layoutNames))
    if (result.isNotEmpty()) cache[cacheKey] = result
    return result
  }

  fun clear() = cache.clear()
}

private const val TOOLS = "http://schemas.android.com/tools"
private val localAttrName = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

private fun namespaceFor(prefix: String, declared: (String) -> String?): String? = when (prefix) {
  "android" -> LayoutDocument.ANDROID
  "app" -> LayoutDocument.APP
  "tools" -> TOOLS
  else -> declared(prefix)?.takeIf { it.isNotEmpty() }
}

/** 裸名按本地名或限定名筛选；输入冒号后只匹配该命名空间。候选保留可回填的完整名称。 */
internal fun matchingAttrNames(keys: List<AttrKey>, input: String, declared: (String) -> String?): List<AttrKey> {
  val query = input.trim()
  if (':' !in query) return keys.filter { it.name.startsWith(query, true) || it.qualifiedName.startsWith(query, true) }
  val prefix = query.substringBefore(':')
  val uri = namespaceFor(prefix, declared)
  val local = query.substringAfter(':')
  return keys.filter { (it.prefix == prefix || uri != null && it.uri == uri) && it.name.startsWith(local, true) }
}

/** 点选和手输共用同一个解析入口；未建索引时仍允许显式命名空间的自定义属性。 */
internal fun resolveAttrName(keys: List<AttrKey>, input: String, declared: (String) -> String?): AttrKey? {
  val text = input.trim()
  val name = text.substringAfter(':')
  if (!localAttrName.matches(name)) return null
  if (':' in text) {
    keys.firstOrNull { it.qualifiedName == text }?.let { return it }
    val prefix = text.substringBefore(':').takeIf { it.isNotEmpty() } ?: return null
    return namespaceFor(prefix, declared)?.let { AttrKey(it, name, prefix) }
  }
  val exact = keys.filter { it.name == name }
  return exact.firstOrNull { it.uri == LayoutDocument.ANDROID } ?: when (exact.size) {
    0 -> AttrKey(LayoutDocument.ANDROID, name, "android")
    1 -> exact.single()
    else -> null  // 有歧义时必须限定命名空间。
  }
}

private const val VIEW = "android.view.View"
private const val VIEW_GROUP = "android.view.ViewGroup"

/**
 * 从 [cls] 沿父类走到 [stopAt]（含）为止的简单类名，作为 styleable 名。
 * 走到 Object 仍没遇到 [stopAt] 时返回已走过的部分；匿名类和内部类的空名跳过。
 */
internal fun classChain(cls: Class<*>, stopAt: String): List<String> {
  val result = mutableListOf<String>()
  var current: Class<*>? = cls
  while (current != null && current != Any::class.java) {
    current.simpleName.takeIf { it.isNotEmpty() }?.let(result::add)
    if (current.name == stopAt) break
    current = current.superclass
  }
  return result
}

/** 命名空间映射为设计器的 [AttrKey]：框架属性用 `android:`，其余都在 `app:`（res-auto）。 */
internal fun toKeys(attrs: List<StyleableAttr>): List<AttrKey> = attrs.mapNotNull { attr ->
  when (attr.namespace) {
    LayoutDocument.ANDROID -> AttrKey(LayoutDocument.ANDROID, attr.name, "android")
    LayoutDocument.APP -> AttrKey(LayoutDocument.APP, attr.name, "app")
    else -> null
  }
}.distinctBy { it.uri to it.name }
