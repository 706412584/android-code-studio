/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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
 * 把一段 Markdown 拆成「文本段 + 代码块」的序列。
 *
 * <p><b>为什么要自己拆，而不是让 markwon 渲染代码块</b>：markwon 把代码块渲染成
 * 一个 [io.noties.markwon.core.spans.CodeBlockSpan]，最终只是一个带背景色的
 * `Spannable`。那个形式做不出「语言标签 + 复制按钮 + 超长折叠」——这三样都是
 * 独立控件，不是文字属性。参考项目（cc-haha）的做法也是把代码块从文本流里
 * 摘出来交给独立组件渲染。
 *
 * <p><b>纯 Kotlin、无 Android 依赖</b>：拆段逻辑全是字符串处理，做成纯函数就能用
 * 普通 JVM 单测覆盖（本模块的测试不带 Robolectric）。边界情况——未闭合围栏、
 * 围栏里出现反引号、`~~~` 分隔符、信息串带额外参数——都靠测试锁住。
 */
object CodeFenceParser {

  /** 一段内容。要么是普通 Markdown 文本，要么是一个代码块。 */
  sealed class Segment {
    data class Text(val markdown: String) : Segment()

    /**
     * 一个围栏代码块。
     *
     * @param language 信息串里的第一个词（如 `kotlin`、`bash`）；无标注时为空串
     * @param code 代码正文，**不含**围栏行，且已去掉尾部换行
     */
    data class Code(val language: String, val code: String) : Segment()
  }

  /** 一个围栏开启行的解析结果。 */
  private data class Fence(
      /** 围栏字符：` 或 ~。闭合必须用同一种字符。 */
      val marker: Char,
      /** 围栏长度（至少 3）。闭合行的长度不得短于开启行。 */
      val length: Int,
      /** 信息串的第一个词，已小写化。 */
      val language: String,
  )

  /**
   * 拆分。
   *
   * <p>刻意不处理行内代码（`` `x` ``）：它属于文本流，由 markwon 渲染成带背景的
   * span 即可，拆出来反而会打散句子。
   */
  fun split(markdown: String): List<Segment> {
    if (markdown.isEmpty()) {
      return emptyList()
    }

    val lines = markdown.split('\n')
    val out = mutableListOf<Segment>()
    val text = StringBuilder()
    var open: Fence? = null
    var code = StringBuilder()

    fun flushText() {
      if (text.isNotEmpty()) {
        out.add(Segment.Text(text.toString()))
        text.setLength(0)
      }
    }

    fun flushCode() {
      val fence = open ?: return
      // 去掉尾部空行：模型常在围栏前留一个空行，留着会让代码块底部多出一段空白。
      val body = code.toString().trimEnd('\n')
      out.add(Segment.Code(fence.language, body))
      code = StringBuilder()
      open = null
    }

    for (line in lines) {
      val current = open
      if (current == null) {
        val fence = parseFenceOpen(line)
        if (fence == null) {
          text.append(line).append('\n')
        } else {
          flushText()
          open = fence
        }
      } else {
        if (isFenceClose(line, current)) {
          flushCode()
        } else {
          code.append(line).append('\n')
        }
      }
    }

    // 未闭合的围栏：流式输出到一半时必然出现（模型还没吐完闭合行）。
    // 按代码块输出而不是丢掉——丢掉会让正在显示的代码整段消失，比格式不完美更糟。
    if (open != null) {
      flushCode()
    }
    flushText()

    return out
  }

  /**
   * 解析围栏开启行。
   *
   * <p>CommonMark 允许最多 3 个前导空格（再多就变成缩进代码块）。围栏长度至少 3，
   * 用同一种字符重复。
   */
  private fun parseFenceOpen(line: String): Fence? {
    var i = 0
    while (i < line.length && i < 3 && line[i] == ' ') {
      i++
    }
    if (i >= line.length) {
      return null
    }
    val marker = line[i]
    if (marker != '`' && marker != '~') {
      return null
    }
    var j = i
    while (j < line.length && line[j] == marker) {
      j++
    }
    val length = j - i
    if (length < 3) {
      return null
    }
    val info = line.substring(j).trim()
    // 反引号围栏的信息串里不允许再出现反引号（CommonMark 规则），
    // 否则 ```` ```foo`bar` ```` 这种行会被误判成围栏。
    if (marker == '`' && info.contains('`')) {
      return null
    }
    // 小写化：模型写 ```Kotlin / ```JSON 的情况很常见，而语法表按小写注册。
    // 归一放在解析这一层，调用方（高亮、语言标签）就不必各自再处理一遍。
    val language = info.split(' ', '\t').firstOrNull().orEmpty().lowercase()
    return Fence(marker, length, language)
  }

  /** 闭合行：同种字符、长度不短于开启行、且除空白外无其他内容。 */
  private fun isFenceClose(line: String, fence: Fence): Boolean {
    var i = 0
    while (i < line.length && i < 3 && line[i] == ' ') {
      i++
    }
    var j = i
    while (j < line.length && line[j] == fence.marker) {
      j++
    }
    if (j - i < fence.length) {
      return false
    }
    return line.substring(j).isBlank()
  }
}
