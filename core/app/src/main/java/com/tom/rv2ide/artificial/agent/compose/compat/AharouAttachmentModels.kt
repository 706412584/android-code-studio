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

package com.tom.rv2ide.artificial.agent.compose.compat

import com.tom.rv2ide.artificial.agent.compose.model.AgentAttachment

/**
 * 聊天输入栏的待发送附件模型（Aharou `ChatAttachmentUtils.kt` 内私有类型的提升）。
 *
 * <p><b>为什么独立成文件</b>：Aharou 把 [PendingUploadAttachment] / [UploadedWorkspaceFile]
 * 定义在 `ChatAttachmentUtils.kt` 内部，第二批移植的三个文件（ChatInputAttachments /
 * ShareIntakeDialog / ChatAttachmentUtils 本体）都引用它们。留在任一移植文件里都会造成
 * 跨文件依赖倒挂——与 compat 收敛 [ExpandableChevronIcon] 时踩过的坑同型。
 *
 * <p><b>两个字段的语义差</b>：`UploadedWorkspaceFile` 是「URI 已复制进工作区」的落盘结果；
 * `PendingUploadAttachment` 是「等待随下一条消息发出」的草稿条目。Aharou 里两者字段完全同形，
 * 且 `toPendingAttachment()` 只是逐字段复制——保留两个名字是为了让移植代码不改调用点，
 * 也让「已落盘 / 待发送」两个阶段在类型上可区分。
 */
data class UploadedWorkspaceFile(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val image: AgentImage? = null,
)

data class PendingUploadAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val image: AgentImage? = null,
)

val PendingUploadAttachment.isImage: Boolean
  get() = image != null

fun UploadedWorkspaceFile.toPendingAttachment(): PendingUploadAttachment =
    PendingUploadAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        image = image,
    )

fun PendingUploadAttachment.toAgentAttachment(): AgentAttachment =
    AgentAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        isImage = isImage,
    )

fun List<PendingUploadAttachment>.toAgentAttachments(): List<AgentAttachment> = map { it.toAgentAttachment() }

fun List<PendingUploadAttachment>.toAgentImages(): List<AgentImage> = mapNotNull { it.image }

/** 附件目录的容器路径。ACS 接入时把 `~` 根指到 filesDir（见 [HostFileAccessProvider]）。 */
const val ATTACHMENTS_DIR_SUFFIX = "/.aharou/attachments"
