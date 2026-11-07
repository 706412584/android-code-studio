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

package com.assistant.ui.components.independent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.assistant.ui.model.AgentUIMessage
import com.assistant.ui.model.MessageRole
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2

/**
 * 一条消息能做的操作。
 *
 * <p>取值而不是回调列表：宿主拿到选择后自己决定怎么做（复制要拿剪贴板、
 * 重新生成要跑一轮对话）。组件只负责「问用户要哪个」。
 */
enum class MessageAction {
  EDIT,
  COPY,
  REGENERATE,
}

/**
 * 消息操作菜单。
 *
 * <p><b>为什么用 AlertDialog 而不是 Aharou 的 ModalBottomSheet</b>：那个组件
 * （`MessageActionsBottomSheet`）是 `internal`，本模块之外（宿主 app）用不了；
 * 而它相对 AlertDialog 只多了「宽屏居中、窄屏贴底」的自适应——本菜单只有四项，
 * 弹窗形态已经够用，为它把组件提为 public 并把它依赖的一整套 compat 类型
 * 一起暴露出去，代价大于收益。
 *
 * <p><b>哪些项显示是有条件的</b>：
 * - 复制：没有可见正文的消息（纯推理块、纯工具行）不显示——复制到空串没有意义
 * - 重新生成：只有助手自己的回答才有「再生成一次」的含义；对用户消息应当是
 *   「编辑后重发」，那是 EDIT 的职责
 * - 编辑：用户消息与助手消息都有
 *
 * <p><b>刻意没有「删除」</b>：会话是 append-only JSONL，删单条要重写整个文件——
 * 而 `ConversationStore` 只提供整会话删除。做一个只删界面、重开就回来的「删除」是
 * 骗人的（用户以为删掉了）。真需要清理会话时用抽屉里的删除整个会话。
 */
@Composable
fun MessageActionDialog(
    message: AgentUIMessage,
    onDismiss: () -> Unit,
    onAction: (MessageAction) -> Unit,
) {
  val hasText = message.content.hasVisibleContentCompat()
  val isAssistant = message.role == MessageRole.ASSISTANT

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("消息操作") },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          ActionRow(FeatherIcons.Edit2, "编辑") {
            onDismiss()
            onAction(MessageAction.EDIT)
          }
          if (hasText) {
            ActionRow(FeatherIcons.Copy, "复制") {
              onDismiss()
              onAction(MessageAction.COPY)
            }
          }
          if (isAssistant && hasText) {
            ActionRow(FeatherIcons.RefreshCw, "重新生成") {
              onDismiss()
              onAction(MessageAction.REGENERATE)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

/**
 * 编辑消息正文。
 *
 * <p>与 [MessageActionDialog] 同属「消息操作」一族但单独一个对话框：编辑要点的是
 * 「确定/取消」而不是从列表里选一项，两者混在一个 AlertDialog 里会让人分不清
 * 当前这一屏是在选择还是在输入。
 */
@Composable
fun EditMessageContentDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
  var text by remember { mutableStateOf(initialText) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("编辑消息") },
      text = {
        com.assistant.ui.compat.AppTextField(
            value = text,
            onValueChange = { text = it },
            label = "内容",
        )
      },
      confirmButton = {
        TextButton(
            enabled = text.isNotBlank(),
            onClick = { onConfirm(text) },
        ) {
          Text("确定")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
  val color = MaterialTheme.colorScheme.onSurface
  Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.md),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    // 间距用手写 dp 而不是 Spacing：这里的视觉节奏是「图标—文字」的固定间隔，
    // 与「区块间距」不是一回事，借用会让将来调区块间距时误改这里。
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier.padding(start = 14.dp),
    )
  }
}

/**
 * 有没有可显示/可复制的正文。
 *
 * <p>不复用 `model` 包里的 `hasVisibleContent()`：那是 CharSequence 的扩展，
 * 判的是「不出墨的码点」（零宽字符、Hangul filler），针对的是**渲染**要不要出气泡。
 * 这里判的是「复制过去是不是空」——纯空白串复制过去同样没有意义，
 * 因此额外 trim。
 */
private fun String.hasVisibleContentCompat(): Boolean = isNotBlank()
