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

package com.tom.rv2ide.artificial.agent.compose.components.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理派遣卡片的解析与结论提取。
 *
 * <p><b>为什么值得单独测</b>：解析失败会让卡片**静默退回普通工具行**——不报错、不崩溃，
 * 只是「子代理派发看起来跟一次普通调用没区别」。这类退化只有肉眼能发现，而它在真机上
 * 很难复现（要刚好跑到一次 `agent` 调用）。所以这里把三类派遣形态、以及若干「不该出卡」
 * 的边界钉死。
 */
class SubAgentDispatchCardTest {

  // ── 单代理派遣 ────────────────────────────────────────────────────────

  @Test
  fun parsesPlainAgentTask() {
    val parsed =
        parseSubAgentDispatch(
            "agent",
            """{"task":"把 UserRepository 的缓存抽出来","mode":"code"}""",
        )
    assertEquals("把 UserRepository 的缓存抽出来", parsed?.taskText)
    assertEquals("code", parsed?.mode)
    assertTrue("通用 agent 不带代理名", parsed?.agentName.isNullOrEmpty())
    assertEquals(0, parsed?.pipelineCount)
  }

  @Test
  fun parsesBuiltinAgentNameFromToolName() {
    val parsed = parseSubAgentDispatch("agentb_reviewer", """{"task":"审查这次改动"}""")
    assertEquals("reviewer", parsed?.agentName)
    assertEquals("审查这次改动", parsed?.taskText)
  }

  @Test
  fun parsesCustomAgentNameFromToolName() {
    val parsed = parseSubAgentDispatch("agentx_my-helper", """{"task":"跑一遍 lint"}""")
    assertEquals("my-helper", parsed?.agentName)
  }

  @Test
  fun fallsBackToPromptWhenTaskMissing() {
    val parsed =
        parseSubAgentDispatch("agent", """{"prompt":"第一行是任务\n第二行是细节"}""")
    assertEquals("第一行是任务 第二行是细节", parsed?.taskText)
  }

  @Test
  fun collapsesWhitespaceInTaskText() {
    val parsed = parseSubAgentDispatch("agent", """{"task":"  多  个\n\n空白\t字符  "}""")
    assertEquals("多 个 空白 字符", parsed?.taskText)
  }

  @Test
  fun truncatesVeryLongTaskText() {
    val long = "x".repeat(500)
    val parsed = parseSubAgentDispatch("agent", """{"task":"$long"}""")
    assertTrue("超长任务描述必须截断", (parsed?.taskText?.length ?: 0) < 200)
    assertTrue(parsed?.taskText?.endsWith("…") == true)
  }

  // ── 流水线派遣 ────────────────────────────────────────────────────────

  @Test
  fun parsesPipelineWithCount() {
    val parsed =
        parseSubAgentDispatch(
            "agent_pipeline",
            """{"agents":[
                 {"id":"a","prompt":"调研模块 A"},
                 {"id":"b","prompt":"调研模块 B","depends_on":["a"]},
                 {"id":"c","prompt":"汇总"}
               ]}""",
        )
    assertEquals(3, parsed?.pipelineCount)
    assertEquals("调研模块 A", parsed?.taskText)
  }

  // ── 不该出卡 ──────────────────────────────────────────────────────────

  @Test
  fun rejectsMissingArgs() {
    assertNull(parseSubAgentDispatch("agent", null))
    assertNull(parseSubAgentDispatch("agent", "  "))
  }

  @Test
  fun rejectsMalformedJson() {
    assertNull(parseSubAgentDispatch("agent", "{not json"))
  }

  @Test
  fun rejectsTasklessArgs() {
    assertNull(parseSubAgentDispatch("agent", """{"mode":"code"}"""))
    assertNull(parseSubAgentDispatch("agent", """{"task":"   "}"""))
  }

  @Test
  fun rejectsNonAgentTools() {
    assertNull(parseSubAgentDispatch("file_read", """{"task":"x"}"""))
    assertNull(parseSubAgentDispatch(null, """{"task":"x"}"""))
  }

  @Test
  fun rejectsEmptyPipeline() {
    assertNull(parseSubAgentDispatch("agent_pipeline", """{"agents":[]}"""))
  }

  // ── 结论提取 ──────────────────────────────────────────────────────────

  @Test
  fun stripsTrailingStatsLine() {
    val content = "缓存已抽出到 CacheStore。\n\n（子 agent 模式: code，轮次 4，工具调用 9）"
    assertEquals("缓存已抽出到 CacheStore。", extractSubAgentSummary(content))
  }

  @Test
  fun stripsBuiltinAgentStatsLine() {
    val content = "共发现 3 处问题。\n\n（内置 agent: reviewer，轮次 2，工具调用 5）"
    assertEquals("共发现 3 处问题。", extractSubAgentSummary(content))
  }

  @Test
  fun stripsCustomAgentStatsLine() {
    val content = "lint 通过。\n\n（自定义 agent: lint-helper）"
    assertEquals("lint 通过。", extractSubAgentSummary(content))
  }

  @Test
  fun keepsMultiLineConclusion() {
    val content = "第一段。\n\n第二段。\n\n（子 agent 模式: explore，轮次 1，工具调用 2）"
    assertEquals("第一段。\n\n第二段。", extractSubAgentSummary(content))
  }

  @Test
  fun returnsNullForEmptyOrStatsOnly() {
    assertNull(extractSubAgentSummary(""))
    assertNull(extractSubAgentSummary("   \n  "))
    assertNull(extractSubAgentSummary("（子 agent 模式: explore，轮次 1，工具调用 2）"))
  }

  @Test
  fun keepsConclusionWithoutStatsLine() {
    assertEquals("只有结论", extractSubAgentSummary("只有结论"))
  }
}
