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
 * 「复制」菜单可以提供的两种形态。
 *
 * <p>做成枚举而不是两个布尔开关：调用方要按它决定菜单项文案与图标，
 * 枚举在 `when` 上是穷尽的，日后加第三种（例如「复制为 HTML」）编译器会替我们找出所有分派点。
 */
enum class CopyFormat {
  /** 内存里存着的 markdown 原文。 */
  MARKDOWN,

  /** 去掉标记后的纯文本，供粘贴到不支持 markdown 的地方。 */
  PLAIN_TEXT,
}

/**
 * 一条聊天引用：用户选中一段文字后「引用提问」，它会作为上下文附加到下一条消息。
 *
 * <p>@param messageId 被引用消息的稳定 id（{@code AssistantMessageAdapter.Message.id}）。
 *   不用下标：列表会因流式更新而增删，下标指向的消息会漂移。
 * @param role 消息角色（"user" / "assistant"）。用于渲染「引用自 助手回复」这类标签。
 * @param quote 用户选中的文字。可能多行，也可能含 markdown 标记——原样保留，
 *   渲染时再逐行加引用前缀（见 [AssistantMessageActions.referenceContext]）。
 * @param sourceName 来源显示名（如消息所在会话名）。仅用于界面展示，**不进上下文块**：
 *   上下文块是给模型看的，模型不需要知道「这段来自哪个会话」。
 */
data class ChatReference(
    val messageId: Long,
    val role: String,
    val quote: String,
    val sourceName: String = "",
) {
  /**
   * 回链地址，形式对齐 cc-haha 的 `chat://<role>/<messageId>`。
   *
   * <p>现在还没有「点引用跳回原消息」的功能，但先把格式定死：等真要做回链时，
   * 历史会话里已经落盘的引用不必再迁移一次。
   */
  val path: String
    get() = "chat://$role/$messageId"
}

/**
 * 悬浮助手「消息级操作」的纯逻辑层：复制形态选择、markdown→纯文本、引用渲染、耗时格式化。
 *
 * <p><b>为什么单独抽成一个 object</b>：这四件事全都只做字符串处理，却原先无处安放——
 * 它们天然长在 Adapter / View 里，而那些类要 Android Context 与 ViewBinding 才能构造，
 * 于是这段逻辑一条测试都写不了。可它恰恰是最容易出错的部分（markdown 转换规则多、
 * 引用要多行加前缀、耗时分支多），出错时用户看到的是「复制出来一团符号」或
 * 「引用块漏出半行」，很难在真机上注意到。
 *
 * <p><b>本类不引任何 `android.*`</b>：全部是纯 Kotlin + JDK 标准库，
 * 因此可以在普通 JVM 单测里直接跑（本模块测试不带 Robolectric）。
 */
object AssistantMessageActions {

  // ---- 1. 复制内容选择 ----

  /**
   * 决定「复制」菜单里应该给出哪些项。
   *
   * <p><b>为什么不能两个选项无脑都给</b>：不含 markdown 的文本（过程日志、错误提示）
   * 转换后与原文一字不差，用户点了两个不同的菜单项却得到同样的结果，
   * 只会以为其中一个坏了。
   *
   * <p>判定**复用** [AssistantMarkdown.looksLikeMarkdown] 而不是自己写一套：
   * 屏幕上是否走 markdown 渲染就是由它决定的，复制选项必须与「所见」一致——
   * 自己再写一套判定，两边迟早会分叉（屏幕上渲染成了 markdown，菜单却只给一个选项）。
   *
   * <p>MARKDOWN 永远在列表里：它是原文，任何情况下都可复制。
   */
  @JvmStatic
  fun availableCopyFormats(markdownText: String): List<CopyFormat> =
      if (AssistantMarkdown.looksLikeMarkdown(markdownText)) {
        listOf(CopyFormat.MARKDOWN, CopyFormat.PLAIN_TEXT)
      } else {
        listOf(CopyFormat.MARKDOWN)
      }

  // ---- 2. markdown → 纯文本 ----

