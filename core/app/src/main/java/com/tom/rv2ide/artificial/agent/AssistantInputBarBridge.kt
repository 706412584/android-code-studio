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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tom.rv2ide.artificial.agent.compose.compat.AgentMode
import com.tom.rv2ide.artificial.agent.compose.compat.PendingUploadAttachment
import com.tom.rv2ide.artificial.agent.compose.components.independent.ProviderSelectionTarget
import com.tom.rv2ide.artificial.agent.compose.compat.ReasoningEffort
import com.tom.rv2ide.artificial.agent.compose.components.independent.ChatSession
import com.tom.rv2ide.artificial.agent.compose.components.tools.ParsedTodoItem
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage
import com.tom.rv2ide.artificial.agent.compose.components.independent.QueuedRequest

/**
 * Compose 输入栏（[com.tom.rv2ide.artificial.agent.compose.components.independent.ChatInputBar]）
 * 的状态桥。XML 输入区退役后，宿主与 [AssistantInputFeatures] 不再触碰任何输入控件——
 * 它们读写本桥的 mutableStateOf，Compose 侧按状态重组。
 *
 * <p><b>为什么是普通类而不是宿主字段散写</b>：输入栏状态是一个整体（文本/忙碌/附件/
 * 运行态互相耦合），收拢后「谁改什么」一目了然；且 ChatInputBar 的参数面有 20 多项，
 * 一个装配点逐项从桥取值，宿主只暴露回调。
 *
 * <p>**线程**：写方是宿主主线程（事件回调已切回），Compose 读方在组合线程——
 * mutableStateOf 的快照隔离保证安全。附件列表用 SnapshotStateList 支持原位增删。
 */
internal class AssistantInputBarBridge {

  // ── 文本与发送 ──

  /** 输入框文本。Compose 侧 onValueChange 回写这里；宿主写入用 [text] 属性。 */
  var text by mutableStateOf("")

  // ── 运行态 ──

  /** 当前显示会话是否在跑（原 setRunningUi 的 running 参数）。 */
  var isBusy by mutableStateOf(false)

  /** 停止后协程仍在跑当前一步（canForceStop）。 */
  var canForceStop by mutableStateOf(false)

  /** 上下文进度环（0..1）；0 不画。 */
  var tokenProgress by mutableFloatStateOf(0f)

  /** 进度是否为估算值。 */
  var tokenEstimated by mutableStateOf(false)

  /**
   * 上下文窗口总大小（token）；0 表示模型未声明窗口。
   *
   * <p><b>为什么不能只看 [tokenProgress]</b>：它表达的是「**已用**量占比」，
   * 未运行时必然是 0——用它判断「要不要显示上下文按钮」会让按钮在首次对话前
   * 永远不出现（用户看到的正是这个）。按钮的显示条件应是「**窗口已配置**」。
   */
  var contextWindowSize by mutableIntStateOf(0)

  // ── 附件（原 AssistantInputFeatures.attachments 的 Compose 投影） ──

  /** 待发送附件。由 [AssistantInputFeatures] 增删（已切换到本列表）。 */
  val pendingAttachments = mutableStateListOf<PendingUploadAttachment>()

  // ── 模型/模式/推理强度 ──

  /** 当前模型显示名（原 assistantToolbarModel 的文本）。 */
  var modelName by mutableStateOf<String?>(null)

  /**
   * 当前服务商投影（含它的模型列表）。
   *
   * <p>由 [FloatingAssistantView.refreshModelLabel] 从 `ProviderConfigStore` 投影。
   * null 表示当前服务商没有记录——此时芯片退化为「只有模型名」的占位投影。
   */
  var currentProvider by mutableStateOf<ProviderSelectionTarget?>(null)

  /**
   * 全部可选服务商投影，供输入栏芯片弹出的模型面板渲染。
   *
   * <p>**不要留空**：芯片的选择面板靠它渲染服务商与模型列表，空列表会弹出一张
   * 没有内容的卡片——用户看不到任何可选项，点空白还会穿透到底下的编辑器。
   */
  var providers by mutableStateOf<List<ProviderSelectionTarget>>(emptyList())

  /** 当前对话模式（Aharou 三档；ACS ChatMode 的投影由宿主换算）。 */
  var mode by mutableStateOf(AgentMode.BUILD)

  /**
   * 推理强度档位。
   *
   * <p><b>初值从持久化偏好读</b>（不是硬编码 MEDIUM）：此前每次重建视图
   * （点开/切全屏）都会回到默认档，用户改过的选择被静默重置（实测反馈）。
   * 写回由宿主在 [onReasoningEffortChange] 里落盘（见 FloatingAssistantView）。
   */
  var reasoningEffort by mutableStateOf(ReasoningEffort.MEDIUM)

  // ── 工具运行状态条（FloatingToolStatusBar） ──

  /**
   * 正在运行的工具的实时输出，键 = 工具卡片 id（[AssistantMessageAdapter.Item] 的 Long id
   * 字符串化，与 Compose 消息映射的 [AgentUIMessage.id] 同源）。
   *
   * <p>宿主在 PROGRESS 事件里累积、TOOL_FINISHED 时移除。键存在 = 该工具仍在跑——
   * 状态条正是以「liveOutputFor(id) != null」判定运行态，因此移除动作本身就是收尾信号。
   */
  var liveToolOutput: Map<String, String> by mutableStateOf(emptyMap())

  // ── 待办（TodoDashboardBar） ──

  /** 最近一次 todo 工具结果解析出的条目。 */
  var todoItems: List<ParsedTodoItem> by mutableStateOf(emptyList())

  // ── 排队 ──

  /** 排队中的请求（ACS 当前无排队生产者，保留参数面）。 */
  val queuedRequests = mutableStateListOf<QueuedRequest>()

  // ── 斜杠命令 ──

  /** 斜杠菜单条目。宿主在 attach 时从 SlashCommandCatalog 投影一次。 */
  var slashCommands by mutableStateOf<List<com.tom.rv2ide.artificial.agent.compose.components.independent.InputSlashCommand>>(emptyList())

  /** 已安装技能（@ 菜单数据源）。宿主在 attach 时从 SkillRegistry 投影。 */
  var skills by mutableStateOf<List<com.tom.rv2ide.artificial.agent.compose.components.independent.InputSlashCommand>>(emptyList())

  // ── 抽屉会话（跨桥复用：ChatDrawer 已有 drawerSessions，这里不重复） ──

  /** 最近会话列表的只读别名，供装配层取 sessionId。 */
  var sessions: List<ChatSession> = emptyList()
}
