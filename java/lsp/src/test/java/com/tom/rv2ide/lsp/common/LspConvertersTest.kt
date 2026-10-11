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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MarkupKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LspConverters] 的解析测试。
 *
 * <p>用真实服务器返回的报文形态做输入（TSLS/Dart 的字段命名与嵌套），
 * 覆盖「服务器返回 null / 数组 / 对象」三种形态与字段缺失的容错。
 */
class LspConvertersTest {

  private val gson = Gson()

  private fun obj(json: String): JsonObject = gson.fromJson(json, JsonObject::class.java)

  // ------------------------------------------------------------ uri/path

  @Test
  fun uriToPath_parsesFileUri() {
    val path = LspConverters.uriToPath("file:///sdcard/proj/App.tsx")
    assertNotNull(path)
    // 用 Paths.get 构造期望值：Windows 上 JVM 会按平台分隔符表示，
    // 直接比字符串会误报（生产环境是 Android，永远是 /）
    assertEquals(java.nio.file.Paths.get("/sdcard/proj/App.tsx"), path)
  }

  @Test
  fun uriToPath_returnsNullForNonFileScheme() {
    assertNull(LspConverters.uriToPath("https://example.com/a.ts"))
  }

  @Test
  fun uriToPath_returnsNullForBlank() {
    assertNull(LspConverters.uriToPath(null))
    assertNull(LspConverters.uriToPath(""))
  }

  @Test
  fun pathToUri_roundTrips() {
    // 用真实临时文件做往返：Windows 的 Path.toUri() 会给无盘符的绝对路径
    // 补上盘符（file:///D:/...），拿手写路径断言会在 Windows 上误报
    val tmp = java.nio.file.Files.createTempFile("lsp-conv-test", ".ts")
    try {
      assertEquals(tmp, LspConverters.uriToPath(LspConverters.pathToUri(tmp)))
    } finally {
      java.nio.file.Files.deleteIfExists(tmp)
    }
  }

  // ------------------------------------------------------------ diagnostics

  @Test
  fun parseDiagnostics_extractsItems() {
    val params =
        obj(
            """
            {
              "uri": "file:///sdcard/proj/App.tsx",
              "diagnostics": [
                {
                  "range": {"start": {"line": 3, "character": 5}, "end": {"line": 3, "character": 10}},
                  "severity": 1,
                  "code": 2322,
                  "source": "typescript",
                  "message": "Type 'string' is not assignable to type 'number'."
                }
              ]
            }
            """)
    val result = LspConverters.parseDiagnostics(params)
    assertNotNull(result)
    assertEquals(java.nio.file.Paths.get("/sdcard/proj/App.tsx"), result!!.file)
    assertEquals(1, result.diagnostics.size)
    val item = result.diagnostics[0]
    assertEquals("Type 'string' is not assignable to type 'number'.", item.message)
    assertEquals("2322", item.code)
    assertEquals(DiagnosticSeverity.ERROR, item.severity)
    assertEquals(3, item.range.start.line)
    assertEquals(5, item.range.start.column)
    assertEquals(10, item.range.end.column)
  }

  @Test
  fun parseDiagnostics_emptyArray() {
    val params = obj("""{"uri":"file:///a.ts","diagnostics":[]}""")
    val result = LspConverters.parseDiagnostics(params)
    assertNotNull(result)
    assertTrue(result!!.diagnostics.isEmpty())
  }

  @Test
  fun parseDiagnostics_severityMapping() {
    fun severityOf(value: Int): DiagnosticSeverity {
      val params =
          obj(
              """
              {"uri":"file:///a.ts","diagnostics":[
                {"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},
                 "severity":$value,"message":"m"}]}
              """)
      return LspConverters.parseDiagnostics(params)!!.diagnostics[0].severity
    }
    assertEquals(DiagnosticSeverity.ERROR, severityOf(1))
    assertEquals(DiagnosticSeverity.WARNING, severityOf(2))
    assertEquals(DiagnosticSeverity.INFO, severityOf(3))
    assertEquals(DiagnosticSeverity.HINT, severityOf(4))
    // 缺省按规范默认 Error
    assertEquals(DiagnosticSeverity.ERROR, severityOf(99))
  }

  @Test
  fun parseDiagnostics_stringCodeSupported() {
    // Dart 分析服务器的 code 常是字符串（如 "undefined_function"）
    val params =
        obj(
            """
            {"uri":"file:///lib/main.dart","diagnostics":[
              {"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":5}},
               "severity":2,"code":"undefined_function","message":"Undefined name"}]}
            """)
    val result = LspConverters.parseDiagnostics(params)!!
    assertEquals("undefined_function", result.diagnostics[0].code)
  }

  // ------------------------------------------------------------ completion

