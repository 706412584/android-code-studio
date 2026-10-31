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

package com.assistant.ui

import com.assistant.ui.model.MessageTimeline
import com.tom.rv2ide.ai.agent.AgentEvent
import com.tom.rv2ide.ai.tool.api.ToolNames

/**
 * 把内核的 [AgentEvent] 流归约成 [MessageTimeline] 的条目。
 *
 * <p><b>为什么单独抽出来</b>：ACS 那边这段逻辑内嵌在 `FloatingAssistantView.handleEvent`
 * 里（约 400 行），与 ViewBinding、生命周期、节流、抽屉圆点混在一起，无法单测。
 * 剥离后的 App 只有一条渲染路径，事件→数据这一步是纯函数式的状态机，
 * 抽出来才能在不启动 Android 的前提下验证「一次运行会产生哪些条目」。
 *
 * <p><b>为什么不是纯函数</b>：它要持有流式指针（当前气泡 id / 当前思考块 id /
 * 当前工具卡 id）。这些指针是「上一事件留下的状态」，必须跨事件保留——
 * 这正是 `TEXT_DELTA` 能追加到同一个气泡而不是每次新建一条的原因。
 *
 * <p><b>线程约定</b>：全部方法**必须在同一线程调用**（本 App 用主线程）。
 * 内核的事件可能来自 HTTP 线程，调用方负责切换；这里不做同步——
 * 加锁只会让「事件顺序」与「渲染顺序」之间多一层不可控的排队。
 *
 * <p><b>与 ACS 的行为差异（刻意的）</b>：
 * <ul>
 *   <li>没有多会话隔离：本 App 一次只显示一个会话，后台会话的概念不存在。
 *   <li>没有重试卡片：ACS 会为 `STREAM_RETRYING` 弹一张瞬时卡片；剥离版把重试
 *       记在状态里（[retryAttempt]），由界面决定怎么显示。
 *   <li>改动汇总卡在 `RUN_FINISHED` 时插入（与 ACS 一致）：运行中插入会被后续消息
 *       推到上面去，用户下次看到它时已经不在「刚才那轮」的位置。
 * </ul>
 */
class EventReducer(private val timeline: MessageTimeline) {

  /** 当前正在流式写入的助手气泡 id；null 表示还没有。 */
  var streamingMessageId: Long? = null
    private set

  /** 当前正在累积的思考块 id；null 表示没有。 */
  var thinkingId: Long? = null
    private set

  /** 最近一张工具卡片的 id。 */
  var lastToolCardId: Long? = null
    private set

  /** 最近一次 `STREAM_RETRYING` 的重试序号（从 1 开始）；0 表示本轮没重试过。 */
  var retryAttempt: Int = 0
    private set

  /** 本轮是否已经开始（收到 `RUN_STARTED`）。 */
  var isRunning: Boolean = false
    private set

  /**
   * 本轮的全部文件改动：`(文件路径, diffId)`。
   *
   * <p>运行结束时一次性落成汇总卡（见 [RUN_FINISHED] 分支）。运行中每改一个文件就往
   * 这里加一条，不在对话流里逐条插卡片——十几张 diff 会把正文挤出屏幕。
   */
  private val pendingDiffs = ArrayList<Pair<String, String?>>()

  /** 上一次收到事件时的运行起点（毫秒）；用于回填本轮耗时。0 表示未记录。 */
  private var runStartedAtMs = 0L

  /** 本轮最后一条助手消息的 id（用于回填耗时）。 */
  private var runEndMessageId: Long? = null

