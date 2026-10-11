/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.common

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.net.URI
import java.nio.file.Paths

/**
 * LSP wire 格式（JsonObject）与 IDE 模型之间的转换。
 *
 * <p>全部是无状态纯函数，与进程/Android 无关，可被 JVM 单测直接覆盖。
 * TS 与 Dart 两个服务器共用。
 *
 * <p><b>注意 position 约定</b>：LSP 用 0-based line/character，IDE 模型同为 0-based，
 * 因此不需要偏移换算；但 character 单位是 UTF-16 code unit（与 Java/Kotlin 的
 * Char 一致），本转换直接沿用，不做 Unicode 码点换算——sora 编辑器的列号同为
 * UTF-16 单位，两边一致。
 */
object LspConverters {

  /** file URI ↔ Path。非 file scheme 或非法 URI 返回 null。 */
  fun uriToPath(uri: String?): java.nio.file.Path? {
    if (uri.isNullOrBlank()) {
      return null
    }
    return try {
      val parsed = URI(uri)
      if (parsed.scheme != null && parsed.scheme != "file") {
        null
      } else {
        Paths.get(parsed)
      }
    } catch (e: Exception) {
      // 部分服务器在 windows 风格路径下会给出非标准 URI，尽力而为
      try {
        Paths.get(uri.removePrefix("file://"))
      } catch (e2: Exception) {
        null
      }
    }
  }

  /** Path → file URI 字符串（供 LSP 请求使用）。 */
  fun pathToUri(path: java.nio.file.Path): String = path.toUri().toString()

  /** 解析 LSP Range（`{start:{line,character},end:{...}}`）。缺字段时退化为 (0,0)。 */
  fun parseRange(element: JsonElement?): Range {
    val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return Range.NONE
    return Range(parsePosition(obj.get("start")), parsePosition(obj.get("end")))
  }

  private fun parsePosition(element: JsonElement?): Position {
    val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return Position(0, 0)
    val line = obj.get("line")?.asInt ?: 0
    val character = obj.get("character")?.asInt ?: 0
    return Position(line, character)
  }

  /** 把 IDE Position 写为 LSP `{line,character}`。 */
  fun writePosition(position: Position): JsonObject =
      JsonObject().apply {
        addProperty("line", position.line)
        addProperty("character", position.column)
      }

  /** 把 IDE Range 写为 LSP `{start,end}`。 */
  fun writeRange(range: Range): JsonObject =
      JsonObject().apply {
        add("start", writePosition(range.start))
        add("end", writePosition(range.end))
      }

  /** 解析 `textDocument/publishDiagnostics` 的 params。无法解析时返回 null。 */
  fun parseDiagnostics(params: JsonObject?): DiagnosticResult? {
    params ?: return null
    val uri = params.get("uri")?.asString ?: return null
    val path = uriToPath(uri) ?: return null
    val array = params.getAsJsonArray("diagnostics") ?: return DiagnosticResult(path, emptyList())

    val items =
        array.mapNotNull { element ->
          try {
            val diag = element.asJsonObject
            DiagnosticItem(
                message = diag.get("message")?.asString ?: "",
                code =
                    when (val code = diag.get("code")) {
                      null -> ""
                      else ->
                          if (code.isJsonPrimitive) code.asString
                          else ""
                    },
                range = parseRange(diag.get("range")),
                source = diag.get("source")?.asString ?: "",
                severity =
                    when (diag.get("severity")?.asInt) {
                      1 -> DiagnosticSeverity.ERROR
                      2 -> DiagnosticSeverity.WARNING
                      3 -> DiagnosticSeverity.INFO
                      4 -> DiagnosticSeverity.HINT
                      // 规范默认值是 Error；但 TSLS 的 hint 常省略 severity，
                      // 归为 WARNING 会让「未使用变量」这类提示看起来过重。
                      // 保持规范默认（ERROR）——显示层会按 source 过滤。
                      else -> DiagnosticSeverity.ERROR
                    },
            )
          } catch (e: Exception) {
            null
          }
        }
    return DiagnosticResult(path, items)
  }

  /**
   * 解析 `textDocument/completion` 的 result。
   *
   * <p>result 有两种形态：CompletionItem[] 或 CompletionList `{isIncomplete, items}`。
   * 都要支持——TSLS 返回前者，Dart 分析服务器两者都可能。
   *
   * <p><b>为什么不在这里做 prefix 过滤</b>：IDE 的 CompletionResult 按 matchLevel 排序，
   * 但 LSP 服务器返回的顺序本身已经带排序意图（sortText）。把 prefix 交给上层
   * CommonCompletionProvider 的 prefixMatcher 过滤更符合既有架构。
   */
  fun parseCompletionResult(response: JsonObject?): CompletionResult {
    val result = response?.get("result") ?: return CompletionResult.EMPTY
    val array: JsonArray =
        when {
          result.isJsonNull -> return CompletionResult.EMPTY
          result.isJsonArray -> result.asJsonArray
          result.isJsonObject -> result.asJsonObject.getAsJsonArray("items") ?: return CompletionResult.EMPTY
          else -> return CompletionResult.EMPTY
        }

    val items = array.mapNotNull { parseCompletionItem(it) }
    return CompletionResult(items)
  }

