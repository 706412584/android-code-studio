/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.ai.protocol.ModelProtocolType
import com.tom.rv2ide.artificial.agent.compose.compat.AppTextField
import com.tom.rv2ide.artificial.agent.compose.components.independent.ProviderLogoIcon
import com.tom.rv2ide.artificial.agent.compose.components.independent.ProviderProtocol
import com.tom.rv2ide.artificial.agent.compose.components.independent.ProviderSelectionTarget
import com.tom.rv2ide.artificial.agent.compose.components.independent.SettingsDivider
import com.tom.rv2ide.artificial.agent.compose.components.independent.SettingsGroup
import com.tom.rv2ide.artificial.agent.compose.components.independent.SettingsGroupHeader
import com.tom.rv2ide.artificial.agent.compose.components.independent.settingsPageBackground
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.resources.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.launch

/**
 * 槽位名的字符串资源 id（与 ProviderConfig.SLOT_ORDER 一致：main/haiku/sonnet/opus）。
 *
 * <p>存资源 id 而非字面量：顶层 val 无法调用 `stringResource`，文案在 Composable
 * 作用域内再解析（见 [ProviderEditPage] 的 `slotLabels`）。
 */
private val SLOT_LABEL_RES =
    listOf(
        R.string.ai_agent_provider_slot_main,
        R.string.manage_provider_slot_second,
        R.string.manage_provider_slot_third,
        R.string.manage_provider_slot_fourth,
    )

/** 1M 窗口的 token 数，由解析器算出，避免在 UI 里散落字面量。 */
private val ONE_M_TOKENS: Int = com.tom.rv2ide.artificial.agent.ContextSizeParser.parse("1m")

/**
 * 按勾选态给模型名加/去 `[1m]` 后缀。
 *
 * <p>先 [ContextSizeParser.stripSuffix] 剥掉已有后缀再附新后缀——直接拼接会把
 * `foo[200k]` 变成 `foo[200k][1m]` 这种非法值（解析器只看最后一个后缀，语义被静默改写）。
 * 基名为空时原样返回（空槽位无法承载后缀）。
 */
private fun with1mSuffix(model: String, checked: Boolean): String {
  val base = com.tom.rv2ide.artificial.agent.ContextSizeParser.stripSuffix(model)
  if (base.isEmpty()) return base
  return if (checked) "$base[${com.tom.rv2ide.artificial.agent.ContextSizeParser.format(ONE_M_TOKENS)}]" else base
}

/**
 * 服务商管理页：**列表**与**编辑**是同一页面的两个状态，不再各有各的弹窗。
 *
 * <p>用 `mode` 表达当前态：null = 列表；"preset" = 选预设；"new" = 新建表单；
 * 其它 = 编辑该 id。返回（[onBack] / 顶栏返回）在编辑态先回列表、列表态才退出页面——
 * 返回层级与用户的「我在哪一层」直觉一致。
 *
 * <p>草稿用 `remember(draftKey)` 隔离：换记录/新建即重建，不串数据；API Key 只留在
 * 组合内存里，不进 saved state。
 */