  /**
   * 归约一个事件。
   *
   * @param nowMs 当前时刻（`SystemClock.elapsedRealtime()`）。由调用方传入而不是内部读，
   *   使本类在 JVM 上可测（不依赖 Android 时钟）。
   */
  fun reduce(event: AgentEvent, nowMs: Long) {
    when (event.type) {
      AgentEvent.Type.RUN_STARTED -> onRunStarted(nowMs)
      AgentEvent.Type.RUN_FINISHED -> onRunFinished(nowMs)
      AgentEvent.Type.USER_MESSAGE -> onUserMessage(event.message)
      AgentEvent.Type.REASONING_DELTA -> onReasoningDelta(event.message)
      AgentEvent.Type.TEXT_DELTA -> onTextDelta(event.message)
      AgentEvent.Type.TURN_FINISHED -> onTurnFinished(event.message)
      AgentEvent.Type.TOOL_STARTED -> onToolStarted(event)
      AgentEvent.Type.TOOL_FINISHED -> onToolFinished(event)
      AgentEvent.Type.PROGRESS -> onProgress(event)
      AgentEvent.Type.STREAM_RETRYING -> {
        retryAttempt = event.retryAttempt
      }
      AgentEvent.Type.CONTEXT_COMPACTED -> onContextCompacted(event.message)
      AgentEvent.Type.COMPLETED, AgentEvent.Type.FAILED -> onTerminal(event)
      // TURN_STARTED 没有渲染意义：它只是「开始请求模型」，用户看不到任何东西。
      AgentEvent.Type.TURN_STARTED -> Unit
    }
  }

  // ---- 运行边界 ----

  /**
   * 运行起点：**重置流式指针，不清列表**。
   *
   * <p>视图可能在运行中途才接上（用户切回 App），它回放的历史里本轮已有的文本会表现为
   * 一条「已完成」的助手气泡。若不重置指针，订阅后的增量会继续追加进那个气泡，
   * 把「已经说完的一段」和「正在说的新一段」混在一起。
   */
  private fun onRunStarted(nowMs: Long) {
    isRunning = true
    streamingMessageId = null
    thinkingId = null
    lastToolCardId = null
    retryAttempt = 0
    pendingDiffs.clear()
    runStartedAtMs = nowMs
    runEndMessageId = null
    // 工具组的自动折叠依赖它：只有「运行中且位于末尾」的组才默认展开。
    timeline.isRunActive = true
  }

  /**
   * 运行结束：收尾思考块、落改动汇总卡、回填耗时。
   *
   * <p>顺序有讲究：先把思考块收掉（否则它停在「思考中」），再插汇总卡
   * （插在最后，用户看到的就是「刚才那轮」），最后回填耗时（耗时挂在末条助手消息上）。
   */
  private fun onRunFinished(nowMs: Long) {
    isRunning = false
    timeline.finishAllThinking()
    thinkingId = null
    lastToolCardId = null

    flushDiffSummary()

    val start = runStartedAtMs
    if (start > 0L) {
      val elapsed = (nowMs - start).coerceAtLeast(0L)
      runEndMessageId?.let { timeline.updateDuration(it, elapsed) }
    }
    runStartedAtMs = 0L
    runEndMessageId = null
    timeline.isRunActive = false
    streamingMessageId = null
  }

  /**
   * 把本轮收集到的改动落成一张汇总卡。
   *
   * <p>`computing=true` 起步：diff 内容由调用方随后异步计算并回填（见
   * [MessageTimeline.updateDiff]）。不标这一位的话，卡片在回填到达前会显示
   * 「记录已过期」——运行刚结束时用户最可能立刻展开。
   */
  private fun flushDiffSummary() {
    if (pendingDiffs.isEmpty()) {
      return
    }
    timeline.appendDiffGroup(pendingDiffs.toList())
    pendingDiffs.clear()
  }

  /** 运行终止（COMPLETED / FAILED）：把仍在「运行中」的工具卡标记为失败。 */
  private fun onTerminal(event: AgentEvent) {
    if (event.type != AgentEvent.Type.FAILED) {
      return
    }
    // 循环异常结束时，仍在运行的工具不会再收到 TOOL_FINISHED——
    // 不标记的话它们永久停在「运行中」，用户以为任务还在跑。
    for (id in timeline.runningToolCallIds()) {
      timeline.failToolCall(id, event.message.ifBlank { "运行已终止" })
    }
  }

  // ---- 用户消息 ----

