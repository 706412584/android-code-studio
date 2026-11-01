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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistant.ui.components.independent.SettingsDivider
import com.assistant.ui.components.independent.SettingsGroup
import com.assistant.ui.components.independent.SettingsGroupHeader
import com.assistant.ui.components.independent.SettingsRow
import com.assistant.ui.components.independent.settingsPageBackground
import com.assistant.ui.theme.AIEditorTheme
import com.assistant.ui.theme.AppThemePreset
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Info
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Zap

/**
 * 设置页的**完整状态**：一页里能改的全部设置。
 *
 * <p>做成一个数据类而不是二十个回调参数：设置项还会继续加，每加一项就多两个
 * 参数的话调用点会变成一堵墙。宿主构造一个 [AssistantSettingsState] 传进来，
 * 每项改动回调里直接改它自己的持久化。
 */
class AssistantSettingsState(
    /** 当前模型摘要（服务商 · 模型名）。 */
    var modelSummary: String,
    /** 还差什么才能对话；空串表示就绪。 */
    var readinessProblem: String,
    /** 配色方案 id。 */
    var themePresetId: String,
    /** 聊天字号（sp）。 */
    var textSize: Float,
    /** 权限模式：auto / confirm / readonly。 */
    var permissionMode: String,
    /** 对话模式 id：chat / plan / agent / control。 */
    var chatMode: String,
    /** 思考块结束后是否自动折叠。 */
    var thinkingAutoCollapse: Boolean,
    /** 是否显示工具运行状态条。 */
    var toolStatusBar: Boolean,
    /** 卡片缩放系数。 */
    var cardScale: Float,
    /** 正文颜色 ARGB；0 表示跟随主题。 */
    var textColor: Int,
    /** 是否启用长期记忆。 */
    var memoryEnabled: Boolean,
    /** 已安装技能数（只读展示）。 */
    var skillCount: Int,
    /** 已记录的记忆条数（只读展示）。 */
    var memoryCount: Int,
    var onOpenModelSettings: () -> Unit,
    var onThemePresetChange: (String) -> Unit,
    var onTextSizeChange: (Float) -> Unit,
    var onPermissionModeChange: (String) -> Unit,
    var onChatModeChange: (String) -> Unit,
    var onThinkingAutoCollapseChange: (Boolean) -> Unit,
    var onToolStatusBarChange: (Boolean) -> Unit,
    var onCardScaleChange: (Float) -> Unit,
    var onTextColorChange: (Int) -> Unit,
    /** 打开提示词模板管理。 */
    var onOpenPromptTemplates: () -> Unit,
    /** 打开长期记忆管理。 */
    var onOpenMemories: () -> Unit,
    /** 打开 Skill 管理。 */
    var onOpenSkills: () -> Unit,
    /** 打开服务商管理（记录表 + 从预设新建）。 */
    var onOpenProviders: () -> Unit,
    /** 打开 MCP 服务端管理。 */
    var onOpenMcp: () -> Unit,
    /** 打开模型管理（按用途分配模型）。 */
    var onOpenModelManage: () -> Unit,
    /** 自定义服务商数量（副标题用）。 */
    var providerCount: Int,
    /** 已接入的 MCP server 数量。 */
    var mcpServerCount: Int,
    /** 已配置了用途的模型数量（副标题用）。 */
    var configuredSceneCount: Int,
    var onMemoryEnabledChange: (Boolean) -> Unit,
    var onBack: () -> Unit,
)

/**
 * 设置首页。
 *
 * <p><b>为什么不是「模型设置页」</b>：模型配置只是设置的一项。把整页做成模型选择，
 * 用户进「设置」看到的第一屏就是服务商清单——找不到权限、外观、关于，
 * 会以为这个 App 没有设置。
 *
 * <p><b>为什么没有第二行标题</b>：页面顶部的 `Assistant` 来自 Activity 的
 * `android:label`（系统绘制的窗口标题），页面内又写一个「设置」就成了双标题。
 * 这里把 Activity 的 label 设成应用名（Assistant），页面内保留唯一的「设置」标题。
 */
