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

package com.tom.rv2ide.uidesigner.utils

import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.inflater.internal.ViewImpl
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.util.setupLookupForCompletion
import com.tom.rv2ide.lsp.xml.models.XMLServerSettings
import com.tom.rv2ide.lsp.xml.providers.XmlCompletionProvider
import com.tom.rv2ide.lsp.xml.providers.completion.AttrValueCompletionProvider
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.uidesigner.models.AttrKey
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** 一条候选值：[text] 是选中后整体回填的值，[kind] 是类型标签，[detail] 是来源包。 */
internal data class ValueSuggestion(val text: String, val kind: String = "", val detail: String = "")

/**
 * 设计器属性值补全。复用 LSP 的 [AttrValueCompletionProvider]，另补 LSP 不处理的几类：
 * 文档内 `@id/`、`?attr/` 主题属性、多值 flag 的拼接、布尔字面量。
 *
 * 所有查询在共享的单线程上执行并做防抖；结果按版本号丢弃过期请求，只在主线程回调。
 * 首次查到属性定义后经 [onSchema] 回传格式（查不到为 null），供调用方按格式校验。
 */
internal class ValueCompletionProvider(
    private val file: File,
    view: ViewImpl,
    private val key: AttrKey,
    private val document: LayoutDocument,
    private val nodeId: Long,
    private val onSchema: (AttrSchema?) -> Unit = {},
    private val onComplete: (List<ValueSuggestion>) -> Unit,
) {
  private val namespaces = view.findNamespaces().map { it.prefix to it.uri }.toSet()
  // 只在补全线程上创建和使用：LSP 提供者内部有可变状态。
  private var lsp: AttrValueCompletionProvider? = null
  private var schema: AttrSchema? = null
  @Volatile private var revision = 0L
  private var pending: Future<*>? = null

  fun close() {
    revision++
    pending?.cancel(false)
    pending = null
  }

  /** 主线程调用；[delayMs] 为 0 时立即查询（如刚获焦）。 */
  fun request(value: String, delayMs: Long = DEBOUNCE_MS) {
    close()
    val request = revision
    pending = executor.schedule({
      if (request != revision) return@schedule
      val result = try {
        complete(value)
      } catch (e: Throwable) {
        log.warn("Value completion failed for {}", key.qualifiedName, e)
        emptyList()
      }
      ThreadUtils.runOnUiThread { if (request == revision) onComplete(result) }
    }, delayMs, TimeUnit.MILLISECONDS)
  }

  private fun provider(): AttrValueCompletionProvider = lsp ?: run {
    ensureLookup(file)
    AttrValueCompletionProvider(XmlCompletionProvider(XMLServerSettings)).also {
      it.setNamespaces(namespaces)
      lsp = it
      val found = it.findAttributeFormat(key.uri, key.name)?.let(AttrSchema::of)
      schema = found
      ThreadUtils.runOnUiThread { onSchema(found) }
    }
  }

  private fun complete(value: String): List<ValueSuggestion> {
    if (isIdAttr(key)) return emptyList()
    if (isIdReferenceAttr(key) && !value.startsWith('?')) {
      return idSuggestions(documentIds(document, nodeId), key, value).map { ValueSuggestion(it, KIND_ID) }
    }
    val provider = provider()
    if (value.startsWith('?')) return provider.completeThemeAttr(value).items.map(::suggestion)
    val schema = schema
    if (value.startsWith('#') && (schema?.color == true || key.name == "background")) {
      return BackgroundCompletions.getBackgroundSuggestions(value).map { ValueSuggestion(it, KIND_COLOR) }
    }
    if (schema?.flags == true && !value.startsWith('@')) {
      val (head, tail) = splitFlags(value)
      val used = usedFlags(head)
      return schema.symbols.filter { it !in used && it.contains(tail, ignoreCase = true) }
          .sortedBy { !it.startsWith(tail, ignoreCase = true) }
          .map { ValueSuggestion(head + it, KIND_FLAG) }
    }
    // LSP 对布尔格式只补 @bool/ 资源，不给 true/false 字面量，这里补上。
    val literals = if (schema?.boolean == true && !value.startsWith('@')) {
      listOf("true", "false").filter { it.startsWith(value.trim(), ignoreCase = true) }.map { ValueSuggestion(it, KIND_BOOL) }
    } else emptyList()
    val items = provider.completeValue(namespace = key.uri, prefix = value, attrName = key.name, attrValue = value)
        .items.map(::suggestion)
    return (literals + items).distinctBy { it.text }
  }

  private fun suggestion(item: CompletionItem): ValueSuggestion {
    val kind = item.overrideTypeText ?: if (schema?.enum == true && !item.ideLabel.startsWith('@')) KIND_ENUM else ""
    val detail = item.detail.removePrefix("From package '").removeSuffix("'")
    return ValueSuggestion(item.ideLabel, kind, detail)
  }

  companion object {
    private const val DEBOUNCE_MS = 150L
    private const val KIND_ID = "ID"
    private const val KIND_FLAG = "FLAG"
    private const val KIND_ENUM = "ENUM"
    private const val KIND_BOOL = "BOOL"
    private const val KIND_COLOR = "COLOR"
    private val log = LoggerFactory.getLogger(ValueCompletionProvider::class.java)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "DesignerValueCompletion").apply { isDaemon = true }
    }

    // 以下两项只在 executor 线程上读写。
    private var lookupFile: File? = null
    private var lookupModule: ModuleProject? = null

    /**
     * 同一文件只初始化一次 Lookup。Lookup 是全局单例，代码编辑器补全其他模块的文件时会覆盖它，
     * 所以还要核对里面的模块仍是上次设置的那个实例，不是就重新设置。
     */
    private fun ensureLookup(file: File) {
      val current = Lookup.getDefault().lookup(ModuleProject.COMPLETION_MODULE_KEY)
      if (file == lookupFile && current != null && current === lookupModule) return
      setupLookupForCompletion(file)
      lookupFile = file
      lookupModule = Lookup.getDefault().lookup(ModuleProject.COMPLETION_MODULE_KEY)
    }

    /**
     * 丢弃缓存，下次查询重新初始化 Lookup。资源表刷新时会换成新实例，
     * 设计器每次打开时调用，避免整场会话一直拿着旧表。
     */
    fun invalidateLookup() {
      executor.execute {
        lookupFile = null
        lookupModule = null
        AttrNameSource.clear()
      }
    }

    /** 在补全线程上带着已初始化的 Lookup 执行 [task]；结果非空时回到主线程交给 [onResult]。 */
    fun <T : Any> withLookup(file: File, task: () -> T?, onResult: (T) -> Unit): Future<*> = executor.submit {
      val result = try {
        ensureLookup(file)
        task()
      } catch (e: Throwable) {
        log.warn("Designer lookup task failed for {}", file, e)
        null
      }
      if (result != null) ThreadUtils.runOnUiThread { onResult(result) }
    }
  }
}
