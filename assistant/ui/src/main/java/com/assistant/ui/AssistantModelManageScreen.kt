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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.assistant.ui.compat.AppTextField
import com.assistant.ui.components.independent.ProviderLogoIcon
import com.assistant.ui.components.independent.ProviderProtocol
import com.assistant.ui.components.independent.ProviderSelectionTarget
import com.assistant.ui.components.independent.SettingsDivider
import com.assistant.ui.components.independent.SettingsGroup
import com.assistant.ui.components.independent.SettingsGroupHeader
import com.assistant.ui.theme.Spacing

/**
 * 一个用途（scene）的模型分配。
 *
 * @param key 稳定标识（chat / image / video）
 * @param label 界面显示名
 * @param hint 一句话说明这个用途是干什么的
 */
data class ModelScene(
    val key: String,
    val label: String,
    val hint: String,
)

/**
 * 模型管理——**照 Aharou 的 `DefaultModelsSection` 的形制**。
 *
 * <p><b>为什么是「按用途分配」而不是「一个主模型」</b>：同一个助手要干不同的事——
 * 对话用文本模型，画图用图像模型，生成视频用视频模型。把它们混成一个字段，
 * 用户配了画图模型就没法保留对话模型（我第一版正是这么做的，还被要求拆成两页重填凭据，
 * 那是错的方向）。Aharou 的解法是一页列出所有用途，每行选「服务商 + 模型」。
 *
 * <p><b>为什么不再重填 baseUrl / apiKey</b>：地址与密钥属于服务商（见「服务商」页），
 * 一个服务商配一次，所有用途共用。这一页只做「哪个用途用谁」。
 *
 * <p><b>与 Aharou 的差异</b>：Aharou 有 7 个用途（对话/识图/压缩/标题/图片/语音STT/语音TTS），
 * 本 App 只实现了 3 个（对话/图片/视频）——其余用途依赖的子系统（OCR、语音、自动压缩）
 * 尚未接入，列出来只会是死开关。
 */
@Composable
fun AssistantModelManageScreen(
    providers: List<ProviderRecordItem>,
    /** 各用途当前的选择：key → (providerId, model)。 */
    selections: Map<String, Pair<String, String>>,
    onSelect: (sceneKey: String, providerId: String, model: String) -> Unit,
    onClear: (sceneKey: String) -> Unit,
    /**
     * 拉取某个服务商的模型清单。由宿主注入（渲染层不依赖引擎）。
     * 返回 (模型清单, 失败原因)。
     */
    onFetchModels:
        (baseUrl: String, apiKey: String, protocol: ProviderProtocol) -> Pair<List<String>, String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  // 正在展开的用途（选服务商/模型的小面板）；null = 都没展开。
  var expanded by remember { mutableStateOf<String?>(null) }
  val usable = providers.filter { it.usable }

  AssistantManageScaffold("模型管理", onBack, modifier, darkTheme) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      Text(
          text =
              "为不同用途指定模型。地址与密钥沿用「服务商」页里的配置，" +
                  "这里只选「谁来做这件事」。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = Spacing.sm),
      )

      MODEL_SCENES.forEach { scene ->
        val (providerId, model) = selections[scene.key] ?: ("" to "")
        val provider = usable.firstOrNull { it.id == providerId }
        SettingsGroup(modifier = Modifier.padding(bottom = Spacing.xs)) {
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .clickable { expanded = if (expanded == scene.key) null else scene.key }
                      .padding(horizontal = Spacing.lg, vertical = 11.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            if (provider != null) {
              ProviderLogoIcon(
                  provider =
                      ProviderSelectionTarget(
                          id = provider.id,
                          name = provider.label,
                          protocol = provider.protocol,
                      ),
                  size = 20.dp,
              )
            }
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.sm)) {
              Text(
                  text = scene.label,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                  text =
                      if (providerId.isEmpty() || model.isEmpty()) scene.hint
                      else "${provider?.label ?: providerId} · $model",
                  style = MaterialTheme.typography.bodySmall,
                  color =
                      if (providerId.isEmpty() || model.isEmpty())
                          MaterialTheme.colorScheme.onSurfaceVariant
                      else MaterialTheme.colorScheme.primary,
              )
            }
          }

          // 选择面板是**弹窗**而不是行内展开：它是一次性选择（选完就关），
          // 行内展开会把列表撑长、把其它用途挤下去（用户实测反馈「该用弹窗的地方平铺了」）。
          if (expanded == scene.key) {
            ScenePicker(
                scene = scene,
                providers = usable,
                currentProviderId = providerId,
                currentModel = model,
                onPick = { pid, m ->
                  onSelect(scene.key, pid, m)
                  expanded = null
                },
                onDismiss = { expanded = null },
                onFetchModels = onFetchModels,
                onClear = {
                  onClear(scene.key)
                  expanded = null
                },
            )
          }
        }
      }
    }
  }
}