  private fun parseCompletionItem(element: JsonElement): CompletionItem? {
    val obj =
        try {
          element.asJsonObject
        } catch (e: Exception) {
          return null
        }
    val label = obj.get("label")?.asString ?: return null

    val detail =
        when (val d = obj.get("detail")) {
          null -> ""
          else -> if (d.isJsonPrimitive) d.asString else ""
        }

    val insertText = obj.get("insertText")?.asString
    val insertTextFormat =
        when (obj.get("insertTextFormat")?.asInt) {
          2 -> InsertTextFormat.SNIPPET
          else -> InsertTextFormat.PLAIN_TEXT
        }

    // textEdit 优先于 insertText：TSLS 通过 textEdit 携带精确替换范围，
    // 而 IDE 的 DefaultEditHandler 只按「标识符前缀」删除旧文本。
    // 这里把 textEdit 归一化为 insertText（保留其 newText），范围差异由
    // DefaultEditHandler 的前缀删除覆盖绝大多数场景（标识符补全）。
    val effectiveInsertText =
        insertText
            ?: obj.getAsJsonObject("textEdit")?.get("newText")?.asString
            ?: label

    return CompletionItem(
        ideLabel = label,
        detail = detail,
        insertText = effectiveInsertText,
        insertTextFormat = insertTextFormat,
        sortText = obj.get("sortText")?.asString,
        command = null,
        completionKind = mapCompletionKind(obj.get("kind")?.asInt ?: 1),
        matchLevel = MatchLevel.NO_MATCH,
        additionalTextEdits = parseTextEdits(obj.getAsJsonArray("additionalTextEdits")),
        data = null,
    )
  }

  /** 解析 LSP 的 CompletionItemKind（1..25）为 IDE 枚举。未知值归 NONE。 */
  fun mapCompletionKind(kind: Int): CompletionItemKind =
      when (kind) {
        2 -> CompletionItemKind.METHOD
        3 -> CompletionItemKind.FUNCTION
        4 -> CompletionItemKind.CONSTRUCTOR
        5 -> CompletionItemKind.FIELD
        6 -> CompletionItemKind.VARIABLE
        7 -> CompletionItemKind.CLASS
        8 -> CompletionItemKind.INTERFACE
        9 -> CompletionItemKind.MODULE
        10 -> CompletionItemKind.PROPERTY
        12 -> CompletionItemKind.VALUE
        13 -> CompletionItemKind.ENUM
        14 -> CompletionItemKind.KEYWORD
        15 -> CompletionItemKind.SNIPPET
        20 -> CompletionItemKind.ENUM_MEMBER
        25 -> CompletionItemKind.TYPE_PARAMETER
        else -> CompletionItemKind.NONE
      }

  /** 解析 TextEdit 数组（用于 additionalTextEdits 与 formatting）。 */
  fun parseTextEdits(array: JsonArray?): List<TextEdit>? {
    array ?: return null
    if (array.size() == 0) {
      return null
    }
    val edits =
        array.mapNotNull { element ->
          try {
            val obj = element.asJsonObject
            val newText = obj.get("newText")?.asString ?: return@mapNotNull null
            TextEdit(parseRange(obj.get("range")), newText)
          } catch (e: Exception) {
            null
          }
        }
    return edits.ifEmpty { null }
  }

  /**
   * 解析 `textDocument/definition` 的 result 为 Location 列表。
   *
   * <p>三种形态：Location / Location[] / LocationLink[]（含 targetUri/targetRange）。
   * Dart 分析服务器返回 Location[]，TSLS 返回 Location 或 Location[]。
   */
  fun parseDefinitionResult(response: JsonObject?): List<Location> {
    val result = response?.get("result") ?: return emptyList()
    if (result.isJsonNull) {
      return emptyList()
    }
    val elements =
        when {
          result.isJsonArray -> result.asJsonArray.toList()
          else -> listOf(result)
        }
    return elements.mapNotNull { parseLocation(it) }
  }

  private fun parseLocation(element: JsonElement): Location? {
    val obj =
        try {
          element.asJsonObject
        } catch (e: Exception) {
          return null
        }
    // LocationLink 形态
    val uri = obj.get("uri")?.asString ?: obj.get("targetUri")?.asString ?: return null
    val rangeElement = obj.get("range") ?: obj.get("targetSelectionRange") ?: obj.get("targetRange")
    val path = uriToPath(uri) ?: return null
    return Location(path, parseRange(rangeElement))
  }

  /** 解析 `textDocument/hover` 的 result 为 MarkupContent。 */
  fun parseHoverResult(response: JsonObject?): MarkupContent {
    val result = response?.get("result") ?: return MarkupContent()
    if (result.isJsonNull) {
      return MarkupContent()
    }
    val obj =
        try {
          result.asJsonObject
        } catch (e: Exception) {
          return MarkupContent()
        }

    // contents 有三种形态：MarkupContent / MarkedString / MarkedString[]
    val contents = obj.get("contents") ?: return MarkupContent()
    return when {
      contents.isJsonObject -> {
        val content = contents.asJsonObject
        MarkupContent(
            content.get("value")?.asString ?: "",
            parseMarkupKind(content.get("kind")?.asString),
        )
      }
      contents.isJsonArray -> {
        val text =
            contents.asJsonArray
                .mapNotNull { element ->
                  when {
                    element.isJsonPrimitive -> element.asString
                    element.isJsonObject -> {
                      val o = element.asJsonObject
                      // MarkedString 对象形态：{language, value}
                      o.get("value")?.asString ?: ""
                    }
                    else -> null
                  }
                }
                .joinToString("\n\n")
        MarkupContent(text, MarkupKind.MARKDOWN)
      }
      contents.isJsonPrimitive -> MarkupContent(contents.asString, MarkupKind.MARKDOWN)
      else -> MarkupContent()
    }
  }

  private fun parseMarkupKind(kind: String?): MarkupKind =
      when (kind) {
        "markdown" -> MarkupKind.MARKDOWN
        else -> MarkupKind.PLAIN
      }
}
