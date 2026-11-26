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

  var fieldValue by mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    private set
  var references by mutableStateOf<List<com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerReference>>(emptyList())
    private set
  var focusRequest by mutableIntStateOf(0)
  private var draftSession = ""
  private data class Draft(
      val value: androidx.compose.ui.text.input.TextFieldValue,
      val references: List<com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerReference>,
      val attachments: androidx.compose.runtime.snapshots.SnapshotStateList<PendingUploadAttachment>,
  )
  private val drafts = mutableMapOf<String, Draft>()

  var text: String
    get() = fieldValue.text
    set(value) { edit(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }

  fun edit(value: androidx.compose.ui.text.input.TextFieldValue) {
    val old = com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerDraft(text, references, fieldValue.selection.end)
    val edited = com.tom.rv2ide.artificial.agent.compose.components.independent.applyTextEdit(old, value.text, value.selection.end)
    if (value.composition != null) {
      // IME 尚未提交，不改它正在组合的文本或 selection。
      references = com.tom.rv2ide.artificial.agent.compose.components.independent.liveReferences(edited.copy(text = value.text))
      fieldValue = value
      return
    }
    references = com.tom.rv2ide.artificial.agent.compose.components.independent.liveReferences(edited)
    fun boundary(offset: Int, towardEnd: Boolean): Int {
      val inside = references.firstOrNull { offset > it.start && offset < it.end } ?: return offset
      return if (towardEnd) inside.end else inside.start
    }
    val selection = if (value.text != edited.text) androidx.compose.ui.text.TextRange(edited.cursor)
        else if (value.selection.collapsed) {
          androidx.compose.ui.text.TextRange(boundary(value.selection.end, value.selection.end >= fieldValue.selection.end))
        } else androidx.compose.ui.text.TextRange(
            boundary(value.selection.start, value.selection.reversed),
            boundary(value.selection.end, !value.selection.reversed))
    fieldValue = androidx.compose.ui.text.input.TextFieldValue(edited.text, selection)
  }

  fun insert(reference: com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerReference, start: Int = fieldValue.selection.min, end: Int = fieldValue.selection.max) {
    if (fieldValue.composition != null) return
    val next = com.tom.rv2ide.artificial.agent.compose.components.independent.insertReference(text, references, start, end, reference)
    references = next.references
    fieldValue = androidx.compose.ui.text.input.TextFieldValue(next.text, androidx.compose.ui.text.TextRange(next.cursor))
    focusRequest++
  }

  fun removeReference(reference: com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerReference) {
    if (fieldValue.composition != null) return
    val length = reference.end - reference.start
    references = references.filterNot { it == reference }.map {
      if (it.start >= reference.end) it.copy(start = it.start - length, end = it.end - length) else it
    }
    val selection = fieldValue.selection
    fieldValue = androidx.compose.ui.text.input.TextFieldValue(
        text.removeRange(reference.start, reference.end),
        androidx.compose.ui.text.TextRange(
            selection.start - (selection.start - reference.start).coerceIn(0, length),
            selection.end - (selection.end - reference.start).coerceIn(0, length)))
  }

  fun restore(value: String, refs: List<com.tom.rv2ide.ai.protocol.ReferenceMeta>) {
    references = refs.map { com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerReference(it.kind, it.id, it.label, it.path, it.start, it.end) }
    fieldValue = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    focusRequest++
  }

  fun switchSession(id: String?) {
    val key = id.orEmpty()
    if (key == draftSession) return
    drafts[draftSession] = Draft(fieldValue, references.toList(), pendingAttachments)
    val saved = drafts[key]
    fieldValue = saved?.value ?: androidx.compose.ui.text.input.TextFieldValue("")
    references = saved?.references.orEmpty()
    pendingAttachments = saved?.attachments ?: mutableStateListOf()
    draftSession = key
  }

  var conversationEmpty by mutableStateOf(true)

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

  // ── 附件 ──

  /** 按会话保存的唯一待发送附件；预览和发送共用，异步读图捕获所属草稿列表。 */
  var pendingAttachments by mutableStateOf(mutableStateListOf<PendingUploadAttachment>())
    private set

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
