/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.artificial.agent.compose.components.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.components.style.BotGlyph
import com.tom.rv2ide.artificial.agent.compose.components.style.ChatStyle
import com.tom.rv2ide.artificial.agent.compose.components.style.chatCardSurfaceColor
import com.tom.rv2ide.artificial.agent.compose.components.style.chatHairlineColor
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage
import com.tom.rv2ide.artificial.agent.compose.model.MessageRole
import com.tom.rv2ide.artificial.agent.compose.model.ToolRunStatus
import com.tom.rv2ide.artificial.agent.compose.theme.Brand
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.artificial.agent.compose.theme.semanticColors
import com.tom.rv2ide.resources.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 子代理派遣卡片（移植自 Aharou `SubAgentCardComponents.kt`，按 ACS 的能力边界裁剪）。
 *
 * <p><b>与 Aharou 的关键差异</b>：Aharou 的子代理有持久化会话（`task` 返回 `sessionId`，
 * 卡片可点进子会话看全过程）。ACS 的 [com.tom.rv2ide.ai.tool.SubAgentRunner.Result]
 * **只有结论、轮次与工具调用数**，没有会话 id，中间过程只经 `steps` 回传父级。
 * 因此这里不做「进入子会话」，改为：状态药丸 + 任务描述 + 步骤摘要 + 结论，全部就地展开。
 *
 * <p>卡片取代普通工具行，理由是折叠态的工具行只能显示「agent · 一句话」，看不出跑了多少步、
 * 结论是什么；而这些正是用户回看一轮派发时唯一关心的东西。
 */

/** 卡片状态。ACS 侧由 [ToolRunStatus] + 是否有结论推导，不额外引入任务表。 */
internal enum class SubAgentCardStatus {
  RUNNING,
  COMPLETED,
  FAILED,
}

/** 卡片里最多逐条列几步（再多只报数量，步骤行本身还有自己的展开）。 */
private const val STEPS_SHOWN_MAX = 3

/** 任务描述兜底截断：`task` 常是一整段需求，卡片只显示前两行。 */
private const val TASK_TEXT_MAX = 160

/** 结论摘要的字符上限，完整结论走卡片内的展开区。 */
private const val SUMMARY_MAX = 400

private const val STATUS_PILL_ALPHA = 0.12f

/**
 * `agent` / `agentb_*` / `agentx_*` / `agent_pipeline` 一次派遣解析出的卡片内容。
 *
 * [agentName] 为空时按「子代理」兜底显示；[taskText] 必填（拿不到就不出卡，退回普通工具行）。
 */
internal data class ParsedSubAgentDispatch(
    val agentName: String?,
    val taskText: String,
    /** `explore` / `code` / null；展示成「只读」「可写」这类用户能懂的字样。 */
    val mode: String?,
    /** 流水线的子任务数；非流水线为 0。 */
    val pipelineCount: Int = 0,
)

/**
 * 容错解析子代理派遣的参数。任何一步拿不到必需字段就返回 null（退回普通工具行），不抛异常
 * ——工具行渲染路径上崩一次就是整页聊天白掉。
 *
 * <p>只认 `create` 形态的派遣：`agent_pipeline` 用 `agents` 数组；单代理用 `task`。
 * 其余（`agent_output`、读取/停止类动作）不出卡，保持普通工具行。
 */
internal fun parseSubAgentDispatch(toolName: String?, argsJson: String?): ParsedSubAgentDispatch? {
  if (argsJson.isNullOrBlank()) return null
  val name = toolName?.lowercase()?.takeIf { isDispatchToolName(it) } ?: return null
  val args =
      runCatching { Json.parseToJsonElement(argsJson).jsonObject }.getOrNull() ?: return null

  if (name == "agent_pipeline") {
    val agents = runCatching { (args["agents"] as? JsonArray)?.jsonArray }.getOrNull() ?: return null
    if (agents.isEmpty()) return null
    val first = agents.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
    val taskText =
        first?.stringOrNull("description")?.takeIf { it.isNotBlank() }
            ?: first
                ?.stringOrNull("prompt")
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.takeIf { it.isNotBlank() }
            ?: return null
    return ParsedSubAgentDispatch(
        agentName = null,
        taskText = normalize(taskText),
        mode = null,
        pipelineCount = agents.size,
    )
  }

  // 单代理：agent（task + mode）、agentb_*（task）、agentx_*（task）。
  val task =
      args.stringOrNull("task")?.takeIf { it.isNotBlank() }
          ?: args.stringOrNull("prompt")?.takeIf { it.isNotBlank() }
          ?: return null
  val mode = args.stringOrNull("mode")?.takeIf { it.isNotBlank() }
  return ParsedSubAgentDispatch(
      agentName = agentDisplayName(name),
      taskText = normalize(task),
      mode = mode,
  )
}

