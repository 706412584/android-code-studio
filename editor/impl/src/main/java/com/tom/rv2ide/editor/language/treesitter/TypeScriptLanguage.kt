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
import com.tom.rv2ide.lsp.typescript.TypeScriptLanguageServer
import com.tom.rv2ide.treesitter.TSLanguage
import io.github.rosemoe.sora.lang.Language.INTERRUPTION_LEVEL_NONE
import io.github.rosemoe.sora.util.MyCharacter

/**
 * [TreeSitterLanguage] implementation for TypeScript / JavaScript files.
 *
 * <p><b>两个语法、一套查询</b>：TS 与 TSX 用**不同**的语法库
 * （`libtree-sitter-typescript.so` / `libtree-sitter-tsx.so`，上游 v0.23.2 同版本），
 * 因为 TSX 需要 JSX 语法扩展。选哪个由**文件扩展名**决定，见 [factoryFor]。
 *
 * <p><b>查询目录按扩展名分**：`assets/editor/treesitter/ts/`（.ts/.mts/.cts）
 * 与 `tsx/`（.tsx）；JS 系扩展（.js/.jsx/.mjs/.cjs）复用 tsx 语法与 tsx 查询——
 * JSX 是 JS 的超集扩展，`<div>` 在 ts 语法下是语法错误（实测），而普通 JS
 * 在 tsx 语法下**无歧义**（实测 RN 模板的 App.tsx 与 metro.config.js 解析均无错），
 * 且 tsx 查询集 = ecma + typescript + jsx 三层叠加，对纯 JS 文件同样适用。
 *
 * <p><b>为什么用 `ts`/`tsx` 而不是 `typescript` 作目录名**：语法资产的目录名同时是
 * [TreeSitterLanguage] 的 langType，[IDEEditor.setupLanguage] 会用**文件扩展名**去
 * 配色方案里找 `languages[扩展名]`。扩展名集合里本就有 `ts` 与 `tsx` 两项，
 * 所以目录名 = 扩展名，配色键也同名，三处不用再各自映射一次。
 */
open class TypeScriptLanguage(
    context: Context,
    /** 语法与查询目录的类型标识（"ts" / "tsx"）。 */
    private val langType: String,
) : TreeSitterLanguage(context, loadLanguage(context, langType), langType) {

  companion object {

    const val TS_TYPE_TS = "ts"
    const val TS_TYPE_TSX = "tsx"

    /** TypeScript 系扩展名（无 JSX）。 */
    val TS_EXTENSIONS = setOf("ts", "mts", "cts")

    /** JS/JSX 系扩展名（按 tsx 语法处理）。 */
    val TSX_EXTENSIONS = setOf("tsx", "js", "jsx", "mjs", "cjs")

    /** .ts 系文件的工厂。 */
    @JvmField val FACTORY_TS = Factory { TypeScriptLanguage(it, TS_TYPE_TS) }

    /** .tsx/.js 系文件的工厂。 */
    @JvmField val FACTORY_TSX = Factory { TypeScriptLanguage(it, TS_TYPE_TSX) }

    /**
     * 按文件扩展名选工厂；不认识的扩展名返回 null。
     *
     * <p>注册表按扩展名精确匹配，每个扩展名注册一个条目，但同一个工厂可复用。
     */
    @JvmStatic
    fun factoryFor(extension: String): Factory<TypeScriptLanguage>? {
      return when (extension.lowercase()) {
        in TS_EXTENSIONS -> FACTORY_TS
        in TSX_EXTENSIONS -> FACTORY_TSX
        else -> null
      }
    }

    /**
     * 加载内置语法实例（langType "ts" → libtree-sitter-typescript.so，
     * "tsx"/"js" 系 → libtree-sitter-tsx.so，见 [grammarNameFor]）。
     *
     * <p>加载失败抛异常：父类构造参数非空（`TSLanguage` 无 EMPTY 常量）。
     * 异常由 `IDEEditor.createLanguage` 的协程捕获并记日志，编辑器退化为
     * 无高亮但不崩溃——与 dart 分支、以及 TsLanguageSpec 解析失败的既有语义一致。
     */
    private fun loadLanguage(context: Context, langType: String): TSLanguage {
      val name = grammarNameFor(langType)
      return TSLanguage.loadLanguage(context, name)
          ?: error(
              "内置 $langType 语法库加载失败：libtree-sitter-$name.so 无法 dlopen 或缺少 tree_sitter_$name 符号。"
          )
    }

    /** langType → 语法库名（与 jniLibs 的文件名一致）。 */
    private fun grammarNameFor(langType: String): String {
      return if (langType == TS_TYPE_TS) "typescript" else "tsx"
    }
  }

  override val languageServer: ILanguageServer?
    get() = ILanguageServerRegistry.getDefault().getServer(TypeScriptLanguageServer.SERVER_ID)

  override fun checkIsCompletionChar(c: Char): Boolean {
    return MyCharacter.isJavaIdentifierPart(c) || c == '.' || c == '$'
  }

  override fun getInterruptionLevel(): Int {
    return INTERRUPTION_LEVEL_NONE
  }
}
