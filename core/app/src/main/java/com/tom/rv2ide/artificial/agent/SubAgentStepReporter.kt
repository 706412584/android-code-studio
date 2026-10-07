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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.ai.agent.AgentEvent
import com.tom.rv2ide.ai.tool.SubAgentRunner
import com.tom.rv2ide.ai.tool.ToolRegistry
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把子会话事件折叠为**步骤播报**（单行文本），经 [SubAgentRunner.ProgressListener] 回传父级。
 *
 * <p><b>为什么只转发「步骤」而不是完整事件流</b>：子 agent 的价值是上下文隔离
 * （见 [com.tom.rv2ide.ai.tool.SubAgentRunner] 类注释），文本与推理增量是它的私有
 * 上下文，转发回主界面等于把隔离掉的噪声搬回来。而「此刻在做什么」是用户有权看到的
 * 实时状态——没有它，跑十几步的子 agent 与卡死无法区分（用户实测反馈）。
 *
 * <p><b>线程</b>：只处理会话循环线程上发出的事件（轮次/工具/终态）。计数器仍用原子类型
 * 以符合 [AgentEvent.Listener] 的线程安全契约；回调异常一律吞掉——进度是尽力而为的
 * 旁路，监听方出错不得让子 agent 运行失败。
 */
class SubAgentStepReporter(private val sink: SubAgentRunner.ProgressListener) :
    AgentEvent.Listener {

  private val turns = AtomicInteger()
  private val steps = AtomicInteger()

  override fun onEvent(event: AgentEvent) {
    val line =
        when (event.type) {
          // 每轮模型请求开始时播报一次。最后一轮（生成结论、无工具调用）通常耗时最长，
          // 没有这条线，状态条会停在最后一个步骤行上，看起来像卡住。
          AgentEvent.Type.TURN_STARTED -> "子 agent 思考中（第 ${turns.incrementAndGet()} 轮）"
          AgentEvent.Type.TOOL_STARTED -> {
            val call = event.toolCall
            val name = call?.let { ToolRegistry.canonicalName(it.name) } ?: "?"
            // 目标复用状态条的提取逻辑（路径压到末两段、命令保留完整内容、JSON 安全）。
            val target =
                call?.let { AssistantActionText.targetOf(name, it.arguments.orEmpty()) } ?: "…"
            "子 agent 步骤 ${steps.incrementAndGet()} · $name: $target"
          }
          AgentEvent.Type.COMPLETED ->
              "子 agent 完成（${turns.get()} 轮 · ${steps.get()} 次工具调用）"
          AgentEvent.Type.FAILED -> "子 agent 失败：${event.message}"
          // 其余事件（文本/推理增量、重试等）不转发：见类注释的隔离原则。
          else -> return
        }
    try {
      sink.onProgress(line)
    } catch (e: RuntimeException) {
      // 契约见 SubAgentRunner.Request#getProgress：进度是旁路。
    }
  }
}
