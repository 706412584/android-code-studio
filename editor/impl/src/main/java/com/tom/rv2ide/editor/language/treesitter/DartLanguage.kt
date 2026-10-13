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

package com.tom.rv2ide.editor.language.treesitter

import android.content.Context
import com.tom.rv2ide.editor.language.treesitter.TreeSitterLanguage.Factory
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.dart.DartLanguageServer
import com.tom.rv2ide.treesitter.TSLanguage
import com.tom.rv2ide.treesitter.TSLanguageCache
import io.github.rosemoe.sora.lang.Language.INTERRUPTION_LEVEL_NONE
import io.github.rosemoe.sora.util.MyCharacter

/**
 * [TreeSitterLanguage] implementation for Dart (Flutter) files.
 *
 * <p><b>为什么不走 `androidide-ts-dart` Maven 依赖</b>：Maven Central 上
 * `io.github.mohammed-baqer-null` 只发布到 log/xml/properties/python 等 6+2 种语言，
 * **没有 dart/typescript**。这三个语法库（dart / typescript / tsx）由本仓库交叉编译
 * 后放在 `editor/impl/src/main/jniLibs/<abi>/`（同 LibGdx 的 .so 放置方式），
 * 用 `TSLanguage.loadLanguage(context, name)` 动态加载——它走
 * `dlopen(applicationInfo.nativeLibraryDir + "/libtree-sitter-<name>.so")`，
 * 对 APK 安装目录里的任何 .so 都成立，无需预先 `System.loadLibrary`。
 *
 * <p><b>语法版本纪律</b>：内置运行时（android-tree-sitter 1.0.1）只接受
 * ABI 13/14（`ts_parser_set_language` 校验 `version-13 <= 1`），而 tree-sitter 上游
 * 0.25+ 生成的 parser.c 是 ABI 15。因此本目录的 dart 语法取自
 * `UserNobody14/tree-sitter-dart` 的 **c1222f5**（该提交 2025-10-03 为最后一个
 * ABI 14 版本，随后 d4d8f3e 升到 15）；typescript/tsx 取自上游 **v0.23.2**
 * （ABI 14）。升级这些语法前必须先确认 ABI 仍为 14，否则设备端 parser 会静默失败。
 *
 * <p><b>语言名与 .so 文件名必须一致</b>：`loadLanguage` 把 name 拼进
 * `libtree-sitter-<name>.so` 并 `dlsym("tree_sitter_<name>")`。dart 语法导出的
 * 符号是 `tree_sitter_dart`，故 name = "dart"。
 */
class DartLanguage(context: Context) :
    TreeSitterLanguage(context, loadDartLanguage(context), TS_TYPE) {

  companion object {

    const val TS_TYPE = "dart"

    @JvmField val FACTORY = Factory { DartLanguage(it) }

    /** 内置语法库名（对应 jniLibs 里的 libtree-sitter-dart.so）。 */
    private const val GRAMMAR_NAME = "dart"

    /**
     * 加载内置的 dart 语法实例。
     *
     * <p>加载失败抛异常而不是返回 null：[TreeSitterLanguage] 的构造参数是
     * 非空 `TSLanguage`（无 EMPTY 常量）。抛出的异常由 `IDEEditor.createLanguage`
     * 的协程捕获并记日志（`setupTsLanguageJob.invokeOnCompletion`），编辑器
     * 退化为无高亮，**不会崩溃**——这与 TsLanguageSpec 解析失败（抛
     * IllegalArgumentException）的既有失败语义一致。
     *
     * <p>生产环境不应出现此失败：.so 随 APK 打包（jniLibs），符号已静态校验
     * （见类注释的编译验证记录）。
     */
    private fun loadDartLanguage(context: Context): TSLanguage {
      return TSLanguage.loadLanguage(context, GRAMMAR_NAME)
          ?: error(
              "内置 dart 语法库加载失败：libtree-sitter-$GRAMMAR_NAME.so 无法 dlopen 或缺少 tree_sitter_$GRAMMAR_NAME 符号。"
          )
    }
  }

  override val languageServer: ILanguageServer?
    get() = ILanguageServerRegistry.getDefault().getServer(DartLanguageServer.SERVER_ID)

  override fun checkIsCompletionChar(c: Char): Boolean {
    return MyCharacter.isJavaIdentifierPart(c) || c == '.'
  }

  override fun getInterruptionLevel(): Int {
    // 分析在编辑器线程池执行，LSP 请求不占用它
    return INTERRUPTION_LEVEL_NONE
  }
}
