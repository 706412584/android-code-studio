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

package com.tom.rv2ide.artificial.agent.compose.components.independent

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.artificial.agent.compose.components.tools.AdaptiveModalBottomSheet
import com.tom.rv2ide.artificial.agent.compose.compat.AppTextField
import com.tom.rv2ide.artificial.agent.compose.compat.ChevronRotationStyle
import com.tom.rv2ide.artificial.agent.compose.compat.ExpandableChevronIcon
import com.tom.rv2ide.artificial.agent.compose.compat.ModelMetadata
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.artificial.agent.compose.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check

/**
 * 模型选择弹窗「按提供商折叠」的展开状态持久化：记住用户折叠了哪些提供商，重开弹窗与重启后保持。
 *
 * 存的是折叠集合（默认空 = 全部展开）；提供商删除后残留的 id 无副作用。搜索时的强制展开不写入此状态。
 *
 * <p>ACS 适配：偏好文件名从 Aharou 的 `model_sheet_collapse` 改为 ACS 惯例的
 * `ai_agent_tools`（同 AssistantComposeRender 的偏好文件），key 改为
 * `model_sheet_collapsed_ids` 前缀式命名，避免与同一偏好文件里的其它键撞名。
 */
class ModelSheetCollapseStore(context: Context) {

    private val prefs = context.getSharedPreferences("ai_agent_tools", Context.MODE_PRIVATE)

    fun collapsedProviderIds(): Set<String> =
        prefs.getStringSet(COLLAPSED_IDS_KEY, null)?.toSet() ?: emptySet()

    /** 传新集合副本写入：SharedPreferences 禁止复用已存实例。 */
    fun save(collapsedProviderIds: Set<String>) {
        prefs.edit().putStringSet(COLLAPSED_IDS_KEY, HashSet(collapsedProviderIds)).apply()
    }

    private companion object {
        const val COLLAPSED_IDS_KEY = "model_sheet_collapsed_ids"
    }
}

/**
 * 分组小标题：卡片上方灰色小字，左对齐。
 *
 * 传了 [onToggle] 就变成可点折叠的标题（右侧带个朝下/朝右的小箭头），由调用方控制 [expanded]。
 * 其余分组原子（SettingsGroup / SettingsRow / CollapsibleGroupHeader / ModelSearchField 等）
 * 在同包的 SettingsGroupComponents.kt，此处不重复。
 */
@Composable
internal fun SettingsGroupHeader(
    text: String,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
            .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.lg, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (expanded != null) {
            ExpandableChevronIcon(
                expanded = expanded,
                style = ChevronRotationStyle.RIGHT_DOWN,
                size = 16.dp,
                tint = MaterialTheme.semanticColors.subtleText
            )
        }
    }
}

/** 分组内行间分隔线：同包 SettingsGroupComponents.kt。 */

/**
 * 合成音色输入框的状态。音色名各家格式不同（`alloy` / `longxiaochun` /
 * `FunAudioLLM/CosyVoice2-0.5B:alex`），无法用统一枚举，故纯文本输入、原样透传。
 */
internal data class VoiceFieldState(
    val value: String,
    val onValueChange: (String) -> Unit,
    val placeholder: String
)

