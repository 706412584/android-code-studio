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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.assistant.ui.components.independent.SettingsDivider
import com.assistant.ui.components.independent.SettingsGroup
import com.assistant.ui.components.independent.SettingsGroupHeader
import com.assistant.ui.components.independent.SettingsRow
import com.assistant.ui.components.independent.settingsPageBackground
import com.assistant.ui.theme.AIEditorTheme
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Info
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Zap

/**
 * 设置首页。
 *
 * <p><b>为什么不是「模型设置页」</b>：模型配置只是设置的一项。把整页做成模型选择，
 * 用户进「设置」看到的第一屏就是服务商清单——找不到权限、外观、关于这些东西，
 * 会以为这个 App 没有设置。
 *
 * <p><b>版式照参考项目 Aharou 的 `SettingsScreen`</b>：分组卡片 + 行 + 右箭头。
 * 分组本身用 [SettingsGroup]，行用 [SettingsRow]，与 ACS 的设置页同一套组件。
 *
 * <p>目前只有「模型与凭据」是真能点的；其余行是占位（显示「待接入」）。
 * 占位而不是隐藏：用户需要知道「这里将来有什么」，而空白页面传达不了这个信息。
 */
@Composable
fun AssistantSettingsScreen(
    /** 当前模型摘要，显示在「模型与凭据」行的副标题上。 */
    modelSummary: String,
    /** 还差什么才能对话；空串表示就绪。 */
    readinessProblem: String,
    onOpenModelSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  AIEditorTheme(darkTheme = darkTheme, followHostFrame = false) {
    Column(
        modifier = modifier.fillMaxSize().background(settingsPageBackground()),
    ) {
      // ---- 顶栏：返回 + 标题 ----
      // 标题只在这里出现一次。之前模型设置页的窗口标题（manifest 的 android:label）
      // 和页面内的标题各写了一遍「模型设置」，看起来像重复了两次。
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        androidx.compose.material3.IconButton(onClick = onBack) {
          Icon(
              FeatherIcons.ArrowLeft,
              contentDescription = "返回",
              tint = MaterialTheme.colorScheme.onBackground,
              modifier = Modifier.size(20.dp),
          )
        }
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = Spacing.xs),
        )
      }

      Column(
          modifier =
              Modifier.fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = Spacing.md),
          verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        // ---- 模型 ----
        SettingsGroupHeader(text = "模型")
        SettingsGroup {
          SettingsRow(
              title = "模型与凭据",
              subtitle =
                  if (readinessProblem.isEmpty()) modelSummary else "还差：$readinessProblem",
              onClick = onOpenModelSettings,
              trailing = {
                Text(
                    text = "配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
              },
          )
        }

        // ---- 对话 ----
        SettingsGroupHeader(text = "对话")
        SettingsGroup {
          PlaceholderRow(
              icon = FeatherIcons.Sliders,
              title = "权限与确认",
              subtitle = "危险工具是否逐次确认",
          )
          SettingsDivider()
          PlaceholderRow(
              icon = FeatherIcons.Zap,
              title = "外观",
              subtitle = "配色方案 / 聊天字号 / 头像",
          )
          SettingsDivider()
          PlaceholderRow(
              icon = FeatherIcons.Shield,
              title = "记忆与技能",
              subtitle = "长期记忆、自定义 skill",
          )
        }

        // ---- 关于 ----
        SettingsGroupHeader(text = "关于")
        SettingsGroup {
          SettingsRow(
              icon = FeatherIcons.Info,
              title = "版本",
              subtitle = "0.1.0-standalone",
          )
        }

        Text(
            text = "带「待接入」的项还没有实现，接入后会在这里可点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        )
      }
    }
  }
}

/** 尚未实现的设置行：灰掉、标注「待接入」，不可点。 */
@Composable
private fun PlaceholderRow(icon: ImageVector, title: String, subtitle: String) {
  SettingsRow(
      icon = icon,
      title = title,
      subtitle = subtitle,
      enabled = false,
      trailing = {
        Text(
            text = "待接入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
  )
}