  @Test
  fun parseCompletionResult_arrayForm() {
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":1,"result":[
              {"label":"useState","kind":3,"detail":"hook"},
              {"label":"useEffect","kind":3,"insertText":"useEffect","insertTextFormat":1}
            ]}
            """)
    val result = LspConverters.parseCompletionResult(response)
    assertEquals(2, result.items.size)
    // CompletionResult 构造时按 matchLevel→sortText→label 排序，items 顺序
    // 不是输入顺序；用集合断言而不是下标断言
    val labels = result.items.map { it.ideLabel }.toSet()
    assertEquals(setOf("useState", "useEffect"), labels)
    val useState = result.items.first { it.ideLabel == "useState" }
    assertEquals("hook", useState.detail)
    assertEquals(CompletionItemKind.FUNCTION, useState.completionKind)
  }

  @Test
  fun parseCompletionResult_completionListForm() {
    // Dart 分析服务器常返回 {isIncomplete, items} 形态
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":1,"result":{"isIncomplete":false,"items":[
              {"label":"setState","kind":2}
            ]}}
            """)
    val result = LspConverters.parseCompletionResult(response)
    assertEquals(1, result.items.size)
    assertEquals("setState", result.items[0].ideLabel)
    assertEquals(CompletionItemKind.METHOD, result.items[0].completionKind)
  }

  @Test
  fun parseCompletionResult_nullResult() {
    val response = obj("""{"jsonrpc":"2.0","id":1,"result":null}""")
    assertTrue(LspConverters.parseCompletionResult(response).items.isEmpty())
  }

  @Test
  fun parseCompletionResult_missingResult() {
    assertTrue(LspConverters.parseCompletionResult(null).items.isEmpty())
    assertTrue(
        LspConverters.parseCompletionResult(obj("""{"jsonrpc":"2.0","id":1}""")).items.isEmpty())
  }

  @Test
  fun parseCompletionResult_snippetFormat() {
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":1,"result":[
              {"label":"forEach","insertText":"forEach(${'$'}{1:callback})","insertTextFormat":2}
            ]}
            """)
    val result = LspConverters.parseCompletionResult(response)
    assertEquals(InsertTextFormat.SNIPPET, result.items[0].insertTextFormat)
    assertTrue(result.items[0].insertText.contains("${'$'}{1:callback}"))
  }

  @Test
  fun parseCompletionResult_textEditFallback() {
    // 部分条目只给 textEdit 不给 insertText（TSLS 的精确替换形态）
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":1,"result":[
              {"label":"console","textEdit":{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":3}},"newText":"console.log"}}
            ]}
            """)
    val result = LspConverters.parseCompletionResult(response)
    assertEquals("console.log", result.items[0].insertText)
  }

  @Test
  fun parseCompletionResult_itemWithoutLabel_skipped() {
    val response =
        obj("""{"jsonrpc":"2.0","id":1,"result":[{"kind":3},{"label":"valid"}]}""")
    val result = LspConverters.parseCompletionResult(response)
    assertEquals(1, result.items.size)
    assertEquals("valid", result.items[0].ideLabel)
  }

  // ------------------------------------------------------------ definition

  @Test
  fun parseDefinitionResult_locationArray() {
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":2,"result":[
              {"uri":"file:///sdcard/proj/App.tsx",
               "range":{"start":{"line":10,"character":2},"end":{"line":10,"character":8}}}
            ]}
            """)
    val locations = LspConverters.parseDefinitionResult(response)
    assertEquals(1, locations.size)
    assertEquals(java.nio.file.Paths.get("/sdcard/proj/App.tsx"), locations[0].file)
    assertEquals(10, locations[0].range.start.line)
  }

  @Test
  fun parseDefinitionResult_singleLocation() {
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":2,"result":
              {"uri":"file:///lib/main.dart",
               "range":{"start":{"line":5,"character":0},"end":{"line":5,"character":4}}}}
            """)
    val locations = LspConverters.parseDefinitionResult(response)
    assertEquals(1, locations.size)
  }

  @Test
  fun parseDefinitionResult_locationLink() {
    // LocationLink 形态（TSLS 在声明 linkSupport 时返回）
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":2,"result":[
              {"targetUri":"file:///sdcard/proj/utils.ts",
               "targetRange":{"start":{"line":1,"character":0},"end":{"line":1,"character":5}},
               "targetSelectionRange":{"start":{"line":1,"character":0},"end":{"line":1,"character":5}}}
            ]}
            """)
    val locations = LspConverters.parseDefinitionResult(response)
    assertEquals(1, locations.size)
    assertEquals(java.nio.file.Paths.get("/sdcard/proj/utils.ts"), locations[0].file)
  }

  @Test
  fun parseDefinitionResult_nullResult() {
    assertTrue(
        LspConverters.parseDefinitionResult(obj("""{"jsonrpc":"2.0","id":2,"result":null}"""))
            .isEmpty())
  }

  // ------------------------------------------------------------ hover

  @Test
  fun parseHoverResult_markupContent() {
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":3,"result":{"contents":{
              "kind":"markdown","value":"**count**: number"}}}
            """)
    val content = LspConverters.parseHoverResult(response)
    assertEquals(MarkupKind.MARKDOWN, content.kind)
    assertEquals("**count**: number", content.value)
  }

  @Test
  fun parseHoverResult_markedStringArray() {
    val response =
        obj(
            """
            {"jsonrpc":"2.0","id":3,"result":{"contents":[
              {"language":"typescript","value":"const count: number"},
              "额外说明"
            ]}}
            """)
    val content = LspConverters.parseHoverResult(response)
    assertTrue(content.value.contains("const count: number"))
    assertTrue(content.value.contains("额外说明"))
  }

  @Test
  fun parseHoverResult_null() {
    assertEquals(
        "", LspConverters.parseHoverResult(obj("""{"jsonrpc":"2.0","id":3,"result":null}""")).value)
    assertEquals("", LspConverters.parseHoverResult(null).value)
  }

  // ------------------------------------------------------------ misc

  @Test
  fun mapCompletionKind_coversAllKnownKinds() {
    assertEquals(CompletionItemKind.METHOD, LspConverters.mapCompletionKind(2))
    assertEquals(CompletionItemKind.FUNCTION, LspConverters.mapCompletionKind(3))
    assertEquals(CompletionItemKind.CONSTRUCTOR, LspConverters.mapCompletionKind(4))
    assertEquals(CompletionItemKind.FIELD, LspConverters.mapCompletionKind(5))
    assertEquals(CompletionItemKind.VARIABLE, LspConverters.mapCompletionKind(6))
    assertEquals(CompletionItemKind.CLASS, LspConverters.mapCompletionKind(7))
    assertEquals(CompletionItemKind.INTERFACE, LspConverters.mapCompletionKind(8))
    assertEquals(CompletionItemKind.MODULE, LspConverters.mapCompletionKind(9))
    assertEquals(CompletionItemKind.PROPERTY, LspConverters.mapCompletionKind(10))
    assertEquals(CompletionItemKind.VALUE, LspConverters.mapCompletionKind(12))
    assertEquals(CompletionItemKind.ENUM, LspConverters.mapCompletionKind(13))
    assertEquals(CompletionItemKind.KEYWORD, LspConverters.mapCompletionKind(14))
    assertEquals(CompletionItemKind.SNIPPET, LspConverters.mapCompletionKind(15))
    assertEquals(CompletionItemKind.ENUM_MEMBER, LspConverters.mapCompletionKind(20))
    assertEquals(CompletionItemKind.TYPE_PARAMETER, LspConverters.mapCompletionKind(25))
    assertEquals(CompletionItemKind.NONE, LspConverters.mapCompletionKind(0))
    assertEquals(CompletionItemKind.NONE, LspConverters.mapCompletionKind(999))
  }

  @Test
  fun parseTextEdits_extractsEdits() {
    val edits =
        LspConverters.parseTextEdits(
            gson
                .fromJson(
                    """
                    [{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},
                      "newText":"import { useState } from 'react';\n"}]
                    """,
                    com.google.gson.JsonArray::class.java))
    assertNotNull(edits)
    assertEquals(1, edits!!.size)
    assertEquals("import { useState } from 'react';\n", edits[0].newText)
  }

  @Test
  fun parseTextEdits_emptyOrNull() {
    assertNull(LspConverters.parseTextEdits(null))
    assertNull(LspConverters.parseTextEdits(com.google.gson.JsonArray()))
  }

  @Test
  fun writePosition_andWriteRange() {
    val pos = com.tom.rv2ide.models.Position(3, 7)
    val written = LspConverters.writePosition(pos)
    assertEquals(3, written.get("line").asInt)
    assertEquals(7, written.get("character").asInt)

    val range = com.tom.rv2ide.models.Range(com.tom.rv2ide.models.Position(1, 2), com.tom.rv2ide.models.Position(3, 4))
    val writtenRange = LspConverters.writeRange(range)
    assertEquals(1, writtenRange.getAsJsonObject("start").get("line").asInt)
    assertEquals(4, writtenRange.getAsJsonObject("end").get("character").asInt)
  }

  @Test
  fun parseRange_missingFieldsFallBackToZero() {
    val range = LspConverters.parseRange(obj("""{}"""))
    assertEquals(0, range.start.line)
    assertEquals(0, range.end.column)
  }
}
