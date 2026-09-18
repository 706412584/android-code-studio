/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorInt
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.syntax.SyntaxHighlight
import io.noties.prism4j.DefaultGrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.Syntax
import io.noties.prism4j.Text
import io.noties.prism4j.Visitor

/**
 * 代码块语法高亮，接在 markwon 的 [SyntaxHighlight] 挂钩点上。
 *
 * <p><b>为什么不用 markwon 官方的 syntax-highlight 扩展</b>：本仓库用的 Prism4j 是
 * Nekogram 分支（见 `libs.versions.toml` 的 prism4j 说明）——它把 32 种语言内嵌进 jar、
 * 无需 kapt 注解处理，代价是把 `Grammar`/`Token` 从 `Prism4j` 的嵌套类提升成了顶层类，
 * 与官方扩展二进制不兼容。好在 markwon 的挂钩点本身只是一个单方法接口
 * （`highlight(language, code) → CharSequence`），自己实现反而更可控：配色能直接跟随
 * 应用主题，而不是被官方扩展那套固定色板锁死。
 *
 * <p><b>配色来源</b>：颜色从主题 attr 解析，不写死。深色/浅色主题切换后旧的高亮结果会
 * 重新解析——[forView] 按 Context 缓存实例，而 [AssistantMarkdown] 的 Markwon 实例是
 * 按 TextView 缓存的，两者都在视图重建时刷新。
 *
 * <p><b>为什么语言名要做别名归一</b>：模型写代码块时用 `js` / `sh` / `py` / `yml` /
 * `xml` 这类简写远多于全称，而 Prism4j 的语法表按全称注册（`xml` 甚至注册成 `markup`）。
 * 不归一的话这些代码块会静默失去高亮——用户看到的就是「有时有颜色有时没有」。
 */
class AssistantCodeHighlighter(private val context: Context) : SyntaxHighlight {