@Composable
fun ProviderManageScreen(
    records: List<ManageProviderItem>,
    presets: List<com.tom.rv2ide.artificial.agent.ProviderPresets.Preset>,
    onSave: suspend (ManageProviderItem) -> String?,
    onDelete: suspend (String) -> Unit,
    onFetchModels: suspend (ManageProviderItem) -> Pair<List<String>, String>,
    onBack: () -> Unit,
) {
  var mode by remember { mutableStateOf<String?>(null) }
  var prefilled by remember { mutableStateOf<ManageProviderItem?>(null) }

  // 系统返回键：非列表态先回列表（与顶栏返回按钮同一路径），列表态才放行退出页面。
  // 不拦截的话，用户在编辑页按返回会直接跳出整个页面，丢掉「列表」这一层——
  // 实测就是这个问题。
  androidx.activity.compose.BackHandler(enabled = mode != null) {
    prefilled = null
    mode = null
  }

  // 编辑态：交给表单页；列表态：列表。
  val editingKey = mode
  if (editingKey != null && editingKey != "preset") {
    // isNew 由 mode 决定，**不能**看 existing.id 是否为空——从预设新建的草稿带着预设 id，
    // 但语义仍是「新建」。
    val isNew = editingKey == "new"
    val existing = if (isNew) prefilled else records.firstOrNull { it.id == editingKey }
    ProviderEditPage(
        existing = existing,
        isNew = isNew,
        onFetchModels = onFetchModels,
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
    )
    return
  }

  if (editingKey == "preset") {
    PresetPickPage(
        presets = presets,
        onPick = { preset ->
          prefilled = preset.toManageDraft()
          mode = "new"
        },
        onBack = { mode = null },
    )
    return
  }

  ProviderListPage(
      records = records,
      onEdit = { mode = it.id },
      onDelete = onDelete,
      onAddFromPreset = { mode = "preset" },
      onAddCustom = { mode = "new" },
      onBack = onBack,
  )
}

@Composable
private fun ProviderListPage(
    records: List<ManageProviderItem>,
    onEdit: (ManageProviderItem) -> Unit,
    onDelete: suspend (String) -> Unit,
    onAddFromPreset: () -> Unit,
    onAddCustom: () -> Unit,
    onBack: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var pendingDelete by remember { mutableStateOf<ManageProviderItem?>(null) }

  ManagePage(
      title = stringResource(R.string.ai_agent_providers_title),
      onBack = onBack,
      // 添加动作收进顶栏，不再靠列表顶部两行按钮占位。
      actions = {
        TextButton(onClick = onAddFromPreset) {
          Text(stringResource(R.string.ai_agent_provider_from_preset))
        }
        TextButton(onClick = onAddCustom) {
          Icon(
              FeatherIcons.Plus,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.primary,
          )
          Text(
              stringResource(R.string.manage_custom),
              modifier = Modifier.padding(start = 4.dp),
          )
        }
      },
  ) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
    ) {
      if (records.isEmpty()) {
        Text(
            text = stringResource(R.string.manage_provider_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.lg),
        )
      } else {
        SettingsGroupHeader(
            text = stringResource(R.string.manage_configured_count, records.size)
        )
        records.forEach { item ->
          // 卡片色取 IDE 主题的 colorSurfaceContainer：比页面背景（colorBackground）
          // 深/浅一档，卡片才看得见轮廓。不传则用助手面板语义色 cardSurface——那来自
          // 另一套预设，与 IDE 主题不同源（用户实测：页面背景没跟随 IDE 配色）。
          SettingsGroup(
              modifier = Modifier.padding(bottom = Spacing.xs),
              color = ideThemeColor(com.tom.rv2ide.R.attr.colorSurfaceContainer),
          ) {
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
                          protocol = item.protocol.toUi(),
                      ),
                  size = 20.dp,
              )
              Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(
                    text = item.label.ifBlank { item.baseUrl },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val noUrl = stringResource(R.string.manage_provider_no_url)
                Text(
                    text =
                        if (item.usable) "${item.mainModel} · ${item.baseUrl}"
                        else {
                          val status = stringResource(R.string.ai_agent_provider_incomplete)
                          "$status · ${item.baseUrl.ifBlank { noUrl }}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (item.usable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
              }
              IconButton(onClick = { pendingDelete = item }) {
                Icon(
                    FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.delete),
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

  val target = pendingDelete
  if (target != null) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.manage_provider_delete_title)) },
        text = {
          Text(
              stringResource(
                  R.string.manage_provider_delete_confirm,
                  target.label.ifBlank { target.baseUrl },
              )
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                pendingDelete = null
                scope.launch { onDelete(target.id) }
              }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
              }
        },
        dismissButton = {
          TextButton(onClick = { pendingDelete = null }) {
            Text(stringResource(R.string.cancel))
          }
        },
    )
  }
}