  /**
   * 把 markdown 原文转成适合粘贴到纯文本场景的形式：去掉标记符号，保留结构与可读性。
   *
   * <p><b>它不是渲染器</b>：markwon 渲染出来的是 `Spannable`，直接 `toString()` 会得到
   * 一个丢了所有结构、又把代码块挤成一行的结果。这里要的是「换到微信/终端里还能读懂」，
   * 因此保留换行、列表符号、代码缩进，只剥掉那些纯文本里没法呈现的标记。
   *
   * <p><b>代码块内容必须原样保留</b>：代码里的 `*`（解引用、乘法）、`#`（预处理指令）、
   * `_`（标识符）如果被当作 markdown 标记处理，复制出去的代码就编译不过了。
   * 围栏的切分交给 [CodeFenceParser]（已有测试覆盖），行内代码在这里用占位符保护。
   *
   * <p>刻意处理的取舍：
   * <ul>
   *   <li>列表符号 `- ` / `* ` / `1. ` **保留**——纯文本里它就是可读的结构，
   *       去掉反而让几条并列项连成一段；
   *   <li>末尾空行会被去掉：复制一条消息时尾部空行只是噪音；
   *   <li>`\r\n` 归一为 `\n`，避免 Windows 换行符粘出去变成可见的 `^M`。
   * </ul>
   */
  @JvmStatic
  fun toPlainText(markdownText: String): String {
    if (markdownText.isEmpty()) {
      return ""
    }
    val normalized = markdownText.replace("\r\n", "\n").replace('\r', '\n')
    val out = StringBuilder()
    for (segment in CodeFenceParser.split(normalized)) {
      when (segment) {
        is CodeFenceParser.Segment.Text -> out.append(convertTextSegment(segment.markdown))
        // 代码正文逐字照抄，只丢掉围栏行本身（split 已经帮我们丢掉了）。
        is CodeFenceParser.Segment.Code -> out.append(segment.code).append('\n')
      }
    }
    // split 会给每段文本补一个行尾换行（包括最后一段），于是结果总多一个空行。
    // 复制场景下尾部空行没有意义，去掉。
    return out.toString().trimEnd('\n')
  }

  /**
   * 行内代码的占位符。
   *
   * <p>用 `\u0000`（NUL）作哨兵：它不可能出现在正常文本里，因此还原时不会误伤原文；
   * 换成 `@@0@@` 这类可打印串，遇到文本里恰好写了同样的串就会还原错位置。
   */
  private const val PLACEHOLDER = '\u0000'

  /** 行内代码：`` `x` ``。 */
  private val INLINE_CODE = Regex("`([^`]*)`")

  /** 链接与图片：`[文字](url)` / `![alt](url)`。图片也按链接处理，免得多出一个孤零零的 `!`。 */
  private val LINK = Regex("!?\\[([^\\]]*)\\]\\(([^)]*)\\)")

  /**
   * 强调类标记。
   *
   * <p>每一条都要求「开启符后紧跟非空白、闭合符前紧跟非空白」，否则
   * `2 * 3 * 4` 里的两个星号会被配成一对斜体，把乘法算式吃掉——
   * 这在模型的解释性文本里很常见。下划线额外要求两侧不是单词字符，
   * 否则 `a_b_c` 这种标识符会被拆成强调（代码里满地都是）。
   *
   * <p>顺序必须是「长的先跑」：`~~` → `**` → `__` → `*` → `_`。
   * 反过来 `**粗**` 会先被单星号规则咬掉一对，剩下 `*粗*` 再跑一轮。
   */
  private val STRIKETHROUGH = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")

  private val BOLD_STAR = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*")
  private val BOLD_UNDER = Regex("(?<![\\w])__(?=\\S)(.+?)(?<=\\S)__(?![\\w])")
  private val ITALIC_STAR = Regex("\\*(?=\\S)(.+?)(?<=\\S)\\*")
  private val ITALIC_UNDER = Regex("(?<![\\w])_(?=\\S)(.+?)(?<=\\S)_(?![\\w])")

  /** 引用前缀 `> ` / `>> `，允许最多 3 个前导空格（再多就是缩进代码块了）。 */
  private val BLOCKQUOTE = Regex("^[ \\t]{0,3}(?:>[ \\t]?)+")

  /** ATX 标题 `# 标题`。要求 `#` 后必须有空白，`#Title` 在 markdown 里本就不是标题。 */
  private val HEADING = Regex("^[ \\t]{0,3}#{1,6}[ \\t]+")

  /**
   * 处理一段**非代码块**的文本。
   *
   * <p>按行处理而不是整体处理：标题、引用这些标记只在行首有意义，
   * 整体跑正则会把 `a > b` 里的 `>` 也当成引用符。
   */
  private fun convertTextSegment(markdown: String): String =
      markdown.split('\n').joinToString("\n") { convertLine(it) }

  /** 单行：先剥行首的块级标记，再剥行内标记。 */
  private fun convertLine(line: String): String {
    if (line.isEmpty()) {
      return line
    }
    var s = BLOCKQUOTE.replace(line, "")
    s = HEADING.replace(s, "")
    return convertInline(s)
  }