  /**
   * 把高亮器挂到 markwon 上的插件。
   *
   * <p>官方的 `syntax-highlight` 制品里有一个现成的插件，但它与 Nekogram 版 Prism4j
   * 二进制不兼容（见类注释）。而插件本身只做一件事——往
   * [MarkwonConfiguration.Builder] 里塞一个 [SyntaxHighlight]，几行就够，
   * 不值得为它引入一个不兼容的制品。
   */
  class Plugin(private val highlighter: AssistantCodeHighlighter) : AbstractMarkwonPlugin() {
    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
      builder.syntaxHighlight(highlighter)
    }
  }

  /** 语法表。构造它会建立内部解析器，按 Context 复用以避免滚动列表里反复构造。 */
  private val prism: Prism4j by lazy { Prism4j(DefaultGrammarLocator()) }

  /** 该 Context 下解析好的颜色。主题切换会产生新的 Context，因此随实例一起失效。 */
  private val colors: Colors by lazy { Colors.from(context) }

  override fun highlight(language: String?, code: String): CharSequence {
    val source = code
    val lang = normalize(language)
    if (lang == null || source.isEmpty()) {
      // 无语言标注或语言不认识：原样返回。markwon 会按普通代码块渲染
      // （等宽字体 + 代码块底色），仍然可读，只是没有 token 配色。
      return source
    }

    val grammar =
        try {
          prism.grammar(lang)
        } catch (e: Throwable) {
          // 语法表损坏或该语言定义有缺陷：不能因为高亮失败就让整条消息显示不出来。
          null
        } ?: return source

    return try {
      val builder = SpannableStringBuilder(source)
      // Visitor 回调给出的 matchedString 可能被 prism 归一化过（例如把 \r\n 折成 \n），
      // 长度未必等于原文片段。因此不按「回调给的字符串」定位，而是自己维护游标，
      // 只信任 textLength()——那个值才是解析器对原文的实际消费长度。
      prism.visit(
          object : Visitor() {
            private var cursor = 0

            override fun visitText(text: Text) {
              cursor += text.textLength()
            }

            override fun visitSyntax(syntax: Syntax) {
              val start = cursor
              cursor += syntax.textLength()
              val color = colors.forToken(syntax.type()) ?: return
              if (start < 0 || cursor > builder.length || start >= cursor) {
                return
              }
              builder.setSpan(
                  ForegroundColorSpan(color),
                  start,
                  cursor,
                  Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
              )
            }
          },
          source,
          grammar,
      )
      builder
    } catch (e: Throwable) {
      // 高亮是锦上添花，任何异常都退回未高亮文本。
      source
    }
  }

  /** Prism4j token 类型 → 主题色。 */
  private class Colors(
      @ColorInt val keyword: Int,
      @ColorInt val string: Int,
      @ColorInt val comment: Int,
      @ColorInt val number: Int,
      @ColorInt val function: Int,
      @ColorInt val typeName: Int,
      @ColorInt val operatorColor: Int,
      @ColorInt val punctuation: Int,
      @ColorInt val tag: Int,
      @ColorInt val attrName: Int,
  ) {
    fun forToken(type: String?): Int? =
        when (type) {
          "keyword",
          "boolean",
          "control-flow",
          "important" -> keyword
          "string",
          "char",
          "string-interpolation",
          "attr-value" -> string
          "comment",
          "prolog",
          "doctype",
          "cdata" -> comment
          "number",
          "constant",
          "symbol" -> number
          "function",
          "method",
          "selector" -> function
          "class-name",
          "maybe-class-name",
          "builtin",
          "namespace",
          "annotation",
          "variable" -> typeName
          "operator",
          "entity",
          "url" -> operatorColor
          "punctuation",
          "separator" -> punctuation
          "tag",
          "deleted" -> tag
          "attr-name",
          "property",
          "key" -> attrName
          else -> null
        }

    companion object {
      /**
       * 从主题取色。
       *
       * <p><b>为什么不直接用主题的主色系</b>：ACS 的主题主色是低饱和的暖灰/米色系
       * （实测深色主题下 `colorPrimary` 解析出 `#DCC2AE`、`colorTertiary` 出 `#E2C0A5`），
       * 两个值肉眼几乎无法区分。语法高亮要的是「一眼能分出 token 类型」，
       * 而不是「与主题色一致」——把主色系套上去的结果就是看上去完全没有高亮。
       *
       * <p>因此这里的取色策略是<b>色相分离</b>：只从主题取「底色/正文色」以保证整体协调，
       * 语法色则用一组色相彼此远离的固定色，并按主题明暗二选一（浅底用深色字、
       * 深底用亮色字），保证在两种主题下都有足够对比度。
       */
      fun from(context: Context): Colors {
        val theme = context.theme
        fun attr(resId: Int, fallback: Int): Int {
          val value = android.util.TypedValue()
          return if (theme.resolveAttribute(resId, value, true)) value.data else fallback
        }

        // 用 colorBackground 的亮度判断明暗主题，而不是 isNightMode 配置：
        // 应用内可以手动切主题而不改变系统配置。
        val background = attr(android.R.attr.colorBackground, 0xFF1B1B1F.toInt())
        val isLight =
            androidx.core.graphics.ColorUtils.calculateLuminance(background) > 0.5

        return if (isLight) light() else dark()
      }

      /** 深色背景上的语法色：高亮度、高饱和，彼此色相分离。 */
      private fun dark(): Colors =
          Colors(
              keyword = 0xFF7AA2F7.toInt(), // 蓝
              string = 0xFF9ECE6A.toInt(), // 绿
              comment = 0xFF6B7089.toInt(), // 暗蓝灰（弱化）
              number = 0xFFFF9E64.toInt(), // 橙
              function = 0xFF7DCFFF.toInt(), // 青
              typeName = 0xFFBB9AF7.toInt(), // 紫
              operatorColor = 0xFF89DDFF.toInt(), // 亮青
              punctuation = 0xFF9AA5CE.toInt(), // 灰蓝
              tag = 0xFFF7768E.toInt(), // 红
              attrName = 0xFFE0AF68.toInt(), // 黄
          )

      /** 浅色背景上的语法色：压暗到中等亮度，保证在浅底上可读。 */
      private fun light(): Colors =
          Colors(
              keyword = 0xFF1E5BC6.toInt(),
              string = 0xFF2E7D32.toInt(),
              comment = 0xFF8A8F98.toInt(),
              number = 0xFFB45309.toInt(),
              function = 0xFF0369A1.toInt(),
              typeName = 0xFF7C3AED.toInt(),
              operatorColor = 0xFF0E7490.toInt(),
              punctuation = 0xFF5B6470.toInt(),
              tag = 0xFFC62828.toInt(),
              attrName = 0xFF8A6D00.toInt(),
          )
    }
  }

  companion object {

    /**
     * 语言名别名 → Prism4j 语法表里的注册名。
     *
     * <p>只收录「模型确实会写、但语法表里没有同名条目」的别名。表里已有的名字
     * （`java` / `kotlin` / `python` …）不必列——[normalize] 会先原样试一次。
     */
    private val ALIASES =
        mapOf(
            "js" to "javascript",
            "jsx" to "javascript",
            "ts" to "javascript",
            "tsx" to "javascript",
            "mjs" to "javascript",
            "node" to "javascript",
            "py" to "python",
            "python3" to "python",
            "sh" to "bash",
            "shell" to "bash",
            "zsh" to "bash",
            "console" to "bash",
            "shell-session" to "bash",
            "yml" to "yaml",
            "xml" to "markup",
            "html" to "markup",
            "svg" to "markup",
            "htm" to "markup",
            "kt" to "kotlin",
            "kts" to "kotlin",
            "gradle" to "groovy",
            "c++" to "cpp",
            "cc" to "cpp",
            "h" to "cpp",
            "hpp" to "cpp",
            "cxx" to "cpp",
            "cs" to "csharp",
            "c#" to "csharp",
            "md" to "markdown",
            "json5" to "json",
            "jsonc" to "json",
            "text" to "",
            "txt" to "",
            "plain" to "",
            "plaintext" to "",
            "none" to "",
        )

    /**
     * 把模型写的语言标注归一成语法表里的名字。
     *
     * <p>`@JvmStatic`：测试是纯 JVM Java 测试（该模块的测试不带 Robolectric），
     * 没有它 Java 侧只能通过 `Companion.normalize(...)` 访问。
     *
     * @return 语法表里可用的语言名；无标注或不认识时返回 null（表示不做高亮）
     */
    @JvmStatic
    fun normalize(language: String?): String? {
      val raw = language?.trim()?.lowercase() ?: return null
      if (raw.isEmpty()) {
        return null
      }
      // 模型偶尔写 ```java title="x" 或 ```java,kt 这种带附加信息的标注，取第一个词。
      val head = raw.split(' ', ',', ';', '|').firstOrNull()?.trim().orEmpty()
      if (head.isEmpty()) {
        return null
      }
      val mapped = ALIASES[head]
      if (mapped != null) {
        // 显式映射到空串 = 该标注表示「无语言」，按不高亮处理。
        return mapped.ifEmpty { null }
      }
      return head
    }
  }
}
