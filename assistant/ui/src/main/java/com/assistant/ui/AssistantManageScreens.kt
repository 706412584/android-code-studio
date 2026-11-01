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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.ui.compat.AppTextField
import com.assistant.ui.components.independent.SettingsDivider
import com.assistant.ui.components.independent.SettingsGroup
import com.assistant.ui.components.independent.SettingsGroupHeader
import com.assistant.ui.components.independent.SettingsRow
import com.assistant.ui.components.independent.settingsPageBackground
import com.assistant.ui.theme.AIEditorTheme
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Trash2

/**
 * 管理页的通用外壳：返回 + 标题 + 内容。
 *
 * <p>三个管理页（提示词模板 / 记忆 / Skill）版式完全一样，抽出来避免三份重复。
 */
@Composable
fun AssistantManageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
  AIEditorTheme(darkTheme = darkTheme, followHostFrame = false) {
    Column(modifier = modifier.fillMaxSize().background(settingsPageBackground())) {
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
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
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = Spacing.xs),
        )
      }
      content()
    }
  }
}

// ---- 提示词模板 ----

/** 一条可编辑的提示词模板。 */
data class PromptTemplateItem(
    val id: String,
    val label: String,
    /** 当前生效内容（自定义或内核默认）。 */
    val content: String,
    /** 是否已被用户自定义。 */
    val customized: Boolean,
)

/**
 * 提示词模板管理：列出内核的 8 个模板，可编辑与重置。
 *
 * <p><b>为什么值得暴露</b>：系统提示词决定了助手的行为方式（工作准则、工具用法、
 * 模式授权）。ACS 把这一页放在「高级」里，是少数「出问题才来找、但找到就能救」的开关。
 */
@Composable
fun AssistantPromptTemplatesScreen(
    templates: List<PromptTemplateItem>,
    onEdit: (id: String, content: String) -> Unit,
    onReset: (id: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  var editing by remember { mutableStateOf<PromptTemplateItem?>(null) }
  var draft by remember { mutableStateOf("") }

  AssistantManageScaffold("提示词模板", onBack, modifier, darkTheme) {
    if (editing != null) {
      val item = editing!!
      Column(
          modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
          verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "留空保存即恢复默认。占位符形如 {{TOOL_LIST}}，由运行时填充。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = false,
            maxLines = 20,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          TextButton(
              onClick = {
                onEdit(item.id, draft)
                editing = null
              }) {
                Text("保存")
              }
          TextButton(onClick = { editing = null }) { Text("取消") }
        }
      }
    } else {
      // 用 Column + verticalScroll 而不是 LazyColumn：模板只有 8 项，
      // 不需要虚拟化；而 LazyColumn 放在 Column（scaffold 的内容槽）里时高度约束
      // 容易出问题（实测点击不响应）。
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = Spacing.md),
      ) {
        SettingsGroupHeader(text = "内核模板")
        templates.forEach { item ->
          SettingsGroup(modifier = Modifier.padding(bottom = Spacing.xs)) {
            // 不用 SettingsRow：它的 onClick 挂在内部的 Modifier.clickable 上，
            // 套在 SettingsGroup 的 Surface 里时点击不响应（实测）。
            // 这里直接写一行带 clickable 的 Row，命中区域明确。
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                          draft = item.content
                          editing = item
                        }
                        .padding(horizontal = Spacing.lg, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (item.customized) "已自定义 · 点击编辑" else "使用默认 · 点击编辑",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (item.customized) {
                TextButton(onClick = { onReset(item.id) }) {
                  Text(
                      text = "重置",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.primary,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

// ---- 长期记忆 ----

/** 一条长期记忆。 */
data class MemoryItem(
    val id: String,
    val text: String,
    val createdAt: Long,
)

/**
 * 长期记忆管理：列出、删除。
 *
 * <p><b>为什么必须能删</b>：记忆会被注入每次请求的系统提示词。一条记错的记忆
 * （例如过期的偏好）会持续污染所有对话，而用户没有任何办法纠正它——这比「记不住」更糟。
 */
@Composable
fun AssistantMemoriesScreen(
    memories: List<MemoryItem>,
    onDelete: (id: String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  AssistantManageScaffold("长期记忆", onBack, modifier, darkTheme) {
    if (memories.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "还没有记忆。\n助手在对话中记下的事实会出现在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onClearAll) { Text("全部清空") }
        }
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md),
        ) {
          memories.forEach { item ->
            SettingsGroup(modifier = Modifier.padding(bottom = Spacing.xs)) {
              Row(
                  modifier =
                      Modifier.fillMaxWidth()
                          .padding(horizontal = Spacing.lg, vertical = 11.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                      text = item.text,
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurface,
                      maxLines = 4,
                      overflow = TextOverflow.Ellipsis,
                  )
                }
                androidx.compose.material3.IconButton(onClick = { onDelete(item.id) }) {
                  Icon(
                      FeatherIcons.Trash2,
                      contentDescription = "删除",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(16.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

// ---- Skill ----

/** 一个已安装的 skill。 */
data class SkillItem(
    val name: String,
    val description: String,
    val usable: Boolean,
)

/**
 * Skill 管理：列出已安装的 skill。
 *
 * <p><b>本页只读</b>：安装/卸载要扫描目录并校验 SKILL.md 结构，属于后续步骤。
 * 但「能看到装了什么」本身就有价值——skill 清单会注入提示词，
 * 用户至少该知道助手手里有哪些参考资料。
 */
@Composable
fun AssistantSkillsScreen(
    skills: List<SkillItem>,
    skillRoot: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  AssistantManageScaffold("Skill", onBack, modifier, darkTheme) {
    if (skills.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Text(
            text =
                "还没有安装 skill。\n\n把含 SKILL.md 的目录放进：\n$skillRoot\n\n" +
                    "skill 是按需加载的参考文档，只在模型认为需要时进入上下文。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = Spacing.md),
      ) {
        SettingsGroupHeader(text = "已安装 ${skills.size} 个")
        skills.forEach { item ->
          SettingsGroup(modifier = Modifier.padding(bottom = Spacing.xs)) {
            SettingsRow(
                title = item.name,
                subtitle = item.description.ifBlank { "（无说明）" },
                trailing = {
                  if (!item.usable) {
                    Text(
                        text = "不可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                  }
                },
            )
          }
        }
      }
    }
  }
}