/** 本 App 支持的用途。 */
private val MODEL_SCENES =
    listOf(
        ModelScene("chat", "对话", "未配置 — 点按选择服务商与对话模型"),
        ModelScene("image", "图片生成", "未配置 — 点按选择服务商与图片模型"),
        ModelScene("video", "视频生成", "未配置 — 点按选择服务商与视频模型"),
    )

/** 选中一个用途之后的弹窗：列服务商 → 填模型名。 */
@Composable
private fun ScenePicker(
    scene: ModelScene,
    providers: List<ProviderRecordItem>,
    currentProviderId: String,
    currentModel: String,
    onPick: (providerId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
    onFetchModels:
        (baseUrl: String, apiKey: String, protocol: ProviderProtocol) -> Pair<List<String>, String>,
    onClear: () -> Unit,
) {
  var pickedProviderId by remember(scene.key) { mutableStateOf(currentProviderId) }
  var modelText by remember(scene.key, pickedProviderId) {
    mutableStateOf(
        if (pickedProviderId == currentProviderId && currentModel.isNotEmpty()) currentModel
        else providers.firstOrNull { it.id == pickedProviderId }?.model.orEmpty())
  }
  // 拉取到的候选清单 + 拉取状态。
  var catalog by remember(scene.key, pickedProviderId) { mutableStateOf(listOf<String>()) }
  var fetching by remember(scene.key) { mutableStateOf(false) }
  var fetchMessage by remember(scene.key) { mutableStateOf("") }

  androidx.compose.ui.window.Dialog(
      onDismissRequest = onDismiss,
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
  ) {
  androidx.compose.material3.Surface(
      shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.fillMaxWidth().padding(Spacing.md),
  ) {
  Column(modifier = Modifier.padding(Spacing.lg)) {
    Text(
        text = "${scene.label}用哪个模型",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = Spacing.sm),
    )
    if (providers.isEmpty()) {
      Text(
          text = "还没有可用的服务商。先到「服务商」页添加一个（填好 API 地址与密钥）。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onDismiss) { Text("关闭") }
      return@Column
    }

    Text(
        text = "服务商",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.xs),
    )
    providers.forEach { provider ->
      val selected = provider.id == pickedProviderId
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .clickable {
                    pickedProviderId = provider.id
                    // 切换服务商时把模型名换成该服务商的主模型——大多数情况下
                    // 用户要的就是它，省一次输入。
                    modelText = provider.model
                  }
                  .padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        ProviderLogoIcon(
            provider =
                ProviderSelectionTarget(
                    id = provider.id,
                    name = provider.label,
                    protocol = provider.protocol,
                ),
            size = 16.dp,
        )
        Text(
            text = provider.label,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.sm),
        )
      }
    }

    Text(
        text = "模型",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
    )
    // 先拉清单再选：模型名要一字不差才不 404，手填基本靠猜。
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(
          enabled = !fetching && pickedProviderId.isNotEmpty(),
          onClick = {
            val provider = providers.firstOrNull { it.id == pickedProviderId }
            if (provider == null) return@TextButton
            fetching = true
            fetchMessage = ""
            val (models, error) = onFetchModels(provider.baseUrl, provider.apiKey, provider.protocol)
            fetching = false
            if (error.isEmpty() && models.isNotEmpty()) {
              catalog = models
              fetchMessage = "拉到 ${models.size} 个模型"
            } else {
              fetchMessage = error.ifEmpty { "目录为空（可手填）" }
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
    com.assistant.ui.compat.ModelDropdownField(
        label = "${scene.label}模型",
        value = modelText,
        onValueChange = { modelText = it },
        choices = catalog,
        placeholder =
            when (scene.key) {
              "image" -> "gpt-image-1 / flux-schnell"
              "video" -> "grok-imagine-video"
              else -> "deepseek-chat"
            },
    )

    Row(
        modifier = Modifier.padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      TextButton(
          enabled = pickedProviderId.isNotEmpty() && modelText.isNotBlank(),
          onClick = { onPick(pickedProviderId, modelText.trim()) }) {
            Text("确定")
          }
      if (currentProviderId.isNotEmpty()) {
        TextButton(onClick = onClear) { Text("清除") }
      }
    }
  }
  }
  }
}