  /**
   * 行内标记：行内代码 → 链接 → 强调。
   *
   * <p><b>行内代码必须先挖出来</b>：`` `a * b * c` `` 里的星号一旦落到强调规则上，
   * 会被配成一对斜体把内容改掉。所以先换成占位符，等其余规则跑完再原样填回。
   */
  private fun convertInline(line: String): String {
    val codes = ArrayList<String>()
    var s =
        INLINE_CODE.replace(line) { m ->
          codes.add(m.groupValues[1])
          "$PLACEHOLDER${codes.size - 1}$PLACEHOLDER"
        }
    s =
        LINK.replace(s) { m ->
          val text = m.groupValues[1]
          val url = m.groupValues[2]
          // 没有 url（`[文字]()`）时补一对空括号只会显得像写坏了，直接给文字。
          if (url.isBlank()) text else "$text ($url)"
        }
    s = STRIKETHROUGH.replace(s, "$1")
    s = BOLD_STAR.replace(s, "$1")
    s = BOLD_UNDER.replace(s, "$1")
    s = ITALIC_STAR.replace(s, "$1")
    s = ITALIC_UNDER.replace(s, "$1")
    for (i in codes.indices) {
      s = s.replace("$PLACEHOLDER$i$PLACEHOLDER", codes[i])
    }
    return s
  }

  // ---- 3. 引用（选中文本提问） ----

  /**
   * 把引用渲染成附加到用户消息前的上下文块。
   *
   * <p><b>为什么每行都要加前缀</b>：模型（与人）看到的是一段纯文本。只给第一行加 `> `、
   * 后面几行裸奔，多行引文就会「漏出」引用块——模型分不清哪些是引用的原文、
   * 哪些是用户自己说的话，而这正是引用机制存在的唯一理由。
   *
   * <p>空列表返回**空字符串**而不是 null：调用方直接拼接即可，不必到处判空。
   * 空白的 quote 直接跳过——它提供不了任何上下文，留着只会往消息里塞一个空的引用框。
   *
   * <p>形如：
   * <pre>
   * &gt; 引用自 助手回复：
   * &gt; 选中的那几行
   * </pre>
   */
  @JvmStatic
  fun referenceContext(references: List<ChatReference>): String {
    val blocks = ArrayList<String>()
    for (ref in references) {
      val quote = ref.quote.trimEnd('\n')
      if (quote.isBlank()) {
        continue
      }
      val lines = ArrayList<String>()
      lines.add(quoteLine("引用自 ${roleLabel(ref.role)}："))
      // 空行也补一个 `>`：否则 markdown 会认为引用块在空行处结束，
      // 后面几行引文就掉出引用块了。
      for (line in quote.split('\n')) {
        lines.add(if (line.isEmpty()) ">" else quoteLine(line))
      }
      blocks.add(lines.joinToString("\n"))
    }
    return blocks.joinToString("\n")
  }

  /** 给一行加引用前缀。 */
  private fun quoteLine(text: String): String = "> $text"

  /** 角色 → 上下文块里的标签。认不出的角色原样显示，便于日后扩展（如 tool/system）。 */
  private fun roleLabel(role: String): String =
      when (role) {
        "assistant" -> "助手回复"
        "user" -> "用户消息"
        else -> role
      }

  // ---- 4. 轮次耗时 ----

  /**
   * 格式化一段耗时，用于「这一轮花了多久」。
   *
   * <p><b>0 与负数返回空串而不是 "0s"</b>：历史会话（或尚未结束的轮次）没有耗时数据，
   * 返回 "0s" 会让列表里出现一长串毫无意义的 "0s"；返回空串，调用方据此**不显示**这一项。
   * 这是「没有数据」与「瞬间完成」的区分，只有调用方能决定怎么呈现，本函数只如实表达。
   *
   * <p>不用 `String.format` 是因为它依赖默认 Locale：某些区域用逗号作小数点，
   * 结果会变成 `0,8s`，同一个应用在不同手机上显示不一致。手算整数与小数位反而更稳。
   */
  @JvmStatic
  fun formatDuration(millis: Long): String {
    if (millis <= 0L) {
      return ""
    }
    if (millis < 60_000L) {
      // 截断而非四舍五入：999ms 显示成 "1.0s" 会让人以为已经到 1 秒了。
      val tenths = millis / 100L
      return "${tenths / 10}.${tenths % 10}s"
    }
    val totalSeconds = millis / 1000L
    return "${totalSeconds / 60}分${totalSeconds % 60}秒"
  }
}
