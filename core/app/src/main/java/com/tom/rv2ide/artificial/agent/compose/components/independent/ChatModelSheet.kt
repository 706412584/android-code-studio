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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.artificial.agent.compose.components.tools.AdaptiveModalBottomSheet
import com.tom.rv2ide.artificial.agent.compose.compat.ModelMetadata
import com.tom.rv2ide.artificial.agent.compose.compat.ReasoningEffort
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Zap

/**
 * 输入区下行的模型切换图标按钮
 */
@Composable
internal fun ModelIconButton(
    provider: ProviderSelectionTarget?,
    providers: List<ProviderSelectionTarget>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    forceOpenSheet: Boolean = false,
    onSelectSuccessInOnboarding: (() -> Unit)? = null,
    onSheetDismiss: (() -> Unit)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
        ModelLogoIcon(modelName = provider?.effectiveModel.orEmpty(), size = 20.dp)
    }

    if (effectiveShowSheet) {
        ModelSheet(
            providers = providers,
            currentProviderId = provider?.id ?: "",
            currentModel = provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            onSelect = { pId, model ->
                onSelectModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onDismiss = {
                showSheet = false
                onSheetDismiss?.invoke()
            }
        )
    }
}

/**
 * 思考强度选择器：独立图标按钮，点击弹出底部档位选择。
 * [availableEfforts] 由调用方按当前模型元数据生成，元数据未命中时给出全部档位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortSelector(
    effort: ReasoningEffort,
    availableEfforts: List<ReasoningEffort>,
    onChange: (ReasoningEffort) -> Unit,
    enabled: Boolean
) {
    var showSheet by remember { mutableStateOf(false) }
    Box {
        // 图标旁直接带档位文字：档位不同耗时与花费差得多，只给图标看不出当前选了哪档。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(enabled = enabled) { showSheet = true }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            Icon(
                FeatherIcons.Zap,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(effort.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1
            )
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState()
        AdaptiveModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl)
            ) {
                Text(
                    text = stringResource(R.string.chat_reasoning_effort),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
                availableEfforts.forEach { e ->
                    val selected = e == effort
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable {
                                showSheet = false
                                onChange(e)
                            }
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            FeatherIcons.Zap,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            text = stringResource(e.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected) {
                            Icon(
                                FeatherIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.DEFAULT -> R.string.chat_reasoning_effort_default
    ReasoningEffort.NONE -> R.string.chat_reasoning_effort_none
    ReasoningEffort.LOW -> R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> R.string.chat_reasoning_effort_max
}

/**
 * 模型选择弹窗：复用设置页默认模型的 [ModelSelectionSheet]（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 */
@Composable
internal fun ModelSheet(
    providers: List<ProviderSelectionTarget>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    ModelSelectionSheet(
        title = stringResource(R.string.common_model),
        noModelsText = stringResource(R.string.chat_no_models_hint),
        providers = providers,
        currentProviderId = currentProviderId,
        currentModel = currentModel,
        modelMetadata = modelMetadata,
        onSelect = onSelect,
        onClear = null,
        onDismiss = onDismiss
    )
}
