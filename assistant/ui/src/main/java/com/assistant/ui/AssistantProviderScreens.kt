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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

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
    val baseUrl: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
) {
  /** 转成表单草稿：密钥留空（用户必须自己填），其余预填。 */
  fun toDraft(): ProviderRecordItem =
      ProviderRecordItem(
          id = "",
          label = label,
          baseUrl = baseUrl,
          apiKey = "",
          slots = List(4) { i -> models.getOrNull(i).orEmpty() },
          protocol = protocol,
          usable = baseUrl.isNotBlank() && models.isNotEmpty(),
      )
}

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
    /**
     * 保存一条记录。**suspend**：宿主在 IO 线程写存储，返回值非空表示失败原因
     * （用于就地提示并保留编辑页），null 表示保存成功。
     *
     * <p>改成 suspend 返回错误而不是 fire-and-forget：此前保存是同步 void，校验失败
     * 后仍会关窗，用户以为存上了。现在只有真正成功才退回列表。
     */
    onSave: suspend (ProviderRecordItem) -> String?,
    onDelete: (id: String) -> Unit,
    /**
     * 拉取模型清单。**suspend**：宿主在 IO 线程发起网络请求。
     * 返回 (模型清单, 失败原因)；成功时失败原因为空串。
     */
    onFetchModels:
        suspend (baseUrl: String, apiKey: String, protocol: ProviderProtocol) ->
            Pair<List<String>, String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  // null = 列表；"preset" = 选预设（短弹层）；"newFromPreset" = 预设预填表单页；
  // "custom" = 空表单页；其它 = 编辑该 id 的页面。
  var mode by remember { mutableStateOf<String?>(null) }
  // 从预设进入表单时的模板（保存时才转成记录）。
  var prefilled by remember { mutableStateOf<PresetTemplate?>(null) }

  when (mode) {
    null ->
        AssistantManageScaffold("服务商", onBack, modifier, darkTheme) {
          ProviderList(
              records = records,
              onEdit = { mode = it.id },
              onDelete = onDelete,
              onAddFromPreset = { mode = "preset" },
              onAddCustom = { mode = "custom" },
          )
        }
    // 选预设是**一次性选择**：留在短弹层里，选定后进入编辑页面（不叠 Dialog）。
    "preset" ->
        AssistantManageScaffold("从预设新建", { mode = null }, modifier, darkTheme) {
          PresetPicker(
              presets = presets,
              onPick = { presetId ->
                // **不直接落库**：选预设只是「填好草稿」，用户可能还要改名称或填密钥。
                prefilled = presets.firstOrNull { it.id == presetId }
                mode = "newFromPreset"
              },
              onCancel = { mode = null },
          )
        }
    else -> {
      val existing =
          when (mode) {
            "newFromPreset" -> prefilled?.toDraft()
            "custom" -> null
            else -> records.firstOrNull { it.id == mode }
          }
      val isNew = existing == null || existing.id.isEmpty()
      val title = if (isNew) "添加服务商" else "编辑服务商"
      ProviderEditForm(
          title = title,
          existing = existing,
          onCancel = {
            prefilled = null
            mode = null
          },
          onSave = { item ->
            val error = onSave(item)
            if (error == null) {
              prefilled = null
              mode = null
            }
            error
          },
          onFetchModels = onFetchModels,
      )
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
  // 选预设是**一次性选择**，用弹窗而不是新页面——
  // 平铺成页会让用户以为这是设置里的一个层级。
  androidx.compose.ui.window.Dialog(
      onDismissRequest = onCancel,
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
  ) {
  androidx.compose.material3.Surface(
      shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.fillMaxWidth().padding(Spacing.md),
  ) {
  Column(
      modifier =
          Modifier
              .verticalScroll(rememberScrollState())
              .padding(Spacing.lg),
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
  }
}

/**
 * 服务商编辑**页面**（不再是 Dialog）。
 *
 * <p><b>为什么从弹窗改页面</b>：这是「连接信息 + 协议 + 4 个模型槽 + 拉取按钮」的多字段
 * 长表单，弹窗里整表单与按钮一起滚动、长内容会把保存按钮顶出屏幕；而且它与列表在同一个
 * 视觉层级，用户看不出「我在编辑」。改页面后：标题栏固定、保存/取消固定在底栏、
 * 正文独立滚动。
 *
 * <p><b>草稿用 remember(existing?.id) 隔离</b>：切换编辑目标（或新建）时重置为那条记录的
 * 初值，不会沿用上一条的输入。API Key 只留在本组合内存里，不进 saved state / Bundle。
 *
 * <p><b>保存失败留在页面</b>：onSave 返回非空即错误文案，就地显示、不退出；成功（返回 null）
 * 才由调用方退回列表。
 */
@Composable
private fun ProviderEditForm(
    title: String,
    existing: ProviderRecordItem?,
    onCancel: () -> Unit,
    onSave: suspend (ProviderRecordItem) -> String?,
    onFetchModels:
        suspend (baseUrl: String, apiKey: String, protocol: ProviderProtocol) ->
            Pair<List<String>, String>,
) {
  // 以记录 id 为 key：换记录/从列表重进即重建 draft，避免串数据。
  val draftKey = existing?.id ?: "new"
  var label by remember(draftKey) { mutableStateOf(existing?.label ?: "") }
  var baseUrl by remember(draftKey) { mutableStateOf(existing?.baseUrl ?: "") }
  var apiKey by remember(draftKey) { mutableStateOf(existing?.apiKey ?: "") }
  var protocol by remember(draftKey) {
    mutableStateOf(existing?.protocol ?: ProviderProtocol.OPENAI_COMPATIBLE)
  }
  var slots by remember(draftKey) {
    mutableStateOf(List(4) { i -> existing?.slots?.getOrNull(i).orEmpty() })
  }

  // 拉取状态属于「一次请求」，不进保存配置。
  var catalog by remember(draftKey) { mutableStateOf(listOf<String>()) }
  var fetching by remember(draftKey) { mutableStateOf(false) }
  var fetchMessage by remember(draftKey) { mutableStateOf("") }

  var labelError by remember(draftKey) { mutableStateOf<String?>(null) }
  var urlError by remember(draftKey) { mutableStateOf<String?>(null) }
  var mainModelError by remember(draftKey) { mutableStateOf<String?>(null) }
  var saveError by remember(draftKey) { mutableStateOf<String?>(null) }
  var saving by remember(draftKey) { mutableStateOf(false) }

  val scope = rememberCoroutineScope()

  AssistantManageScaffold(
      title = title,
      onBack = onCancel,
      bottomBar = {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
          TextButton(onClick = onCancel, enabled = !saving) { Text("取消") }
          androidx.compose.material3.Button(
              onClick = {
                // 就地校验：都不通过则不进 IO。
                labelError = if (label.isBlank()) "请填写名称" else null
                urlError =
                    if (baseUrl.isBlank()) "请填写 API 地址"
                    else if (!baseUrl.trim().startsWith("http")) "地址需以 http(s):// 开头"
                    else null
                mainModelError = if (slots.firstOrNull().isNullOrBlank()) "请填写主模型" else null
                if (labelError != null || urlError != null || mainModelError != null) return@Button
                val item =
                    ProviderRecordItem(
                        // 新建时给空 id，由引擎生成；编辑时沿用原 id。
                        id = existing?.id ?: "",
                        label = label.trim(),
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        slots = slots.map { it.trim() },
                        protocol = protocol,
                        usable = baseUrl.isNotBlank() && slots.first().isNotBlank(),
                    )
                saving = true
                saveError = null
                scope.launch {
                  val error = onSave(item)
                  saving = false
                  // 只有返回 null（成功）时调用方才会让我们退出；非空则留在本页。
                  if (error != null) saveError = error
                }
              },
              enabled = !saving,
              modifier = Modifier.weight(1f),
          ) {
            Text(if (saving) "保存中…" else "保存")
          }
        }
      },
  ) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      AppTextField(
          value = label,
          onValueChange = {
            label = it
            labelError = null
          },
          label = "名称",
          placeholder = "例如：DeepSeek",
          isError = labelError != null,
          supportingText = labelError?.let { { Text(it) } },
      )
      AppTextField(
          value = baseUrl,
          onValueChange = {
            baseUrl = it
            urlError = null
          },
          label = "API 地址",
          placeholder = "https://api.deepseek.com/v1",
          isError = urlError != null,
          supportingText = urlError?.let { { Text(it) } },
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

      SettingsGroupHeader(text = "模型")
      Text(
          text =
              "主模型用于对话；其余三个留空表示「与主模型相同」。" +
                  "点「拉取模型」从服务商的 /models 取真实清单。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = !fetching && baseUrl.isNotBlank(),
            onClick = {
              fetching = true
              fetchMessage = ""
              // suspend 调用在 IO 由宿主完成；这里只关心结果。
              scope.launch {
                val (models, error) = onFetchModels(baseUrl.trim(), apiKey, protocol)
                fetching = false
                if (error.isEmpty() && models.isNotEmpty()) {
                  catalog = models
                  fetchMessage = "拉到 ${models.size} 个模型"
                } else {
                  // 失败原因分开：网络不通与目录为空是两种问题，
                  // 混成一句「没有模型」会把用户引向改模型名。
                  fetchMessage = error.ifEmpty { "目录为空（可手填模型名）" }
                }
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
            isError = index == 0 && mainModelError != null,
            supportingText = if (index == 0) mainModelError else null,
            onValueChange = { value ->
              slots = slots.toMutableList().also { it[index] = value }
              if (index == 0) mainModelError = null
            },
        )
      }

      if (saveError != null) {
        Text(
            text = saveError!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Spacing.xs),
        )
      }
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
    isError: Boolean = false,
    supportingText: String? = null,
) {
  // 用下拉框而不是「输入框 + 候选行」：候选行会把页面撑得很长，
  // 而下拉是同类交互的既有形制（ACS 用 MaterialAutoCompleteTextView）。
  Column {
    com.assistant.ui.compat.ModelDropdownField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        choices = choices,
        placeholder = if (label == SLOT_LABELS[0]) "必填" else "留空 = 与主模型相同",
    )
    if (isError && supportingText != null) {
      Text(
          text = supportingText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(start = Spacing.sm, top = 2.dp),
      )
    }
  }
}