@Composable
fun AssistantSettingsScreen(
    state: AssistantSettingsState,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  AIEditorTheme(darkTheme = darkTheme, followHostFrame = false) {
    Column(modifier = modifier.fillMaxSize().background(settingsPageBackground())) {
      // ---- 顶栏：返回 + 标题（页面内唯一的一处标题） ----
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        androidx.compose.material3.IconButton(onClick = state.onBack) {
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
          // 只有「服务商」一项：服务商记录里就含 baseUrl 与密钥，
          // 再放一个「模型与凭据」是同一件事的两个入口（用户实测反馈重复）。
          SettingsRow(
              title = "服务商",
              subtitle =
                  if (state.providerCount > 0) "${state.providerCount} 个已配置 · ${state.modelSummary}"
                  else "从预设新建，或手填中转站 / 自建网关 · 还差：${state.readinessProblem}",
              onClick = state.onOpenProviders,
              trailing = {
                Text(
                    text = "管理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
              },
          )
        }

        // ---- 外观 ----
        SettingsGroupHeader(text = "外观")
        SettingsGroup {
          // 配色方案：四个预设并排成色块，点一下即切换。
          SettingsRow(
              icon = FeatherIcons.Sliders,
              title = "配色方案",
              subtitle =
                  AppThemePreset.ALL_PRESETS
                      .firstOrNull { it.id == state.themePresetId }
                      ?.displayName
                      ?: "默认蓝",
          )
          ThemePresetRow(
              selected = state.themePresetId,
              dark = darkTheme,
              onSelect = state.onThemePresetChange,
          )
          SettingsDivider()
          // 聊天字号：三档（小 / 默认 / 大）。不做滑块——档位少时按钮更准，
          // 也不会因为拖到中间值出现「13.7sp」这种不可复现的状态。
          SettingsRow(
              title = "聊天字号",
              subtitle = "${state.textSize.toInt()} sp",
              trailing = {
                TextSizeStepper(value = state.textSize, onChange = state.onTextSizeChange)
              },
          )
          SettingsDivider()
          SettingsRow(
              title = "卡片缩放",
              subtitle = "${(state.cardScale * 100).toInt()}%",
              trailing = {
                ScaleStepper(value = state.cardScale, onChange = state.onCardScaleChange)
              },
          )
          SettingsDivider()
          SettingsRow(
              title = "正文颜色",
              subtitle = if (state.textColor == 0) "跟随主题" else "自定义",
              trailing = {
                ColorSwatchRow(selected = state.textColor, onSelect = state.onTextColorChange)
              },
          )
        }

        // ---- 对话 ----
        SettingsGroupHeader(text = "对话")
        SettingsGroup {
          // 对话模式放最前：它决定「助手被允许做到什么程度」，是比权限模式更上层的授权。
          SettingsRow(
              icon = FeatherIcons.Cpu,
              title = "对话模式",
              subtitle = chatModeLabel(state.chatMode),
          )
          ChatModeRow(selected = state.chatMode, onSelect = state.onChatModeChange)
          SettingsDivider()
          SettingsRow(
              icon = FeatherIcons.Shield,
              title = "权限模式",
              subtitle = permissionLabel(state.permissionMode),
          )
          PermissionModeRow(
              selected = state.permissionMode,
              onSelect = state.onPermissionModeChange,
          )
          SettingsDivider()
          SettingsRow(
              icon = FeatherIcons.Zap,
              title = "长期记忆",
              subtitle =
                  if (state.memoryEnabled) "已启用 · ${state.memoryCount} 条"
                  else "关闭时模型不会记住跨会话的信息",
              trailing = {
                Switch(
                    checked = state.memoryEnabled,
                    onCheckedChange = state.onMemoryEnabledChange,
                )
              },
          )
          SettingsDivider()
          SettingsRow(
              icon = FeatherIcons.Zap,
              title = "思考结束后自动折叠",
              subtitle = "流式期间展开，回答开始后收起",
              trailing = {
                Switch(
                    checked = state.thinkingAutoCollapse,
                    onCheckedChange = state.onThinkingAutoCollapseChange,
                )
              },
          )
          SettingsDivider()
          SettingsRow(
              icon = FeatherIcons.Zap,
              title = "工具运行状态条",
              subtitle = "在输入框上方显示工具实时输出",
              trailing = {
                Switch(
                    checked = state.toolStatusBar,
                    onCheckedChange = state.onToolStatusBarChange,
                )
              },
          )
          SettingsDivider()
          SettingsRow(
              icon = FeatherIcons.Cpu,
              title = "技能",
              subtitle = if (state.skillCount > 0) "已安装 ${state.skillCount} 个" else "尚未安装",
              onClick = state.onOpenSkills,
              trailing = {
                Text(
                    text = "管理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
              },
          )
        }

        // ---- 能力 ----
        SettingsGroupHeader(text = "能力")
        SettingsGroup {
          SettingsRow(
              icon = FeatherIcons.Zap,
              title = "MCP 服务端",
              subtitle =
                  if (state.mcpServerCount > 0) "已接入 ${state.mcpServerCount} 个"
                  else "接入外部 MCP 工具服务",
              onClick = state.onOpenMcp,
              trailing = {
                Text(
                    text = "管理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
              },
          )
          SettingsDivider()
          // 图片/视频生成合到一页：它们与对话同属「哪个用途用哪个模型」，
          // 拆成两页会各自重填一遍地址密钥（用户实测反馈：太重）。
          SettingsRow(
              icon = FeatherIcons.Cpu,
              title = "模型管理",
              subtitle =
                  if (state.configuredSceneCount > 0) "已配置 ${state.configuredSceneCount} 个用途"
                  else "为对话 / 图片 / 视频分别指定模型",
              onClick = state.onOpenModelManage,
              trailing = {
                Text(
                    text = "管理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
              },
          )
        }

        // ---- 高级 ----
        SettingsGroupHeader(text = "高级")
        SettingsGroup {
          SettingsRow(
              icon = FeatherIcons.Sliders,
              title = "提示词模板",
              subtitle = "自定义系统提示词的各个段落",
              onClick = state.onOpenPromptTemplates,
              trailing = {
                Text(
                    text = "编辑",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
              },
          )
          SettingsDivider()
          SettingsRow(
              icon = FeatherIcons.Zap,
              title = "长期记忆",
              subtitle = if (state.memoryCount > 0) "${state.memoryCount} 条" else "暂无",
              onClick = state.onOpenMemories,
              trailing = {
                Text(
                    text = "查看",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
              },
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
      }
    }
  }
}

/** 配色方案的四色块选择器。 */
@Composable
private fun ThemePresetRow(
    selected: String,
    dark: Boolean,
    onSelect: (String) -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    AppThemePreset.ALL_PRESETS.forEach { preset ->
      val (primary, bg) = preset.previewColors(dark)
      val isSelected = preset.id == selected
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.clickable { onSelect(preset.id) },
      ) {
        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier.size(44.dp)
                    .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
          androidx.compose.foundation.layout.Box(
              modifier =
                  Modifier.size(if (isSelected) 20.dp else 16.dp)
                      .background(primary, androidx.compose.foundation.shape.CircleShape),
          )
        }
        Text(
            text = preset.displayName,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

/** 字号步进器：小 / 默认 / 大。 */
@Composable
private fun TextSizeStepper(value: Float, onChange: (Float) -> Unit) {
  val steps = listOf(11f, 13f, 16f, 19f)
  Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    steps.forEach { sp ->
      val isSelected = kotlin.math.abs(value - sp) < 0.5f
      androidx.compose.material3.TextButton(onClick = { onChange(sp) }) {
        Text(
            text = sp.toInt().toString(),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** 权限模式三选一。 */
@Composable
private fun PermissionModeRow(selected: String, onSelect: (String) -> Unit) {
  val modes =
      listOf(
          "auto" to "自动执行",
          "confirm" to "危险工具确认",
          "readonly" to "只读",
      )
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    modes.forEach { (id, label) ->
      val isSelected = id == selected
      androidx.compose.material3.Surface(
          shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
          color =
              if (isSelected) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.clickable { onSelect(id) },
      ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
      }
    }
  }
}

/** 对话模式四选一。 */
@Composable
private fun ChatModeRow(selected: String, onSelect: (String) -> Unit) {
  val modes =
      listOf(
          "chat" to "对话",
          "plan" to "计划",
          "agent" to "执行",
          "control" to "受控",
      )
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    modes.forEach { (id, label) ->
      val isSelected = id == selected
      androidx.compose.material3.Surface(
          shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
          color =
              if (isSelected) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.clickable { onSelect(id) },
      ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
      }
    }
  }
}

private fun chatModeLabel(mode: String): String =
    when (mode) {
      "chat" -> "纯对话，不调用工具"
      "plan" -> "只读调查，不改动文件"
      "control" -> "可执行，但危险操作前先确认"
      else -> "可执行任务，不逐次确认"
    }

/** 卡片缩放四档。 */
@Composable
private fun ScaleStepper(value: Float, onChange: (Float) -> Unit) {
  val steps = listOf(0.85f, 1.0f, 1.15f, 1.3f)
  Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    steps.forEach { scale ->
      val isSelected = kotlin.math.abs(value - scale) < 0.02f
      androidx.compose.material3.TextButton(onClick = { onChange(scale) }) {
        Text(
            text = "${(scale * 100).toInt()}",
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** 正文颜色：跟随主题 + 四个预设色。 */
@Composable
private fun ColorSwatchRow(selected: Int, onSelect: (Int) -> Unit) {
  // 0 = 跟随主题；其余是 ARGB。
  val colors: List<Pair<Int, androidx.compose.ui.graphics.Color?>> =
      listOf(
          0 to null,
          0xFF0F172A.toInt() to androidx.compose.ui.graphics.Color(0xFF0F172A),
          0xFF6B4423.toInt() to androidx.compose.ui.graphics.Color(0xFF6B4423),
          0xFF7C2D12.toInt() to androidx.compose.ui.graphics.Color(0xFF7C2D12),
          0xFF1E3A5F.toInt() to androidx.compose.ui.graphics.Color(0xFF1E3A5F),
      )
  Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    colors.forEach { (argb, color) ->
      val isSelected = argb == selected
      androidx.compose.foundation.layout.Box(
          modifier =
              Modifier.size(if (isSelected) 28.dp else 24.dp)
                  .background(
                      color ?: MaterialTheme.colorScheme.surfaceVariant,
                      androidx.compose.foundation.shape.CircleShape,
                  )
                  .clickable { onSelect(argb) },
          contentAlignment = Alignment.Center,
      ) {
        if (color == null) {
          Text(
              text = "自动",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

private fun permissionLabel(mode: String): String =
    when (mode) {
      "auto" -> "自动执行，不逐次确认"
      "readonly" -> "只读，禁止任何写操作"
      else -> "危险工具逐次确认"
    }
