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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.assistant.ui.compat.AppTextField
import com.assistant.ui.components.independent.ProviderLogoIcon
import com.assistant.ui.components.independent.ProviderProtocol
import com.assistant.ui.components.independent.ProviderSelectionTarget
import com.assistant.ui.components.independent.SettingsDivider
import com.assistant.ui.components.independent.SettingsGroup
import com.assistant.ui.components.independent.SettingsGroupHeader
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2

/** 一条服务商记录（渲染层投影）。 */
data class ProviderRecordItem(
    val id: String,
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    /** 4 个槽位：main / 第二 / 第三 / 第四；空串表示跟随 main。 */
    val slots: List<String>,
    val protocol: ProviderProtocol,
    /** 是否可用（baseUrl 与主模型都非空）。 */
    val usable: Boolean,
) {
  /** 主模型（槽位 0）。 */
  val model: String get() = slots.firstOrNull().orEmpty()
}

/** 一个预设模板（用于「从预设新建」）。 */
data class PresetTemplate(
    val id: String,
    val label: String,
    val models: List<String>,
)

/**
 * 服务商管理——**与 ACS 的 `ProviderManagementPreference` 同一模型**。
 *
 * <p><b>为什么不是「预设 vs 自定义」两套</b>：ACS 的做法是「预设表退化为快速填充模板，
 * 真正的配置是用户自己的服务商记录」。这样每个服务商各有一份密钥、互不干扰
 * （旧实现 7 家共用一个键，切服务商会串），也能从预设一键新建后随意改。
 *
 * <p>本页提供两个动作：**从预设新建**（选一个预设，自动填好 baseUrl 与默认模型）、
 * **完全自定义**（手填全部字段）。
 */
@Composable
fun AssistantProvidersScreen(
    records: List<ProviderRecordItem>,
    presets: List<PresetTemplate>,
    onAddFromPreset: (presetId: String) -> Unit,
    onAddCustom: () -> Unit,
    onUpdate: (ProviderRecordItem) -> Unit,
    onDelete: (id: String) -> Unit,
    onFetchModels: (baseUrl: String, apiKey: String, protocol: ProviderProtocol) -> Pair<List<String>, String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  // null = 列表；"preset" = 选预设；"custom" = 手填；其它 = 编辑该 id。
  var mode by remember { mutableStateOf<String?>(null) }

  AssistantManageScaffold("服务商", onBack, modifier, darkTheme) {
    when (mode) {
      null ->
          ProviderList(
              records = records,
              onEdit = { mode = it.id },
              onDelete = onDelete,
              onAddFromPreset = { mode = "preset" },
              onAddCustom = { mode = "custom" },
          )
      "preset" ->
          PresetPicker(
              presets = presets,
              onPick = { presetId ->
                onAddFromPreset(presetId)
                mode = null
              },
              onCancel = { mode = null },
          )
      "custom" ->
          ProviderEditForm(
              existing = null,
              onCancel = { mode = null },
              onSave = {
                onUpdate(it)
                mode = null
              },
              onFetchModels = onFetchModels,
          )
      else -> {
        val existing = records.firstOrNull { it.id == mode }
        ProviderEditForm(
            existing = existing,
            onCancel = { mode = null },
            onSave = {
              onUpdate(it)
              mode = null
            },
            onFetchModels = onFetchModels,
        )
      }
    }
  }
}