/** 该 TOOL 消息是否渲染成派遣卡片。 */
internal fun AgentUIMessage.isSubAgentDispatchCard(): Boolean =
    role == MessageRole.TOOL && !isError && parseSubAgentDispatch(toolName, toolArgs) != null

/**
 * 只有「派遣类」工具才出卡：`agent`、`agentb_*`、`agentx_*`、`agent_pipeline`。
 *
 * <p>必须显式收窄，不能只看「参数里有没有 task」：`file_read` 之类也可能带同名键，
 * 那会把普通工具误渲染成派遣卡。`agent_output` 是取回结果的工具，不是派遣，排除。
 */
private fun isDispatchToolName(lowerName: String): Boolean =
    lowerName == "agent" ||
        lowerName == "agent_pipeline" ||
        lowerName.startsWith("agentb_") ||
        lowerName.startsWith("agentx_")

/**
 * 工具名 → 用户可读的代理名。`agentb_<id>` / `agentx_<name>` 去掉前缀直接显示，
 * 内置 `agent` 显示为「子代理」，流水线由调用方按 [ParsedSubAgentDispatch.pipelineCount] 另述。
 */
private fun agentDisplayName(toolName: String): String =
    when {
      toolName == "agent" -> ""
      toolName.startsWith("agentb_") || toolName.startsWith("agentx_") -> toolName.substringAfter('_')
      else -> ""
    }

