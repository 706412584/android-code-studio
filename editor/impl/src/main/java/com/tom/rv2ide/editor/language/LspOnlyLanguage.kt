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

package com.tom.rv2ide.editor.language

import com.tom.rv2ide.editor.language.newline.BracketsNewlineHandler
import com.tom.rv2ide.editor.language.utils.CommonSymbolPairs
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import io.github.rosemoe.sora.lang.Language.INTERRUPTION_LEVEL_NONE
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.util.PlainTextAnalyzeManager
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.util.MyCharacter
import io.github.rosemoe.sora.widget.SymbolPairMatch

/**
 * 仅由语言服务器提供智能功能的语言（无内置词法/语法分析）。
 *
 * <p><b>为什么需要这个类</b>：补全请求的入口是 [IDELanguage.doComplete]——它从
 * `languageServer` 取服务器并调用 `complete()`。但编辑器为未知扩展名挂的是
 * sora 的 `EmptyLanguage`，它的 `requireAutoComplete` 是空操作，**根本不看
 * languageServer**。也就是说：只在注册表里注册服务器是不够的，.ts/.dart 文件
 * 必须挂上一个继承 [IDELanguage] 的实现，补全链路才通。
 *
 * <p><b>为什么分析管理器用 [PlainTextAnalyzeManager] 而不是
 * `EmptyLanguage.EmptyAnalyzeManager`</b>（设备实测踩坑）：sora 在弹出补全前
 * 调 `StylesUtils.checkNoCompletion`，其逻辑是「光标处 span 为 null **或** 该 span
 * 带 NO_COMPLETION 位 → 禁止补全」。`EmptyAnalyzeManager` 从不设置 Styles，
 * spans 恒为 null，于是**每一次**补全请求都在触发前被 sora 拦下——现象是
 * 「诊断和悬停都正常，补全永远不弹」。`PlainTextAnalyzeManager` 会设置一个
 * 全文普通样式 span，绕过该拦截。这正是本类存在的第二个理由（第一个是补全入口）。
 *
 * <p><b>为什么不做语法高亮</b>：JS/TS/Dart 在本仓库都没有 tree-sitter 语法库
 * （`editor/impl/build.gradle.kts` 只引入了 java/cpp/json/kotlin/log/xml 六种），
 * 也没有对应的 ANTLR 词法器。普通文本样式与改造前（EmptyLanguage）的高亮行为
 * 一致，不引入退化；补全/诊断/跳转/悬停/格式化全部走 LSP。将来若要高亮，
 * 替换 [getAnalyzeManager] 即可，不影响本类其余部分。
 *
 * @param serverId 注册表中的服务器 id（见 LspHandler.registerLanguageServers）。
 */
class LspOnlyLanguage(private val serverId: String) : IDELanguage() {

  override val languageServer: ILanguageServer?
    get() = ILanguageServerRegistry.getDefault().getServer(serverId)

  private val symbolPairs = CommonSymbolPairs()

  private val newlineHandlers: Array<NewlineHandler> =
      arrayOf(BracketsNewlineHandler({ getIndentAdvance(it ?: "") }, { useTab() }))

  /** 每个编辑器实例独享（BaseAnalyzeManager 持有 receiver/contentRef 状态）。 */
  private val analyzeManager = PlainTextAnalyzeManager()

  override fun getAnalyzeManager(): AnalyzeManager {
    return analyzeManager
  }

  override fun getInterruptionLevel(): Int {
    // 语言服务器请求由协程/线程池承载，不占用编辑器分析线程
    return INTERRUPTION_LEVEL_NONE
  }

  override fun getSymbolPairs(): SymbolPairMatch {
    return symbolPairs
  }

  override fun getNewlineHandlers(): Array<NewlineHandler> {
    return newlineHandlers
  }

  override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
    return 0
  }

  override fun getIndentAdvance(line: String): Int {
    // 括号/花括号的简单缩进推进；语言服务器无缩进协议，够用即可
    var advance = 0
    for (c in line) {
      when (c) {
        '{', '(', '[' -> advance++
        '}', ')', ']' -> advance--
      }
    }
    return maxOf(0, advance) * getTabSize()
  }

  /**
   * 补全前缀字符：标识符字符 + `.`（成员访问）。
   *
   * <p>用 [MyCharacter.isJavaIdentifierPart] 而不是自定义判断：Java 标识符规则
   * 是 JS/TS/Dart 标识符的近似超集（都含字母/数字/下划线/`$`），对前缀提取足够。
   */
  override fun checkIsCompletionChar(c: Char): Boolean {
    return MyCharacter.isJavaIdentifierPart(c) || c == '.'
  }

  override fun destroy() {
    // 无自有资源；语言服务器生命周期由注册表管理
  }
}
