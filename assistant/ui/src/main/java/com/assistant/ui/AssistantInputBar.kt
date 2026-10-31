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

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.assistant.ui.compat.AgentMode
import com.assistant.ui.compat.ReasoningEffort
import com.assistant.ui.components.independent.ChatInputBar

/**
 * 输入栏的公开门面。
 *
 * <p><b>为什么需要它</b>：[ChatInputBar] 是 `internal`，且参数有二十多个（服务商芯片、
 * 附件、斜杠命令、待发队列、token 进度…）。宿主不该为了发一条消息去拼二十个默认值。
 * 这里只暴露「发消息」必需的那几个，其余走组件默认值。
 *
 * <p><b>为什么文本必须是 Compose 状态而不是普通字段</b>：`ChatInputBar` 的
 * `canSend = value.isNotBlank()` 由**传入的 value** 决定。若把文本存成普通 `var`，
 * 输入变化不会触发重组，`value` 永远停在初值——表现就是「打字有显示，但发送键一直灰着」。
 * 这个坑在真机上表现为「点了发送没反应」，而日志里没有任何错误。
 *
 * <p><b>刻意不含的东西</b>：附件上传、斜杠命令、技能 @ 菜单、待发队列、待办条。
 * 它们各自需要一个完整的宿主侧实现（文件选择器、命令注册表、技能目录扫描、队列管理器），
 * 属于后续步骤。先让「打字 → 发送 → 收到回答」这条主链路跑起来。
 */
class AssistantInputBarView(
    context: Context,
    /** 输入框文本的初值。 */
    initialText: String = "",
    /** 文本变化回调。宿主需要它才能知道「现在能不能发」。 */
    private val onTextChanged: (String) -> Unit = {},
    /** 点发送。入参是输入框当前文本。 */
    private val onSend: (String) -> Unit,
    /** 点停止（软打断）。 */
    private val onStop: () -> Unit,
    /** 当前是否在运行。运行中发送键变成停止键。 */
    private val isBusy: () -> Boolean,
    /** 当前模型名，显示在输入栏的芯片上。 */
    private val modelName: () -> String?,
) {

  /**
   * 输入框内容的**唯一真相**。
   *
   * <p>必须是 `mutableStateOf`：`ChatInputBar` 的发送键可用性由它决定（见类注释）。
   * 用普通 `var` 会让「有没有内容」永远停在初始判断上。
   */
  private val textState = mutableStateOf(initialText)

  /** 当前输入框内容（供宿主读取，例如判断是否为空）。 */
  val text: String
    get() = textState.value

  val view: ComposeView =
      ComposeView(context).apply {
        setContent {
          ChatInputBar(
              value = textState.value,
              onValueChange = {
                textState.value = it
                onTextChanged(it)
              },
              onSend = { value ->
                // 先清空再回调：清空会让发送键立刻回到禁用态，避免用户连点两次
                // 把同一条内容发出去（第二次调用会拿到空串，send 里会拒绝）。
                textState.value = ""
                onSend(value)
              },
              onStop = onStop,
              isBusy = isBusy(),
              activeModelName = modelName(),
              // 服务商选择面板：本 App 用设置页改服务商，输入栏不弹面板。
              // 传空列表时芯片只显示模型名（组件对空列表是安全的）。
              providers = emptyList(),
              currentMode = AgentMode.BUILD,
              onToggleMode = {},
              reasoningEffort = ReasoningEffort.DEFAULT,
              onReasoningEffortChange = {},
              // 附件：本步骤不支持上传，传空表 + 无操作回调。
              pendingAttachments = emptyList(),
              onRemoveAttachment = {},
              onReadAttachment = { null },
              canUploadFiles = false,
              canUploadImages = false,
              onUploadFile = {},
              onUploadImage = {},
              onTakePhoto = {},
          )
        }
      }

  /** 把焦点放进输入框（宿主在打开面板时调用）。 */
  fun requestFocus() {
    view.requestFocus()
  }
}