/** 折叠空白、按字符上限截断；超长时补省略号。 */
private fun normalize(text: String): String {
  val flat = text.replace(Regex("\\s+"), " ").trim()
  return if (flat.length <= TASK_TEXT_MAX) flat else flat.take(TASK_TEXT_MAX) + "…"
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * 子代理派遣卡片。
 *
 * <p>整卡可点：点一下展开/收起「步骤 + 结论」。折叠态保持三行信息——代理名 + 状态、任务、
 * 「N 步 · 最后一步」，足以判断这次派发在做什么、跑到哪了。
 */
@Composable
internal fun SubAgentDispatchCard(
    message: AgentUIMessage,
    modifier: Modifier = Modifier,
) {
  val dispatch = remember(message.toolArgs, message.toolName) {
    parseSubAgentDispatch(message.toolName, message.toolArgs)
  } ?: return

  val running = message.toolStatus == ToolRunStatus.RUNNING
  val status =
      when {
        running -> SubAgentCardStatus.RUNNING
        message.isError -> SubAgentCardStatus.FAILED
        else -> SubAgentCardStatus.COMPLETED
      }
  val summary = remember(message.content) { extractSubAgentSummary(message.content) }
  val steps = message.steps

  var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
  val shape = RoundedCornerShape(ChatStyle.cardCorner)

  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .clip(shape)
              .background(chatCardSurfaceColor())
              .border(ChatStyle.hairline, chatHairlineColor(), shape)
              .clickable { expanded = !expanded }
              .padding(horizontal = Spacing.md, vertical = Spacing.sm),
      verticalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    // 标题行：机器人字形 + 代理名 + 状态药丸 + 展开箭头
    Row(verticalAlignment = Alignment.CenterVertically) {
      BotGlyph(tint = Brand.IconGray, iconSize = ChatStyle.rowIconSize)
      Spacer(Modifier.width(Spacing.sm))
      Text(
          text = dispatchTitle(dispatch),
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
      )
      SubAgentStatusPill(status)
      Spacer(Modifier.width(Spacing.xs))
      Icon(
          imageVector =
              if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
          contentDescription = null,
          tint = Brand.IconGray,
          modifier = Modifier.size(18.dp),
      )
    }

    // 任务描述
    Text(
        text = dispatch.taskText,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
    )

    // 步骤摘要（折叠态也显示：这是「跑到哪了」的唯一凭据）
    if (steps.isNotEmpty()) {
      Text(
          text = stringResource(R.string.subagent_card_steps_hint, steps.size, steps.last()),
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }

    if (expanded) {
      if (steps.isNotEmpty()) {
        Spacer(Modifier.size(Spacing.xs))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          steps.takeLast(STEPS_SHOWN_MAX).forEach { step -> SubAgentStepLine(step) }
        }
      }
      if (!summary.isNullOrBlank()) {
        Spacer(Modifier.size(Spacing.xs))
        ToolOutputText(
            text = summary,
            color = MaterialTheme.colorScheme.onSurface,
            textStyle = MaterialTheme.typography.bodySmall,
        )
      }
    } else if (!running && !summary.isNullOrBlank()) {
      // 折叠态给一行结论摘要：用户不必展开就能知道子代理得出了什么。
      Text(
          text = summary.take(SUMMARY_MAX),
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** 卡片标题：有代理名用代理名，流水线报子任务数，其余兜底「子代理」。 */
@Composable
private fun dispatchTitle(dispatch: ParsedSubAgentDispatch): String {
  if (dispatch.pipelineCount > 0) {
    return stringResource(R.string.subagent_card_pipeline_title, dispatch.pipelineCount)
  }
  return dispatch.agentName?.takeIf { it.isNotBlank() }
      ?: stringResource(R.string.subagent_card_title)
}

/** 状态药丸：文字用状态色，底色取同色低透明度。 */
@Composable
private fun SubAgentStatusPill(status: SubAgentCardStatus) {
  val color = subAgentStatusColor(status)
  Box(
      modifier =
          Modifier.clip(RoundedCornerShape(Radius.sm))
              .background(color.copy(alpha = STATUS_PILL_ALPHA))
              .padding(horizontal = Spacing.sm, vertical = 2.dp)
  ) {
    Text(
        text = stringResource(subAgentStatusLabelRes(status)),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
  }
}

@Composable
private fun subAgentStatusColor(status: SubAgentCardStatus): Color {
  val colors = MaterialTheme.semanticColors
  return when (status) {
    SubAgentCardStatus.RUNNING -> colors.info
    SubAgentCardStatus.COMPLETED -> colors.success
    SubAgentCardStatus.FAILED -> MaterialTheme.colorScheme.error
  }
}

private fun subAgentStatusLabelRes(status: SubAgentCardStatus): Int =
    when (status) {
      SubAgentCardStatus.RUNNING -> R.string.subagent_card_status_running
      SubAgentCardStatus.COMPLETED -> R.string.subagent_card_status_completed
      SubAgentCardStatus.FAILED -> R.string.subagent_card_status_failed
    }

/** 展开态的一行步骤：等宽小字，与工具卡的指令/结果同一视觉。 */
@Composable
private fun SubAgentStepLine(step: String) {
  Text(
      text = step,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
  )
}

/**
 * 从工具结果里取出子代理结论。
 *
 * <p>ACS 的结果形态是「结论正文 + 尾部一段统计」：
 * <pre>
 * &lt;结论&gt;
 *
 * （子 agent 模式: explore，轮次 3，工具调用 7）
 * </pre>
 * 内置/自定义 agent 同理（`内置 agent: xxx` / `自定义 agent: xxx`）。统计行对卡片是冗余信息
 * （状态药丸已表达），这里把它剥掉只留结论；解析不出时原样返回。
 */
internal fun extractSubAgentSummary(content: String): String? {
  val text = content.trim()
  if (text.isEmpty()) return null
  val lines = text.lines()
  // 从尾部剥掉连续的空行与括号统计行。
  var end = lines.size
  while (end > 0) {
    val line = lines[end - 1].trim()
    if (line.isEmpty() || (line.startsWith("（") && line.endsWith("）"))) {
      end--
    } else {
      break
    }
  }
  if (end == 0) return null
  return lines.subList(0, end).joinToString("\n").trim().takeIf { it.isNotEmpty() }
}