@Composable
private fun ProviderList(
    records: List<ProviderRecordItem>,
    onEdit: (ProviderRecordItem) -> Unit,
    onDelete: (String) -> Unit,
    onAddFromPreset: () -> Unit,
    onAddCustom: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = Spacing.md),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.End,
    ) {
      TextButton(onClick = onAddFromPreset) { Text("从预设新建") }
      TextButton(onClick = onAddCustom) {
        Icon(
            FeatherIcons.Plus,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("自定义", modifier = Modifier.padding(start = 4.dp))
      }
    }

    if (records.isEmpty()) {
      Text(
          text =
              "还没有配置服务商。\n\n" +
                  "点「从预设新建」选一家（DeepSeek / OpenAI / Claude…），会自动填好地址与模型；\n" +
                  "或点「自定义」手填中转站、自建网关。\n\n" +
                  "每个服务商各存一份密钥，切换时不会串。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = Spacing.lg),
      )
    } else {
      records.forEach { item ->
        SettingsGroup(modifier = Modifier.padding(bottom = Spacing.xs)) {
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .clickable { onEdit(item) }
                      .padding(horizontal = Spacing.lg, vertical = 11.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            ProviderLogoIcon(
                provider =
                    ProviderSelectionTarget(
                        id = item.id,
                        name = item.label,
                        protocol = item.protocol,
                    ),
                size = 20.dp,
            )
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
              Text(
                  text = item.label.ifBlank { item.baseUrl },
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                  text =
                      if (item.usable) "${item.model} · ${item.baseUrl}"
                      else "未配置完整 · ${item.baseUrl.ifBlank { "无地址" }}",
                  style = MaterialTheme.typography.bodySmall,
                  color =
                      if (item.usable) MaterialTheme.colorScheme.onSurfaceVariant
                      else MaterialTheme.colorScheme.error,
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

@Composable
private fun PresetPicker(
    presets: List<PresetTemplate>,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = Spacing.md),
  ) {
    Text(
        text = "选一个预设，会自动填好 API 地址与默认模型。之后可以随意改。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacing.sm),
    )
    presets.forEach { preset ->
      SettingsGroup(modifier = Modifier.padding(bottom = Spacing.xs)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable { onPick(preset.id) }
                    .padding(horizontal = Spacing.lg, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          ProviderLogoIcon(
              provider =
                  ProviderSelectionTarget(
                      id = preset.id,
                      name = preset.label,
                      protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                  ),
              size = 20.dp,
          )
          Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
            Text(
                text = preset.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = preset.models.firstOrNull() ?: "（需手填模型）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
    TextButton(onClick = onCancel) { Text("取消") }
  }
}

@Composable
private fun ProviderEditForm(
    existing: ProviderRecordItem?,
    onCancel: () -> Unit,
    onSave: (ProviderRecordItem) -> Unit,
    /**
     * 拉取模型清单。由宿主注入——渲染层不依赖引擎（见 engine/build.gradle.kts 的说明）。
     * 返回 (模型清单, 失败原因)；成功时失败原因为空串。
     */
    onFetchModels: (baseUrl: String, apiKey: String, protocol: ProviderProtocol) -> Pair<List<String>, String>,
) {
  var label by remember { mutableStateOf(existing?.label ?: "") }
  var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
  var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
  var protocol by remember {
    mutableStateOf(existing?.protocol ?: ProviderProtocol.OPENAI_COMPATIBLE)
  }
  // 4 个槽位。默认只有 main 有值，其余空串表示「与 main 相同」。
  var slots by remember {
    mutableStateOf(
        List(4) { i -> existing?.slots?.getOrNull(i).orEmpty() })
  }
  // 拉取到的模型清单：拉取成功后每个槽位输入框都能从这里直选。
  var catalog by remember { mutableStateOf(listOf<String>()) }
  var fetching by remember { mutableStateOf(false) }
  var fetchMessage by remember { mutableStateOf("") }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = Spacing.md),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    AppTextField(
        value = label,
        onValueChange = { label = it },
        label = "名称",
        placeholder = "例如：DeepSeek",
    )
    AppTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = "API 地址",
        placeholder = "https://api.deepseek.com/v1",
    )
    AppTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = "API Key",
        visualTransformation = PasswordVisualTransformation(),
    )

    SettingsGroupHeader(text = "协议")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      listOf(
              ProviderProtocol.OPENAI_COMPATIBLE to "OpenAI 兼容",
              ProviderProtocol.ANTHROPIC_MESSAGES to "Anthropic Messages",
          )
          .forEach { (p, name) ->
            val selected = p == protocol
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color =
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { protocol = p },
            ) {
              Text(
                  text = name,
                  style = MaterialTheme.typography.bodySmall,
                  color =
                      if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              )
            }
          }
    }

    // ---- 模型槽位 ----
    SettingsGroupHeader(text = "模型")
    Text(
        text =
            "主模型用于对话；其余三个留空表示「与主模型相同」。" +
                "点「拉取模型」从服务商的 /models 取真实清单，之后可直接从下拉里选。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(
          enabled = !fetching && baseUrl.isNotBlank(),
          onClick = {
            fetching = true
            fetchMessage = ""
            // 宿主负责切线程（它知道该在哪个作用域里跑）。
            val (models, error) = onFetchModels(baseUrl, apiKey, protocol)
            fetching = false
            if (error.isEmpty() && models.isNotEmpty()) {
              catalog = models
              fetchMessage = "拉到 ${models.size} 个模型"
            } else {
              // 失败原因要分开：网络不通与目录为空是两种问题，
              // 混成一句「没有模型」会把用户引向改模型名。
              fetchMessage = error.ifEmpty { "目录为空（可手填模型名）" }
            }
          }) {
            Text(if (fetching) "拉取中…" else "拉取模型")
          }
      if (fetchMessage.isNotEmpty()) {
        Text(
            text = fetchMessage,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (catalog.isNotEmpty()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
        )
      }
    }

    SLOT_LABELS.forEachIndexed { index, slotLabel ->
      SlotField(
          label = slotLabel,
          value = slots[index],
          choices = catalog,
          onValueChange = { value ->
            slots = slots.toMutableList().also { it[index] = value }
          },
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      TextButton(
          onClick = {
            onSave(
                ProviderRecordItem(
                    // 新建时给空 id，由引擎生成；编辑时沿用原 id。
                    id = existing?.id ?: "",
                    label = label.trim(),
                    baseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(),
                    slots = slots.map { it.trim() },
                    protocol = protocol,
                    usable = baseUrl.isNotBlank() && slots.first().isNotBlank(),
                ))
          }) {
            Text("保存")
          }
      TextButton(onClick = onCancel) { Text("取消") }
    }
  }
}

/** 槽位名（与 ACS 的 SLOT_ORDER 一致）。 */
private val SLOT_LABELS = listOf("主模型", "第二模型", "第三模型", "第四模型")

/**
 * 一个模型槽位：可手填，也可从拉取到的清单里直选。
 *
 * <p>清单为空时只显示输入框（用户手填）；拉取成功后下方出现可点的候选行。
 * 不用下拉框是因为 Compose 的 ExposedDropdownMenu 在这个页面里与滚动容器冲突，
 * 候选行更直接。
 */
@Composable
private fun SlotField(
    label: String,
    value: String,
    choices: List<String>,
    onValueChange: (String) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = if (label == SLOT_LABELS[0]) "必填" else "留空 = 与主模型相同",
    )
    if (choices.isNotEmpty()) {
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .horizontalScroll(rememberScrollState())
                  .padding(top = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
      ) {
        choices.take(12).forEach { model ->
          val selected = model == value
          androidx.compose.material3.Surface(
              shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
              color =
                  if (selected) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier.clickable { onValueChange(model) },
          ) {
            Text(
                text = model,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }
      }
    }
  }
}
