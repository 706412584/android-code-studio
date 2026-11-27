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
