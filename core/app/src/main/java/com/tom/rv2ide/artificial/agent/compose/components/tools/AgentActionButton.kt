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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.artificial.agent.compose.theme.semanticColors

/**
 * 工具面板里的一枚动作按钮（确认 / 补充 / 允许 / 拒绝）。
 *
 * <p>Aharou 里它宿主是 `ToolPermissionPanel`（危险工具授权面板）；本组移植的
 * [AskUserQuestionPanel] 是它当前唯一的调用方，故随本组一并落地，放在 tools 包内。
 *
 * <p>色调承担的语义：`Success` = 正向推进（确认），`Danger` = 否决（拒绝），
 * `Neutral` = 其余（补充说明）。禁用态统一走「容器变浅 + 文字降到 38%」，
 * 而不是只把整体 alpha 调低——后者在深色底上会让按钮整个「消失」。
 */
internal enum class AgentActionTone {
  Neutral,
  Success,
  Danger
}

@Composable
internal fun AgentActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: AgentActionTone = AgentActionTone.Neutral
) {
  val shape = RoundedCornerShape(Radius.sm)
  val success = MaterialTheme.semanticColors.success
  val (container, content, border) =
      when (tone) {
        AgentActionTone.Success ->
            Triple(
                if (enabled) success else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                if (enabled) MaterialTheme.semanticColors.onSuccess
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                if (enabled) success
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        AgentActionTone.Danger ->
            Triple(
                if (enabled) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                if (enabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                if (enabled) MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        AgentActionTone.Neutral ->
            Triple(
                if (enabled) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                if (enabled) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
      }

  Box(
      modifier =
          modifier
              .height(44.dp)
              .clip(shape)
              .background(container)
              .border(1.dp, border, shape)
              .clickable(enabled = enabled, onClick = onClick)
              .padding(horizontal = Spacing.xs),
      contentAlignment = Alignment.Center) {
        Text(text = text, color = content, fontWeight = FontWeight.Medium, maxLines = 1)
      }
}
