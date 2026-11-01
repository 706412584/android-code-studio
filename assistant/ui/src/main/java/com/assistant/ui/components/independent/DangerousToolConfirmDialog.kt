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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.assistant.ui.theme.Spacing

/**
 * 一次待确认的危险工具调用。
 *
 * @param toolName 工具规范名（如 file_delete / shell_execute）
 * @param caption 一句话说明这一步要做什么（由宿主翻译工具名得到）
 * @param detail 参数原文，供用户判断「到底要执行什么」
 */
data class PendingDangerousTool(
    val toolName: String,
    val caption: String,
    val detail: String,
)

/**
 * 用户对一次危险工具确认的答复——**取值与内核 {@code DangerousToolDecision} 一一对应**，
 * 由宿主负责映射（渲染层不依赖内核，因此不直接引那个枚举）。
 */
enum class DangerousToolChoice {
  /** 只放行本次；同一条命令在本会话内不再重复询问。 */
  ALLOW_ONCE,

  /** 放行本次并持久化一条规则（工具 + 参数粒度）。 */
  ALLOW_ALWAYS,

  /** 拒绝本次，或用户点了对话框外部。 */
  DENY,
}

/**
 * 危险工具确认弹窗。
 *
 * <p><b>为什么必须有</b>：权限模式为「确认」时，内核会在这里停下来等用户答复
 * （阻塞式端口）。没有这个弹窗就只有两条路——静默放行（危险）或一律拒绝（不可用）。
 *
 * <p><b>三个按钮而不是两个</b>：参照 ACS 的 `DangerousToolDecision`。
 * 「始终允许」写一条持久化规则（工具 + 参数粒度，见内核 `ToolPermissionRule`），
 * 对 `git status` 点过之后不会顺带放行 `git push --force`——那是内核的规则粒度保证的，
 * 这里只负责如实回传用户的选择。
 *
 * <p><b>默认焦点在「拒绝」</b>：用户误触对话框外的空白不该放行删除。
 */
@Composable
fun DangerousToolConfirmDialog(
    pending: PendingDangerousTool,
    onAllowOnce: () -> Unit,
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDeny,
      title = { Text("需要确认") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          Text(
              text = pending.caption,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
              text = pending.toolName,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (pending.detail.isNotBlank()) {
            // 参数可能很长（整段 JSON）：限高 + 内滚，否则会把按钮顶出屏幕。
            // 横向滚动是因为 JSON 常常是一行很长，换行反而不便读。
            Text(
                text = pending.detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
            )
          }
        }
      },
      confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
          TextButton(onClick = onDeny) { Text("拒绝") }
          TextButton(onClick = onAllowAlways) { Text("始终允许") }
          TextButton(onClick = onAllowOnce) { Text("允许一次") }
        }
      },
  )
}
