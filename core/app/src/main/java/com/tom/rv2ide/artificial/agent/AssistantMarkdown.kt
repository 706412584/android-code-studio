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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * 助手回复的 Markdown 渲染。
 *
 * <p><b>为什么必须渲染 Markdown</b>：模型回答里大量出现代码块、列表、表格与链接。
 * 直接塞进 TextView 会把这些符号原样显示——`**重点**` 带着星号、代码块没有缩进与背景、
 * 链接不可点。用户面对的是「一堆符号」，而不是可读的答案。
 *
 * <p><b>Markwon 实例必须复用</b>：它的构造会解析插件并建立内部解析器，每次绑定时新建
 * 在滚动列表里是明显的浪费。这里按 TextView 缓存——不同 TextView 的样式配置可能不同，
 * 按控件缓存比全局单例更安全（主题切换后旧实例仍带旧配色）。
 */
object AssistantMarkdown {

  private val cache = java.util.WeakHashMap<TextView, Markwon>()

  /** 取（或创建）与该 TextView 配套的渲染器。 */
  fun forView(view: TextView): Markwon =
      cache.getOrPut(view) {
        Markwon.builder(view.context)
            // 删除线：模型常用 ~~旧值~~ 表示被替换的内容
            .usePlugin(StrikethroughPlugin.create())
            // 链接：让 URL 可点，而不是一段无法操作的纯文本
            .usePlugin(LinkifyPlugin.create())
            // 代码块语法高亮。没有语言标注或语言不认识时高亮器原样返回，
            // markwon 仍会按普通代码块渲染（等宽 + 底色）。
            .usePlugin(AssistantCodeHighlighter.Plugin(AssistantCodeHighlighter(view.context)))
            .build()
      }

  /**
   * 渲染 Markdown 到 TextView。
   *
   * <p>解析失败时回退为纯文本：模型偶尔会输出不闭合的代码块围栏（尤其在流式过程中），
   * 解析器可能抛异常。内容不完美也要让用户看到，而不是整条消息变成空白。
   */
  fun render(view: TextView, markdown: String) {
    try {
      forView(view).setMarkdown(view, markdown)
    } catch (e: Throwable) {
      view.text = markdown
    }
  }

  /**
   * 判断文本是否值得走 Markdown 渲染。
   *
   * <p>过程日志（工具调用摘要、错误提示）是纯文本，且量大。对它们跑一遍解析器纯属浪费，
   * 而且会把这些行里的 `*`、`_`、`[` 等字符误当标记处理，导致显示变形。
   */
  fun looksLikeMarkdown(text: String): Boolean {
    if (text.isEmpty()) {
      return false
    }
    // 只认那些「几乎不可能是普通文本」的信号，避免把路径里的下划线误判成强调。
    return text.contains("```") ||
        text.contains("\n- ") ||
        text.contains("\n* ") ||
        text.contains("\n1. ") ||
        text.contains("\n#") ||
        text.contains("**") ||
        text.contains("\n|") ||
        text.startsWith("# ") ||
        text.startsWith("- ") ||
        text.startsWith("```")
  }
}
