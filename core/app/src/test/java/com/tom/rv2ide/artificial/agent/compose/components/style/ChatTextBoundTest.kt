/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.compose.components.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长文本有界渲染工具（移植自 Aharou `ExpandableOutputText.kt`）的纯函数测试。
 *
 * <p><b>为什么值得单独测</b>：这三个函数是「工具输出不卡主线程」的唯一防线，
 * 但它们错了不会报错，只会表现为「偶发掉帧」或「文本被悄悄截断」——两者都很难在
 * 真机上归因。特别是：
 * <ul>
 *   <li>[boundedTextChunks] 若切掉了半个代理对，后半块开头就是乱码方块；
 *   <li>[boundedTextChunks] 若在无换行文本上退化成「每块 1 字符」，块数会爆炸到
 *       几十万，展开一次就 OOM；
 *   <li>[capLineLengths] 若不返回原对象，每次重组都复制一遍几十万字符的串。
 * </ul>
 */
class ChatTextBoundTest {

  // ── boundedTextChunks ────────────────────────────────────────────────

  @Test
  fun emptyTextYieldsNoChunks() {
    assertTrue(boundedTextChunks("", 100, 10).isEmpty())
  }

  @Test
  fun chunksCoverTextExactlyOnce() {
    val text = (1..500).joinToString("\n") { "line $it" }
    val chunks = boundedTextChunks(text, maxCharsPerChunk = 200, maxLinesPerChunk = 20)
    assertTrue(chunks.isNotEmpty())
    assertEquals(0, chunks.first().first)
    assertEquals(text.length - 1, chunks.last().last)
    chunks.zipWithNext().forEach { (a, b) ->
      assertEquals("块之间不能有缝也不能重叠", a.last + 1, b.first)
    }
    assertEquals(text, chunks.joinToString("") { text.substring(it.first, it.last + 1) })
  }

  @Test
  fun chunkRespectsCharBudget() {
    val text = "x".repeat(1_000)
    val chunks = boundedTextChunks(text, maxCharsPerChunk = 100, maxLinesPerChunk = 40)
    assertTrue("每块不得超过字符预算", chunks.all { it.last - it.first + 1 <= 100 })
    assertEquals(10, chunks.size)
  }

  @Test
  fun chunkRespectsLineBudget() {
    val text = (1..100).joinToString("\n") { "l$it" }
    val chunks = boundedTextChunks(text, maxCharsPerChunk = 10_000, maxLinesPerChunk = 10)
    assertTrue("每块不得超过行数预算", chunks.all { text.substring(it.first, it.last + 1).count { c -> c == '\n' } <= 10 })
  }

  @Test
  fun chunkPrefersLineBoundaryOverHardCut() {
    // 每行 9 字符，预算 20：应当两行一块，而不是在行中间切。
    val text = (1..6).joinToString("\n") { "abcdefg$it" }
    val chunks = boundedTextChunks(text, maxCharsPerChunk = 20, maxLinesPerChunk = 40)
    assertTrue("块边界应落在换行处", chunks.all { text[it.last] == '\n' || it.last == text.length - 1 })
  }

  @Test
  fun chunkNeverSplitsSurrogatePair() {
    // 每 2 个字符一个 emoji（代理对）；预算取奇数，逼它落在代理对中间。
    val emoji = "\uD83D\uDE00" // 😀
    val text = emoji.repeat(50)
    val chunks = boundedTextChunks(text, maxCharsPerChunk = 5, maxLinesPerChunk = 40)
    chunks.forEach { range ->
      val slice = text.substring(range.first, range.last + 1)
      assertTrue("块不能以孤立低位代理开头", !slice.first().isLowSurrogate())
      assertTrue("块不能以孤立高位代理结尾", !slice.last().isHighSurrogate())
    }
    assertEquals(text, chunks.joinToString("") { text.substring(it.first, it.last + 1) })
  }

  // ── tailPreview ──────────────────────────────────────────────────────

  @Test
  fun tailPreviewKeepsTailLines() {
    val text = (1..100).joinToString("\n") { "line $it" }
    val preview = tailPreview(text, maxLines = 3, maxLineChars = 400, scanChars = 10_000)
    assertEquals("line 98\nline 99\nline 100", preview)
  }

  @Test
  fun tailPreviewMarksWindowTruncation() {
    val text = (1..100).joinToString("\n") { "line $it" }
    val preview = tailPreview(text, maxLines = 3, maxLineChars = 400, scanChars = 40)
    assertTrue("窗口外的内容应提示省略", preview.startsWith("…"))
    assertTrue(preview.endsWith("line 100"))
  }

  @Test
  fun tailPreviewCapsLongLineKeepingItsTail() {
    val line = "A".repeat(50) + "ERROR_AT_END"
    val preview = tailPreview(line, maxLines = 24, maxLineChars = 20, scanChars = 10_000)
    assertTrue("长行保留行尾", preview.endsWith("ERROR_AT_END"))
    assertTrue("长行补前置省略号", preview.startsWith("…"))
    assertTrue("预览长度受控", preview.length <= 21)
  }

  @Test
  fun tailPreviewHandlesShortText() {
    assertEquals("abc", tailPreview("abc", maxLines = 24, maxLineChars = 400, scanChars = 8_000))
  }

  // ── capLineLengths ───────────────────────────────────────────────────

  @Test
  fun capLineLengthsReturnsSameInstanceWhenNothingToCut() {
    val text = "short\nlines\nonly"
    assertSame("无需截断时必须原样返回，避免每次重组复制长串", text, capLineLengths(text, 100))
  }

  @Test
  fun capLineLengthsCutsOnlyOverlongLines() {
    val text = "ok\n" + "x".repeat(50) + "\nok"
    val capped = capLineLengths(text, 10)
    val lines = capped.split("\n")
    assertEquals(3, lines.size)
    assertEquals("ok", lines[0])
    assertEquals("x".repeat(10) + "…", lines[1])
    assertEquals("ok", lines[2])
  }

  @Test
  fun capLineLengthsPreservesLineCount() {
    val text = (1..50).joinToString("\n") { "y".repeat(100) }
    assertEquals(50, capLineLengths(text, 10).count { it == '\n' } + 1)
  }

  // ── formatOutputSize ─────────────────────────────────────────────────

  @Test
  fun formatOutputSizeScales() {
    assertEquals("512 B", formatOutputSize(512))
    assertEquals("12 KB", formatOutputSize(12_345))
    assertEquals("1.5 MB", formatOutputSize(1_500_000))
  }
}