  /**
   * 一条用户消息进入会话。
   *
   * <p><b>为什么这里要判重</b>：本 App 是唯一入口，发送时会本地画气泡；内核随后
   * 也会广播 `USER_MESSAGE`。不去重就会出现两个一模一样的用户气泡。
   * 判据是「末条已经是同一内容的用户消息」——足够简单，且不会误删用户真的连发两条
   * 相同内容的情况（那种情况中间隔着助手的回复）。
   */
  private fun onUserMessage(text: String) {
    val last = timeline.snapshot().lastOrNull()
    if (last is MessageTimeline.Message &&
        last.role == MessageTimeline.Role.USER &&
        last.text == text) {
      return
    }
    timeline.append(MessageTimeline.Role.USER, text)
  }

  // ---- 流式增量 ----

  /**
   * 推理增量。
   *
   * <p>返回 -1 表示「纯空白、没建块」——不能把它存进 [thinkingId]，
   * 否则后续增量会去找一个不存在的 id，然后每次增量都新建一块。
   */
  private fun onReasoningDelta(delta: String) {
    if (delta.isEmpty()) {
      return
    }
    val id = timeline.appendThinking(delta, thinkingId)
    if (id >= 0) {
      thinkingId = id
    }
  }

  /**
   * 正文增量。
   *
   * <p>还没有气泡时，**纯空白增量不建气泡**：模型「只调用工具、不写正文」的轮次
   * 会先吐出 `"\n\n"`，那时建出的气泡最终没有内容，渲染成一片空白灰块。
   */
  private fun onTextDelta(delta: String) {
    if (delta.isEmpty()) {
      return
    }
    val id = streamingMessageId
    if (id == null) {
      if (delta.isBlank()) {
        return
      }
      streamingMessageId = timeline.append(MessageTimeline.Role.ASSISTANT, delta)
    } else {
      timeline.appendTo(id, delta)
    }
  }

  /**
   * 一轮结束：用**规范输出**覆盖流式累积。
   *
   * <p>模型可能把工具调用写成正文文本形态，累积的增量里含标记，而事件里的 message
   * 已经过 `ToolCallTextParser` 剥离。不覆盖的话用户会看到 `FILE_TO_MODIFY:` 之类的标记。
   *
   * <p>该轮规范输出为空是常见情况（只发起工具调用、没写正文），**只在非空时覆盖**——
   * 无条件覆盖会把已经流式显示出来的正文抹掉，用户看到气泡突然变空。
   */
  private fun onTurnFinished(text: String) {
    val id = streamingMessageId
    if (text.isNotBlank()) {
      if (id != null) {
        timeline.update(id, text)
      } else {
        streamingMessageId = timeline.append(MessageTimeline.Role.ASSISTANT, text)
      }
    } else if (id != null) {
      // 本轮没有正文，但流式阶段建过气泡（增量全是空白，或后来被覆盖成空）。
      // 留着就是一个内容为空的灰块——删掉，工具卡片自己已经说明了这一步做了什么。
      timeline.removeIfBlank(id)
      streamingMessageId = null
    }
    // 本轮推理到此结束。事件顺序是 TURN_FINISHED 先于该轮的 TOOL_STARTED，
    // 在这里收尾最准；置空后，工具之后的新推理会另起一块。
    thinkingId?.let { timeline.finishThinking(it) }
    thinkingId = null
    runEndMessageId = streamingMessageId
    streamingMessageId = null
  }

  // ---- 工具 ----