/**
 * 模型选择弹窗：风格与拉取模型弹窗保持一致（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 识图模型、压缩模型与主页聊天模型共用此组件，仅文案不同；右上角「重置」清除专用模型配置（回退跟随聊天模型），主页场景传 null 不显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectionSheet(
    title: String,
    noModelsText: String,
    providers: List<ProviderSelectionTarget>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** 模型名过滤：语音模型等专用场景用它把不相关的模型挡在外面。 */
    modelFilter: ((String) -> Boolean)? = null,
    /** 非空时在列表上方插一个音色输入框（仅语音合成用）。 */
    voiceField: VoiceFieldState? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val collapseStore = remember { ModelSheetCollapseStore(context.applicationContext) }
    var searchQuery by remember { mutableStateOf("") }
    var collapsedProviderIds by remember { mutableStateOf(collapseStore.collapsedProviderIds()) }

    // ACS 适配：AdaptiveModalBottomSheet 的 ACS 权威实现未暴露 sheetGesturesEnabled
    // （Aharou 传 true，与平台默认行为一致），删去该实参即可，不为此扩参数面。
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                onClear?.let { onClear ->
                    Text(
                        text = stringResource(R.string.common_reset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onClear() }
                    )
                }
            }

            ModelSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.provider_filter_models_hint)
            )

            voiceField?.let { field ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_voice_tts_voice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(64.dp)
                    )
                    AppTextField(
                        value = field.value,
                        onValueChange = field.onValueChange,
                        placeholder = field.placeholder,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val activeProviders = providers.filter { it.isEnabled && it.models.isNotEmpty() }
            // 过滤后的可选项：过滤为空时视为该 provider 无可用模型，不展示它的分组
            fun visibleModels(provider: ProviderSelectionTarget): List<String> =
                provider.models.filter {
                    (modelFilter == null || modelFilter(it)) &&
                        (searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true))
                }
            val hasAnyVisible = activeProviders.any { visibleModels(it).isNotEmpty() }
            if (!hasAnyVisible) {
                SettingsGroup {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = noModelsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    activeProviders.forEach { provider ->
                        val filteredModels = visibleModels(provider)
                        if (filteredModels.isNotEmpty()) {
                            // 搜索时强制展开，否则用折叠状态：用户搜到名字却看不到结果会很困惑。
                            val expanded = searchQuery.isNotBlank() || provider.id !in collapsedProviderIds
                            item(key = "header_${provider.id}") {
                                CollapsibleGroupHeader(
                                    text = "${provider.name} (${filteredModels.size})",
                                    expanded = expanded,
                                    onToggle = {
                                        val updated = if (provider.id in collapsedProviderIds) {
                                            collapsedProviderIds - provider.id
                                        } else {
                                            collapsedProviderIds + provider.id
                                        }
                                        collapsedProviderIds = updated
                                        collapseStore.save(updated)
                                    }
                                )
                            }
                            if (expanded) {
                                item(key = "card_${provider.id}") {
                                    SettingsGroup {
                                        filteredModels.forEachIndexed { index, model ->
                                            if (index > 0) {
                                                SettingsDivider()
                                            }
                                            ModelSelectionRow(
                                                model = model,
                                                selected = provider.id == currentProviderId && model == currentModel,
                                                metadata = modelMetadata[modelMetadataKey(provider.id, model)],
                                                onClick = { onSelect(provider.id, model) }
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
    }
}

/**
 * 投影版的 [com.tom.rv2ide.artificial.agent.compose.compat.ModelMetadata] 的键：
 * Aharou 的 `modelMetadataKey(providerId, model)` = `"$providerId:$model"`，照搬。
 */
fun modelMetadataKey(providerId: String, model: String): String = "$providerId:$model"

@Composable
private fun ModelSelectionRow(
    model: String,
    selected: Boolean,
    metadata: ModelMetadata?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            ModelMetadataTags(metadata)
        }
        if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelMetadataTags(metadata: ModelMetadata?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (metadata != null) {
            if (metadata.supportsVision) {
                ModelTag(text = "Image", isHighlight = true)
            }
            if (metadata.supportsTools) {
                ModelTag(text = "Tools")
            }
            // ACS 投影：投影 ModelMetadata 只有 inputTokens/outputTokens 两个 token 字段
            // （Aharou 还有 contextTokens 兜底，未纳入投影），此处相应简化。
            val input = metadata.inputTokens?.takeIf { it > 0 }
            if (input != null) {
                ModelTag(text = formatTokenLimit(input), icon = FeatherIcons.ArrowUp)
            }
            metadata.outputTokens?.takeIf { it > 0 }?.let { output ->
                ModelTag(text = formatTokenLimit(output), icon = FeatherIcons.ArrowDown)
            }
        }
    }
}

@Composable
private fun ModelTag(
    text: String,
    isHighlight: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val backgroundColor = if (isHighlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isHighlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = textColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = textColor
            )
        }
    }
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")
