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

package com.tom.rv2ide.artificial.agent

/**
 * 把 [DiffResult] 排成可直接显示的文本行。
 *
 * <p><b>为什么把排版与上色拆开</b>：上色需要 `Spannable`（Android 类型），
 * 而排版（行号对齐、增删标记、词级区间的偏移计算）是纯字符串运算。拆开后，
 * 排版这部分可以在 JVM 上单测——而行号列宽算错、词级高亮偏移错位这类问题
 * 恰恰是肉眼看截图时最容易漏掉的（差一列看起来都"差不多"）。
 *
 * <p><b>为什么自己算列宽</b>：行号是右对齐的，若不按最大行号的位数统一列宽，
 * 每行的行号宽度会随数字位数变化，整块 diff 的左侧参差不齐，
 * 而 diff 的可读性几乎全靠这个左边缘的对齐。
 */
object AssistantDiffRenderer {

  /**
   * 一行的排版结果。
   *
   * @param text 已对齐的整行文本（含行号、标记、正文）
   * @param changedRanges 词级高亮的字符区间（相对 [text]）。空表示该行不做词级强调
   *   ——要么它是未配对的行，要么 diff 走了降级路径
   */
  data class RenderedLine(
      val text: String,
      val type: DiffLineType,
      val changedRanges: List<IntRange> = emptyList(),
  )

  /** 增删标记：等宽，保证正文起始列在所有行上一致。 */
  private const val MARKER_EQUAL = "  "
  private const val MARKER_INSERT = "+ "
  private const val MARKER_DELETE = "- "

  /** 行号与正文之间的分隔。用竖线让「行号区」与「内容区」在视觉上分开。 */
  private const val GUTTER = " │ "

  /**
   * 排版 [result]。
   *
   * @param result diff 结果
   * @param maxLines 最多输出多少行；超出部分由调用方通过折叠按钮展开
   * @return 排版后的行；调用方按序拼接即可
   */
  @JvmStatic
  @JvmOverloads
  fun render(result: DiffResult, maxLines: Int = Int.MAX_VALUE): List<RenderedLine> {
    val lines = result.lines
    if (lines.isEmpty()) {
      return emptyList()
    }

    // 列宽按**整个结果**里出现过的最大行号算，而不是逐行算：
    // 逐行算会让每行宽度不同，左边缘就散了。
    val width = maxLineNumberWidth(lines)
    val limit = minOf(maxLines, lines.size)

    val out = ArrayList<RenderedLine>(limit)
    for (i in 0 until limit) {
      out.add(renderLine(lines[i], width))
    }
    return out
  }

  private fun renderLine(line: DiffLine, width: Int): RenderedLine {
    val marker =
        when (line.type) {
          DiffLineType.EQUAL -> MARKER_EQUAL
          DiffLineType.INSERT -> MARKER_INSERT
          DiffLineType.DELETE -> MARKER_DELETE
        }

    val prefix =
        pad(line.oldNo, width) + " " + pad(line.newNo, width) + GUTTER + marker
    val body = line.text

    // 词级高亮的偏移必须加上前缀长度，否则高亮会落在行号上。
    // 这里逐段累加而不是用固定前缀长：段落文本可能被规范化（见下），
    // 用实际写入的长度累加才不会错位。
    val ranges = ArrayList<IntRange>(line.segments.size)
    val sb = StringBuilder(prefix.length + body.length)
    sb.append(prefix)
    if (line.segments.isEmpty()) {
      sb.append(body)
    } else {
      for (segment in line.segments) {
        if (segment.text.isEmpty()) {
          continue
        }
        val start = sb.length
        sb.append(segment.text)
        if (segment.changed) {
          ranges.add(start until sb.length)
        }
      }
    }
    return RenderedLine(sb.toString(), line.type, ranges)
  }

  /**
   * 右对齐一个可空行号。
   *
   * <p>null 输出等宽空格而不是空串：行号缺失时（新增行没有旧行号、删除行没有新行号）
   * 留空串会让该行的正文比其它行左移，正文起始列就不齐了。
   */
  private fun pad(number: Int?, width: Int): String {
    if (number == null) {
      return " ".repeat(width)
    }
    val s = number.toString()
    return if (s.length >= width) s else " ".repeat(width - s.length) + s
  }

  private fun maxLineNumberWidth(lines: List<DiffLine>): Int {
    var max = 1
    for (line in lines) {
      line.oldNo?.let { if (it > max) max = it }
      line.newNo?.let { if (it > max) max = it }
    }
    return max.toString().length
  }

  /**
   * 把 diff 拼成适合复制的纯文本。
   *
   * <p>复制走这个而不是复用 [render] 的结果：屏幕上的行含行号与竖线分隔，
   * 粘到别处（issue、聊天、笔记）时这些是噪声，而 `+`/`-` 前缀才是通用 diff 记法。
   */
  @JvmStatic
  fun toPlainText(result: DiffResult): String {
    val sb = StringBuilder()
    for (line in result.lines) {
      when (line.type) {
        DiffLineType.EQUAL -> sb.append(' ').append(' ')
        DiffLineType.INSERT -> sb.append('+').append(' ')
        DiffLineType.DELETE -> sb.append('-').append(' ')
      }
      sb.append(line.text).append('\n')
    }
    return sb.toString()
  }
}