  /**
   * 工具开始。
   *
   * <p>发生工具调用意味着「这一段推理结束了」：把思维链块收尾并断开，
   * 让工具之后的新推理另起一块。否则整轮的推理会堆在同一块里，
   * 看不出哪段推理导致了哪次调用。
   *
   * <p><b>文件改动的 diffId 从参数里取</b>：写/改类工具的参数含 `file_path`，
   * 这里把它记进 [pendingDiffs]，运行结束时汇总成卡片。取不到路径的工具（grep、
   * shell 等）不进汇总——它们本来就没有「改动」可言。
   */
  private fun onToolStarted(event: AgentEvent) {
    val call = event.toolCall ?: return
    val id =
        timeline.appendToolCall(
            toolName = call.name,
            summary = summarizeArgs(call.arguments),
            input = call.arguments.orEmpty(),
        )
    lastToolCardId = id
    thinkingId?.let { timeline.finishThinking(it) }
    thinkingId = null

    val path = parsePathArg(call.arguments)
    if (path != null && isMutatingTool(call.name)) {
      pendingDiffs.add(path to null)
    }
  }

  /**
   * 工具完成：回填输出与图片。
   *
   * <p>按 `lastToolCardId` 回填而不是按调用 id 配对——本 App 的工具调用是串行的
   * （`ShellExecuteTool` 与文件工具都不是并发安全的），并行派遣需要子 agent 支持，
   * 那属于后续步骤。串行场景下「最近一张」就是「这张」。
   */
  private fun onToolFinished(event: AgentEvent) {
    val result = event.toolResult ?: return
    val id = lastToolCardId ?: return
    timeline.completeToolCall(
        id = id,
        output = result.content ?: "",
        isError = result.isError,
        imageBase64 = if (result.hasImage()) result.imageBase64 else null,
        imageMimeType = result.imageMimeType ?: "",
    )
  }

  /** 工具步骤进度：追加到当前卡片（长任务的「此刻在做什么」）。 */
  private fun onProgress(event: AgentEvent) {
    val step = event.message
    if (step.isBlank()) {
      return
    }
    // 带调用 id 时按 id 归卡（并行场景）；不带时退回到「最近一张」。
    val callId = event.progressCallId
    if (!callId.isNullOrEmpty()) {
      // 本 App 还没有「调用 id → 卡片 id」映射（串行执行下不需要）。
      // 直接落到最近一张，语义与无 id 时一致。
    }
    lastToolCardId?.let { timeline.appendToolStep(it, step) }
  }

  /** 上下文被压缩：插入一条可见提示。压缩是**有损**的，用户有权知道。 */
  private fun onContextCompacted(summary: String) {
    val text =
        if (summary.isBlank()) {
          "早期对话已压缩为摘要（模型已看不到更早的原始内容）。"
        } else {
          "早期对话已压缩为摘要：\n$summary"
        }
    timeline.append(MessageTimeline.Role.TRACE, text)
  }

  // ---- 参数解析辅助 ----

  /**
   * 折叠态的参数摘要。
   *
   * <p>逐个 key 取前 60 字符：大段内容（要写入的文件正文）会把摘要撑爆，
   * 而折叠态只需要「这次调用大致在干什么」。
   */
  private fun summarizeArgs(args: String?): String {
    if (args.isNullOrBlank()) {
      return ""
    }
    return try {
      val obj = org.json.JSONObject(args)
      val sb = StringBuilder()
      val keys = obj.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(key).append('=').append(obj.optString(key).take(60))
      }
      sb.toString()
    } catch (e: Exception) {
      args.take(100)
    }
  }

  /**
   * 从工具参数里取目标路径。
   *
   * <p>覆盖三种形态：`file_path`（写/改）、`paths` 数组（删除）、`path`（部分工具）。
   */
  private fun parsePathArg(args: String?): String? {
    if (args.isNullOrBlank()) {
      return null
    }
    return try {
      val obj = org.json.JSONObject(args)
      obj.optString("file_path").ifBlank { null }
          ?: obj.optJSONArray("paths")?.optString(0)?.ifBlank { null }
          ?: obj.optString("path").ifBlank { null }
    } catch (e: Exception) {
      null
    }
  }

  /** 该工具是否会改动文件（决定是否进「本轮改动」汇总）。 */
  private fun isMutatingTool(name: String): Boolean =
      name == ToolNames.FILE_WRITE || name == ToolNames.FILE_EDIT || name == ToolNames.FILE_DELETE
}
