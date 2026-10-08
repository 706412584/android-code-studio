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

import com.tom.rv2ide.artificial.agent.compose.model.AgentAttachment
import com.tom.rv2ide.artificial.agent.compose.compat.AgentImage
import java.util.UUID

/**
 * AI 忙时排队待发的请求（Aharou `AgentUiModels.kt` 同名类型照搬）。
 *
 * <p>`QueuedRequestPanel`（同目录 ChatInputAttachments.kt）按本类型的 id 做拖拽排序 key，
 * 字段面与 Aharou 完全一致；`inputImages` / `inputAttachments` 在 ACS 侧由输入栏接线时填充。
 */
data class QueuedRequest(
    val id: String,
    val request: String,
    val modelRequest: String = request,
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val inputImages: List<AgentImage> = emptyList(),
    val inputAttachments: List<AgentAttachment> = emptyList(),
    val isAutoTrigger: Boolean = false
)

/**
 * 配置变更确认弹窗的一行（Aharou `core/config/confirm/PendingConfigChange.kt` 照搬）。
 * 一次 CLI 调用可携带多条变更（set-batch），在同一弹窗里作为整体确认/驳回。
 */
data class PendingConfigChangeItem(
    val id: String = UUID.randomUUID().toString(),
    /** 行内标签，来自字段的 [displayName]。 */
    val displayName: String,
    /** 点分路径；展示在细字说明里。 */
    val path: String,
    /** 旧值（人读格式）。新增集合行时为空。 */
    val oldDisplay: String,
    /** 新值（人读格式）。删除行时为空。 */
    val newDisplay: String,
    /** 行内动词（set / append / remove / add / hide / revert）。 */
    val verb: String,
    val risk: ConfigRisk,
    /** 逐行驳回开关，默认 true（允许）。 */
    val isApproved: Boolean = true,
)

/** 一条等待用户确认的 CLI 请求。 */
data class PendingConfigChange(
    val id: String = UUID.randomUUID().toString(),
    val items: List<PendingConfigChangeItem>,
    val caption: String?,
)

/** 门禁上报给桥接层的最终处置。 */
sealed class ConfirmOutcome {
    /** 用户批准（可能带逐行开关后的）条目集。 */
    data class Approved(val items: List<PendingConfigChangeItem>) : ConfirmOutcome()
    /** 用户取消。 */
    object Rejected : ConfirmOutcome()
    /** 门禁超时窗口内无用户响应。 */
    object TimedOut : ConfirmOutcome()
}

/** 变更风险档位（Aharou `core/config/ConfigSchema.kt` 同名枚举照搬）。 */
enum class ConfigRisk { NORMAL, SENSITIVE, DESTRUCTIVE }
