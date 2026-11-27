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

package com.tom.rv2ide.lsp.models

import com.tom.rv2ide.lsp.models.DiagnosticSeverity.ERROR
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.HINT
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.INFO
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.WARNING
import com.tom.rv2ide.models.Range
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticProvider
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_ERROR
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_NONE
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_TYPO
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_WARNING
import io.github.rosemoe.sora.widget.CodeEditor
import java.nio.file.Path
import java.nio.file.Paths
import java.util.WeakHashMap

data class DiagnosticItem(
    var message: String,
    var code: String,
    var range: Range,
    var source: String,
    var severity: DiagnosticSeverity,
) {

  var extra: Any = Any()

  companion object {
    @JvmField
    val START_COMPARATOR: Comparator<in DiagnosticItem> =
        Comparator.comparing(DiagnosticItem::range)

    private fun mapSeverity(severity: DiagnosticSeverity): Short {
      return when (severity) {
        ERROR -> SEVERITY_ERROR
        WARNING -> SEVERITY_WARNING
        INFO -> SEVERITY_NONE
        HINT -> SEVERITY_TYPO
      }
    }

    // Helper to convert line/column to character index
    fun lineColumnToIndex(content: CharSequence, line: Int, column: Int): Int {
      var currentLine = 0
      var index = 0

      while (currentLine < line && index < content.length) {
        if (content[index] == '\n') {
          currentLine++
        }
        index++
      }

      return minOf(index + column, content.length)
    }
  }

  fun asDiagnosticRegion(content: CharSequence): DiagnosticRegion {
    try {
      // Convert line/column positions to character indices
      val startIndex = lineColumnToIndex(content, range.start.line, range.start.column)
      val endIndex = lineColumnToIndex(content, range.end.line, range.end.column)

      return DiagnosticRegion(startIndex, endIndex, mapSeverity(severity))
    } catch (e: Exception) {
      // KslLogs.error("Failed to create diagnostic region", e)
      // Return a minimal valid region
      return DiagnosticRegion(0, 1, mapSeverity(severity))
    }
  }

  private fun lineColumnToIndex(content: CharSequence, line: Int, column: Int): Int {
    var currentLine = 0
    var index = 0

    while (currentLine < line && index < content.length) {
      if (content[index] == '\n') {
        currentLine++
      }
      index++
    }

    // Add column offset
    return minOf(index + column, content.length)
  }
}

/** 同一个编辑器只登记一个 LSP 提供者，不覆盖语法分析器自己的诊断。 */
object EditorDiagnostics {
  private class Regions(var items: List<DiagnosticRegion>) : DiagnosticProvider {
    override fun provideDiagnostics(container: DiagnosticsContainer) = container.addDiagnostics(items)
  }

  // 仅在 UI 线程访问；值不持有编辑器。
  private val providers = WeakHashMap<CodeEditor, Regions>()

  @JvmStatic fun publish(editor: CodeEditor, diagnostics: List<DiagnosticItem>) {
    editor.post {
      if (editor.isReleased) return@post
      if (diagnostics.isEmpty()) {
        providers.remove(editor)?.let(editor::unregisterDiagnosticProvider)
        return@post
      }
      val items = diagnostics.map { it.asDiagnosticRegion(editor.text) }
      val existing = providers[editor]
      if (existing == null) {
        val provider = Regions(items)
        providers[editor] = provider
        editor.registerDiagnosticProvider(provider)
      } else {
        existing.items = items
        editor.invalidateDiagnostics()
      }
    }
  }
}

data class DiagnosticResult(var file: Path, var diagnostics: List<DiagnosticItem>) {
  companion object {
    @JvmField val NO_UPDATE = DiagnosticResult(Paths.get(""), emptyList())
  }
}

enum class DiagnosticSeverity {
  ERROR,
  WARNING,
  INFO,
  HINT,
}