@Composable
private fun PresetPickPage(
    presets: List<com.tom.rv2ide.artificial.agent.ProviderPresets.Preset>,
    onPick: (com.tom.rv2ide.artificial.agent.ProviderPresets.Preset) -> Unit,
    onBack: () -> Unit,
) {
  ManagePage(title = stringResource(R.string.ai_agent_provider_from_preset), onBack = onBack) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
    ) {
      Text(
          text = stringResource(R.string.manage_provider_preset_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = Spacing.sm),
      )
      presets.forEach { preset ->
        SettingsGroup(
            modifier = Modifier.padding(bottom = Spacing.xs),
            color = ideThemeColor(com.tom.rv2ide.R.attr.colorSurfaceContainer),
        ) {
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .clickable { onPick(preset) }
                      .padding(horizontal = Spacing.lg, vertical = 11.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            ProviderLogoIcon(
                provider =
                    ProviderSelectionTarget(
                        id = preset.getId(),
                        name = preset.getLabel(),
                        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    ),
                size = 20.dp,
            )
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
              Text(
                  text = preset.getLabel(),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                  text = preset.getBaseUrl(),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProviderEditPage(
    existing: ManageProviderItem?,
    isNew: Boolean,
    onFetchModels: suspend (ManageProviderItem) -> Pair<List<String>, String>,
    onCancel: () -> Unit,
    onSave: suspend (ManageProviderItem) -> String?,
) {
  val draftKey = existing?.id ?: "new"
  var label by remember(draftKey) { mutableStateOf(existing?.label ?: "") }
  var baseUrl by remember(draftKey) { mutableStateOf(existing?.baseUrl ?: "") }
  var apiKey by remember(draftKey) { mutableStateOf(existing?.apiKey ?: "") }
  var protocol by remember(draftKey) { mutableStateOf(existing?.protocol ?: ModelProtocolType.OPENAI_COMPATIBLE) }
  var slots by remember(draftKey) {
    mutableStateOf(List(4) { i -> existing?.slots?.getOrNull(i).orEmpty() })
  }

  var catalog by remember(draftKey) { mutableStateOf(listOf<String>()) }
  // 当前展开了下拉菜单的槽位索引；-1 = 都没展开。四个槽位共用同一个 catalog，
  // 但一次只允许一个菜单展开（同时开两个会互相遮挡）。
  var expandedSlot by remember(draftKey) { mutableStateOf(-1) }
  var fetching by remember(draftKey) { mutableStateOf(false) }
  var fetchMessage by remember(draftKey) { mutableStateOf("") }

  var labelError by remember(draftKey) { mutableStateOf<String?>(null) }
  var urlError by remember(draftKey) { mutableStateOf<String?>(null) }
  var mainError by remember(draftKey) { mutableStateOf<String?>(null) }
  var saveError by remember(draftKey) { mutableStateOf<String?>(null) }
  var saving by remember(draftKey) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // onConfirm / onCheckedChange 都不是 Composable lambda，文案必须先在作用域内取好。
  val nameRequired = stringResource(R.string.manage_provider_name_required)
  val urlRequired = stringResource(R.string.manage_provider_url_required)
  val urlScheme = stringResource(R.string.manage_provider_url_scheme)
  val mainRequired = stringResource(R.string.manage_provider_main_required)
  val slotLabels = SLOT_LABEL_RES.map { stringResource(it) }
  val requiredHint = stringResource(R.string.manage_required)
  val sameAsMainHint = stringResource(R.string.manage_provider_slot_same_hint)
  val fetchEmptyText = stringResource(R.string.ai_agent_provider_fetch_empty)
  // 拉取结果在协程里拼，带 %1$d 的模板只能走 resources。
  val resources = LocalContext.current.resources

  fun currentDraft() =
      ManageProviderItem.draft(
          // 编辑沿用原 id；新建沿用草稿 id（从预设进来时即预设 id，见 toManageDraft）。
          // 两者都为空才回退 label（完全自定义新建）。
          id = existing?.id.orEmpty(),
          label = label.trim(),
          baseUrl = baseUrl.trim(),
          apiKey = apiKey.trim(),
          slots = slots.map { it.trim() },
          protocol = protocol,
      )

  ManagePage(
      title =
          stringResource(
              if (isNew) R.string.ai_agent_provider_add
              // 独立页面只有标题可交代对象：复用弹窗时代的 ai_agent_provider_edit（值只有
              // "Edit"/"编辑"）会看不出在编辑什么，因此用「编辑服务商」。
              else R.string.manage_provider_edit_title),
      onBack = onCancel,
      bottomBar = {
        ManageBottomBar(
            onCancel = onCancel,
            confirming = saving,
            onConfirm = {
              labelError = if (label.isBlank()) nameRequired else null
              urlError =
                  if (baseUrl.isBlank()) urlRequired
                  else if (!baseUrl.trim().startsWith("http")) urlScheme
                  else null
              mainError = if (slots.firstOrNull().isNullOrBlank()) mainRequired else null
              if (labelError != null || urlError != null || mainError != null) return@ManageBottomBar
              val item = currentDraft()
              saving = true
              saveError = null
              scope.launch {
                val error = onSave(item)
                saving = false
                if (error != null) saveError = error
              }
            },
        )
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
          label = stringResource(R.string.ai_agent_provider_name),
          placeholder = stringResource(R.string.manage_provider_name_hint),
          isError = labelError != null,
          supportingText = labelError?.let { { Text(it) } },
      )
      AppTextField(
          value = baseUrl,
          onValueChange = {
            baseUrl = it
            urlError = null
          },
          label = stringResource(R.string.ai_agent_provider_base_url),
          // Anthropic 协议只填域名即可：路径由协议层补全（api.anthropic.com → /v1/messages；
          // 带 /v1 只补 /messages；已含 /messages 直接用）。OpenAI 兼容才需要显式 /v1。
          placeholder =
              if (protocol == ModelProtocolType.ANTHROPIC_MESSAGES) "https://api.anthropic.com"
              else "https://api.deepseek.com/v1",
          isError = urlError != null,
          supportingText = urlError?.let { { Text(it) } },
      )
      AppTextField(
          value = apiKey,
          onValueChange = { apiKey = it },
          label = stringResource(R.string.ai_agent_provider_api_key),
          visualTransformation = PasswordVisualTransformation(),
      )

      SettingsGroupHeader(text = stringResource(R.string.ai_agent_provider_protocol))
      Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        listOf(
                ModelProtocolType.OPENAI_COMPATIBLE to
                    stringResource(R.string.manage_provider_protocol_openai),
                ModelProtocolType.ANTHROPIC_MESSAGES to "Anthropic Messages",
            )
            .forEach { (p, name) ->
              val selected = p == protocol
              Surface(
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
      Text(
          text =
              stringResource(
                  if (protocol == ModelProtocolType.ANTHROPIC_MESSAGES)
                      R.string.manage_provider_url_hint_anthropic
                  else R.string.manage_provider_url_hint_openai
              ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      SettingsGroupHeader(text = stringResource(R.string.common_model))
      Text(
          text = stringResource(R.string.manage_provider_slots_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = !fetching && baseUrl.isNotBlank(),
            onClick = {
              fetching = true
              fetchMessage = ""
              val draft = currentDraft()
              scope.launch {
                val (models, error) = onFetchModels(draft)
                fetching = false
                if (error.isEmpty() && models.isNotEmpty()) {
                  catalog = models
                  fetchMessage =
                      resources.getString(R.string.ai_agent_provider_fetch_ok, models.size)
                } else {
                  fetchMessage = error.ifEmpty { fetchEmptyText }
                }
              }
            }) {
              Text(
                  stringResource(
                      if (fetching) R.string.ai_agent_provider_fetching
                      else R.string.ai_agent_provider_fetch_models
                  )
              )
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

      slotLabels.forEachIndexed { index, slotLabel ->
        Column {
          // 拉取成功后的候选清单挂在**输入框右端的下拉箭头**上（与协议下拉同一交互），
          // 不再在框下平铺一排横向滚动的小字：模型目录常有几十条，平铺既占空间又难扫读。
          Box {
            AppTextField(
                value = slots[index],
                onValueChange = { v ->
                  slots = slots.toMutableList().also { it[index] = v }
                  if (index == 0) mainError = null
                },
                label = slotLabel,
                placeholder = if (index == 0) requiredHint else sameAsMainHint,
                isError = index == 0 && mainError != null,
                supportingText = if (index == 0) mainError?.let { { Text(it) } } else null,
                trailingIcon =
                    if (catalog.isNotEmpty()) {
                      {
                        IconButton(onClick = { expandedSlot = index }) {
                          Icon(
                              if (expandedSlot == index) FeatherIcons.ChevronUp
                              else FeatherIcons.ChevronDown,
                              contentDescription = null,
                              tint = MaterialTheme.colorScheme.onSurfaceVariant,
                              modifier = Modifier.size(20.dp),
                          )
                        }
                      }
                    } else {
                      null
                    },
            )
            DropdownMenu(
                expanded = expandedSlot == index,
                onDismissRequest = { expandedSlot = -1 },
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
              // 限高 + 内滚：OpenRouter 这类服务商有几百个模型，不限高会把菜单撑出屏幕。
              Column(
                  Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                catalog.forEach { candidate ->
                  DropdownMenuItem(
                      text = {
                        Text(
                            text = candidate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color =
                                if (candidate == slots[index])
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                      },
                      onClick = {
                        slots = slots.toMutableList().also { it[index] = candidate }
                        if (index == 0) mainError = null
                        expandedSlot = -1
                      },
                  )
                }
              }
            }
          }
          // 「1M 上下文」勾选：勾选即给模型名加 [1m] 后缀（复用 ContextSizeParser，
          // 不直接拼字符串——避免把已有的 [200k] 叠成非法值）。这一项在旧弹窗表单里
          // 存在，页面化必须保留，否则用户无法再声明 1M 窗口。
          Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked =
                    com.tom.rv2ide.artificial.agent.ContextSizeParser.parseFromModelId(slots[index]) ==
                        ONE_M_TOKENS,
                onCheckedChange = { checked ->
                  slots =
                      slots.toMutableList().also {
                        it[index] = with1mSuffix(it[index], checked)
                      }
                  if (index == 0) mainError = null
                },
            )
            Text(
                text = stringResource(R.string.manage_provider_1m_context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          }
      }

      if (saveError != null) {
        Text(
            text = saveError!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

/** 把本地 ProviderProtocol 投影给 ProviderLogoIcon 用的 UI 协议枚举。 */
private fun ModelProtocolType.toUi(): ProviderProtocol =
    if (this == ModelProtocolType.ANTHROPIC_MESSAGES) ProviderProtocol.ANTHROPIC_MESSAGES
    else ProviderProtocol.OPENAI_COMPATIBLE

/** 预设 → 管理页草稿（id 用预设 id，见 ManageProviderItem.draft 的说明）。 */
private fun com.tom.rv2ide.artificial.agent.ProviderPresets.Preset.toManageDraft(): ManageProviderItem =
    ManageProviderItem.draft(
        id = getId(),
        label = getLabel(),
        baseUrl = getBaseUrl(),
        apiKey = "",
        slots =
            listOf(
                getDefaultModel(),
                "",
                "",
                "",
            ),
        protocol = getProtocolType(),
    )
