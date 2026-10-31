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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.assistant.ui.compat.AppTextField
import com.assistant.ui.components.independent.ProviderLogoIcon
import com.assistant.ui.components.independent.ProviderProtocol
import com.assistant.ui.components.independent.ProviderSelectionTarget
import com.assistant.ui.components.independent.SettingsDivider
import com.assistant.ui.components.independent.SettingsGroup
import com.assistant.ui.components.independent.SettingsGroupHeader
import com.assistant.ui.components.independent.SettingsRow
import com.assistant.ui.components.independent.settingsPageBackground
import com.assistant.ui.theme.AIEditorTheme
import com.assistant.ui.theme.Spacing

/**
 * 一个服务商在设置页上的投影。
 *
 * <p><b>为什么不在 ui 模块直接引用引擎的 `ProviderPresets`</b>：渲染层与引擎是两条独立的轴
 * （见 `assistant/engine/build.gradle.kts` 的说明）。设置页属于渲染层，它只认「要显示什么」，
 * 由宿主把引擎的预设表投影成这个类型。这样渲染层不需要 `assistant:engine` 依赖。
 */
data class ProviderOption(
    val id: String,
    val label: String,
    val models: List<String>,
    val requiresApiKey: Boolean,
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
)

/**
 * 模型设置页。
 *
 * <p><b>全部复用本模块既有组件</b>：分组用 [SettingsGroup] + [SettingsGroupHeader]，
 * 行用 [SettingsRow]，输入用 [AppTextField]，服务商图标用 [ProviderLogoIcon]。
 * 这些是 Aharou 移植过来的同一套设置页组件——重造一套只会让两处风格漂移。
 *
 * <p>这一页只解决「填 Key 把对话跑起来」。完整设置（权限策略 / MCP / Skill / 记忆 /
 * 主题 / 定时任务）属于后续步骤，届时按 Aharou 的 `SettingsScreen` 分节扩展，
 * 骨架已经在这里（分组 + 行 + 输入）。
 */
@Composable
fun AssistantModelSettingsScreen(
    providers: List<ProviderOption>,
    selectedProviderId: String,
    selectedModel: String,
    apiKey: String,
    customBaseUrl: String,
    /** 还差什么才能开始对话；空串表示就绪。 */
    readinessProblem: String,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
  AIEditorTheme {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(settingsPageBackground())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      Text(
          text = "模型设置",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.padding(start = Spacing.xs, top = Spacing.md, bottom = Spacing.sm),
      )

      // ---- 服务商 ----
      SettingsGroupHeader(text = "服务商")
      SettingsGroup {
        providers.forEachIndexed { index, provider ->
          if (index > 0) SettingsDivider()
          ProviderRow(
              provider = provider,
              selected = provider.id == selectedProviderId,
              onClick = { onSelectProvider(provider.id) },
          )
        }
      }

      // ---- 模型 ----
      val models = providers.firstOrNull { it.id == selectedProviderId }?.models.orEmpty()
      SettingsGroupHeader(text = "模型")
      SettingsGroup {
        if (models.isEmpty()) {
          SettingsRow(title = "该服务商没有预设模型", subtitle = "请填写自定义地址后使用")
        } else {
          models.forEachIndexed { index, model ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                title = model,
                onClick = { onSelectModel(model) },
                trailing = {
                  if (model == selectedModel) {
                    Text(
                        text = "已选",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                  }
                },
            )
          }
        }
      }

      // ---- 凭据 ----
      SettingsGroupHeader(text = "凭据")
      SettingsGroup {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
          AppTextField(
              value = apiKey,
              onValueChange = onApiKeyChange,
              label = "API Key",
              placeholder = "粘贴服务商控制台里的密钥",
              // 密码型变换：设置页可能被旁人看到，明文显示没有好处。
              visualTransformation = PasswordVisualTransformation(),
          )
        }
        SettingsDivider()
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
          AppTextField(
              value = customBaseUrl,
              onValueChange = onBaseUrlChange,
              label = "自定义 API 地址（可选）",
              placeholder = "留空则使用预设地址",
          )
        }
      }

      // ---- 状态 ----
      SettingsGroupHeader(text = "状态")
      SettingsGroup {
        SettingsRow(
            title = if (readinessProblem.isEmpty()) "配置就绪" else "还不能对话",
            subtitle = readinessProblem.ifEmpty { "可以开始发送消息了" },
            trailing = {
              Text(
                  text = if (readinessProblem.isEmpty()) "开始对话" else "保存",
                  style = MaterialTheme.typography.bodyMedium,
                  color =
                      if (readinessProblem.isEmpty()) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            },
            onClick = onSave,
        )
      }
    }
  }
}

/**
 * 一个服务商选项行：logo + 名称 + 协议，选中态用主色文字。
 *
 * <p>不用 [SettingsRow] 而是自己拼一行：`SettingsRow.icon` 只接受 `ImageVector`，
 * 而服务商 logo 是资源 id（`R.drawable.logo_deepseek` 这类），塞不进去。
 * 行的内边距、分隔线与其它设置行保持一致，视觉上仍是同一套。
 */
@Composable
private fun ProviderRow(
    provider: ProviderOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
  val target =
      remember(provider) {
        ProviderSelectionTarget(
            id = provider.id,
            name = provider.label,
            protocol = provider.protocol,
        )
      }
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clickable { onClick() }
              .padding(horizontal = Spacing.lg, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    ProviderLogoIcon(provider = target, size = 22.dp)
    Column(
        modifier = Modifier.weight(1f).padding(start = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
          text = provider.label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
          text = if (provider.requiresApiKey) "需要 API Key" else "本地服务，无需密钥",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (selected) {
      Text(
          text = "已选",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
