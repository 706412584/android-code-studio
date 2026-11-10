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

package com.tom.rv2ide.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程命令的引号转义。
 *
 * <p><b>为什么值得钉住</b>：`shellQuote` 用在「把工作目录拼进远端命令」这一步
 * （`SshShellBackend` 的 `cd ${shellQuote(cwd)} && <命令>`）。cwd 来自模型可写的
 * 工具参数，因此这里是一个**命令注入边界**：转义写错，一个带 `;` 的路径就能在远端
 * 执行任意命令。
 *
 * <p>测试只看行为（能否安全嵌入），不看具体转义形式——形式可以变，语义不能。
 */
class ShellQuoteTest {

  @Test
  fun wrapsPlainValueInSingleQuotes() {
    assertEquals("'abc'", shellQuote("abc"))
  }

  @Test
  fun emptyValueBecomesEmptyQuotedString() {
    // 空串也必须产出一个合法的空参数（`''`），不能塌成什么都没有——
    // 否则 `cd  && cmd` 会变成语法错误。
    assertEquals("''", shellQuote(""))
  }

  @Test
  fun singleQuoteIsEscapedSoItCannotBreakOut() {
    // 核心安全用例：值里带单引号时，必须用 '"'"' 序列转义，使引号不能提前闭合。
    val quoted = shellQuote("a'b")

    assertEquals("'a'\"'\"'b'", quoted)
    // 结果必须以单引号开头结尾（整体是一个字面量）
    assertTrue(quoted.startsWith("'"))
    assertTrue(quoted.endsWith("'"))
  }

  @Test
  fun commandSeparatorsStayInsideTheLiteral() {
    // 注入尝试：路径里带 `;`、`$()`、反引号、`&&`。转义后整体仍在单引号内，
    // shell 不会解释它们。
    // 用 raw string 避免 `$` 与反引号在 Kotlin 侧的转义规则干扰本用例的意图表达。
    val attack = """/tmp; rm -rf / #$(whoami)`id`&&echo pwned"""
    val quoted = shellQuote(attack)

    // 判据是「转义后能还原成原值」——说明每个特殊字符都在字面量里，没有被解释掉。
    assertEquals("注入串必须原样还原: $quoted", attack, unquote(quoted))
    // 原始的注入字符仍原样在里面（只是不再有解释作用）
    assertTrue(quoted.contains("; rm -rf /"))
  }

  @Test
  fun spacesAndGlobsAreProtected() {
    // 未加引号时空格会拆词、`*` 会展开——转义后都是字面量。
    assertEquals("'/tmp/a b/*.txt'", shellQuote("/tmp/a b/*.txt"))
  }

  @Test
  fun multipleQuotesAreAllEscaped() {
    val quoted = shellQuote("'''")

    // 判据不能是「内部不含单引号」——转义序列本身就是 '"'"' 里带单引号。
    // 正确判据是：每一个原始单引号都各产生一次转义序列，且整体仍以单引号包裹。
    assertEquals(
        "连续三个单引号必须各转义一次: $quoted",
        3,
        quoted.split("'\"'\"'").size - 1)
    assertTrue(quoted.startsWith("'"))
    assertTrue(quoted.endsWith("'"))
  }

  @Test
  fun quotesAreBalancedSoTheLiteralCannotBreakOut() {
    // 更强的判据：把结果按「单引号字面量」规则解析回去，必须恰好还原成原值。
    // 这条能同时抓住「少转义」与「多转义」两类错误。
    val samples = listOf("abc", "a'b", "'''", "/tmp/x;y", "it's a 'test'", "")

    for (sample in samples) {
      val quoted = shellQuote(sample)
      assertEquals("转义后再解析必须还原原值: $sample -> $quoted", sample, unquote(quoted))
    }
  }

  /**
   * 按 POSIX shell 语义解析一个由 [shellQuote] 产生的结果，还原出原始值。
   *
   * <p>覆盖两种片段：
   * - `'...'` 单引号字面量：内容原样保留；
   * - `"..."` 双引号片段：本函数只为解析转义序列 `'"'"'` 而存在，其中只可能包含一个单引号。
   *
   * 逐字符状态机而不是正则：转义序列是三段拼接（收字面量 / 双引号包单引号 / 重进字面量），
   * 用「找下一个单引号」的朴素写法会在双引号那一段出错。
   */
  private fun unquote(quoted: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < quoted.length) {
      when (quoted[i]) {
        '\'' -> {
          i++
          while (i < quoted.length && quoted[i] != '\'') {
            sb.append(quoted[i])
            i++
          }
          i++ // 跳过收尾的单引号
        }
        '"' -> {
          i++
          while (i < quoted.length && quoted[i] != '"') {
            // 双引号片段里只可能有一个单引号（来自转义序列），直接计入值。
            if (quoted[i] == '\\' && i + 1 < quoted.length) i++ // 跳过反斜杠转义
            sb.append(quoted[i])
            i++
          }
          i++ // 跳过收尾的双引号
        }
        else -> i++ // 裸字符（shellQuote 不会产生，容错跳过）
      }
    }
    return sb.toString()
  }
}