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

package com.tom.rv2ide.artificial.agent.compose.components.markdown

import androidx.compose.ui.graphics.ImageBitmap
import com.tom.rv2ide.artificial.agent.compose.compat.FileAccessProvider
import com.tom.rv2ide.artificial.agent.compose.compat.FileLogger
import com.tom.rv2ide.artificial.agent.compose.compat.ImageSource
import com.tom.rv2ide.artificial.agent.compose.compat.ImageViewerRequest
import com.tom.rv2ide.artificial.agent.compose.compat.decodeSampledBitmap
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ChatImageLoader"

/**
 * 聊天区图片加载器：把 [ImageSource] 落成位图交给全屏查看器。全项目只有这一份解码策略。
 *
 * [ImageSource.ContainerPath] 经 [FileAccessProvider.copyToLocal] 落到宿主文件 —— 本地 PRoot 模式
 * 只是路径映射、不产生拷贝；远程 SSH 模式会把文件 base64 拉回来写进临时文件。
 */
internal fun chatImageLoader(
    fileAccess: FileAccessProvider,
    maxEdge: Int,
    maxPixels: Long
): suspend (ImageSource) -> ImageBitmap? = { source ->
    withContext(Dispatchers.IO) {
        try {
            when (source) {
                is ImageSource.LocalFile ->
                    decodeExistingFile(File(source.path), maxEdge, maxPixels)

                is ImageSource.ContainerPath ->
                    decodeExistingFile(fileAccess.copyToLocal(source.path), maxEdge, maxPixels)

                is ImageSource.Base64 ->
                    decodeSampledBitmap(Base64.getDecoder().decode(source.data), maxEdge, maxPixels)
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "图片加载失败: $source", e)
            null
        }
    }
}

private fun decodeExistingFile(file: File, maxEdge: Int, maxPixels: Long): ImageBitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return decodeSampledBitmap(file.absolutePath, maxEdge, maxPixels)
}

/*
 * TODO(port): Aharou 本文件另有一个 `PendingUploadAttachment.toViewerRequest()`。
 *
 * 它依赖 `com.aharou.feature.agent.presentation.component.PendingUploadAttachment`
 * —— 那是**聊天输入栏的待发送草稿模型**（字段：fileName / containerPath / localPath /
 * mimeType / sizeBytes / image: AgentImage?），定义在 Aharou 的 `ChatAttachmentUtils.kt`，
 * 既不属于本任务的 4 个交付文件，ACS 侧也尚无对应类型（输入栏由接线任务负责）。
 *
 * 为保证「不为编译而静默删功能」，此处显式留档其行为，待 ACS 的待发送附件模型落地后原样补回：
 *
 *     localPath 优先、base64 兜底（image?.base64Data）：
 *       sources = listOfNotNull(
 *           localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
 *           image?.base64Data.takeIf { it.isNotBlank() }?.let { ImageSource.Base64(it) }
 *       ), title = fileName
 *
 * 之所以 base64 只作兜底：decodeByteArray 要完整 byte[] 常驻，加上本来就压在内存里的 base64
 * 字符串（UTF-16 实占两倍），一张 5MB 的图瞬时要多吃十几 MB；从磁盘解码没这份开销。
 */

/**
 * Markdown 内嵌图片的查看请求：link 就是 AI 视角的容器路径。
 *
 * 库把 alt 直接交给 Image 当 contentDescription，不经过 transformer，所以这里拿不到它，
 * 退而取路径末段的文件名当标题。
 */
internal fun markdownViewerRequest(link: String): ImageViewerRequest = ImageViewerRequest(
    sources = listOf(ImageSource.ContainerPath(link)),
    title = link.trimEnd('/').substringAfterLast('/')
)
