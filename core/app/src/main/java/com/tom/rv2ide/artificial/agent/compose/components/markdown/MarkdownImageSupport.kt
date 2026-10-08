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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.tom.rv2ide.artificial.agent.compose.compat.FileAccessProvider
import com.tom.rv2ide.artificial.agent.compose.compat.FileLogger
import com.tom.rv2ide.artificial.agent.compose.compat.LocalImageViewer
import com.tom.rv2ide.artificial.agent.compose.compat.MARKDOWN_MAX_EDGE
import com.tom.rv2ide.artificial.agent.compose.compat.decodeSampledBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.tom.rv2ide.resources.R

/**
 * 图片预览按钮的无障碍文案。
 *
 * TODO(strings): Aharou 取 `R.string.common_image_preview`。本次移植不新增 ACS 字符串资源
 * （避免为一个文案动 `strings.xml`），先用常量；接线时替换为 ACS 既有或新增的资源。
 */
// 无障碍标签（读屏用）；走资源以便随系统语言切换
private const val IMAGE_PREVIEW_LABEL = "图片预览"

/**
 * Markdown 图片 transformer 的 CompositionLocal。
 * 默认空实现（返回 null）不渲染图片节点，与引入本功能前的行为一致。
 */
internal val LocalMarkdownImageTransformer = staticCompositionLocalOf<ImageTransformer> { NoOpImageTransformer }

private object NoOpImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = null
}

/**
 * 只加载本地图片的 markdown 图片渲染器。
 *
 * 支持 AI 容器视角的本地路径（`~/workspace/...`、容器绝对路径、相对路径）与 `file://`，
 * 经 [FileAccessProvider.copyToLocal] 落到宿主文件（远程模式自动下载到临时文件）；
 * 网络协议（http/https/data/content 等）一律不渲染。
 *
 * 正文里按 [MARKDOWN_MAX_EDGE] 采样、限高等比缩放（GIF 只显示首帧）；点一下拉起全屏查看器
 * 看原图，那边会按屏幕尺寸重新解码一份更清晰的。
 *
 * > 0.35.0 适配：Aharou 用 0.41.0 的 `ImageTransformer.placeholderConfig(link, ...)` 按链接给占位尺寸。
 * > 0.35.0 该回调签名是 `placeholderConfig(density, containerSize, imageSize)` —— **没有 link 参数**，
 * > 无法按链接区分，因此本类不重写它，交给库默认实现；本地图片的真实尺寸在 [transform] 返回后
 * > 由 `ImageData.painter.intrinsicSize` 给出，占位只在解码期间存在，与 0.41.0 行为等价。
 */
internal class MarkdownImageTransformer(
    private val fileAccess: FileAccessProvider
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        if (!isLocalPath(link)) return null
        val painter by produceState<Painter?>(initialValue = null, key1 = link) {
            value = withContext(Dispatchers.IO) { decode(link) }
        }
        val p = painter ?: return null
        val viewer = LocalImageViewer.current
        val clickLabel = stringResource(R.string.compose_markdown_image_preview)
        // remember 住 modifier：ImageData 是 data class，每帧换一个新 Modifier 实例会让它的
        // equals 恒不成立，白白触发下游重组。
        val modifier = remember(link, viewer, clickLabel) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = MAX_HEIGHT_DP)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = clickLabel) { viewer.show(markdownViewerRequest(link)) }
        }
        return ImageData(painter = p, modifier = modifier, contentScale = ContentScale.Fit)
    }

    private fun decode(url: String): Painter? {
        return try {
            val file = fileAccess.copyToLocal(url)
            if (!file.isFile || file.length() <= 0L) return null
            decodeSampledBitmap(file.absolutePath, MARKDOWN_MAX_EDGE)?.let { BitmapPainter(it) }
        } catch (e: Exception) {
            FileLogger.w(TAG, "markdown 本地图片解码失败: $url", e)
            null
        }
    }

    private fun isLocalPath(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.startsWith("file://")) return true
        // 带 :// 的其它协议（http/https/data/content/ftp...）一律视为非本地
        return !u.contains("://")
    }

    private companion object {
        const val TAG = "MarkdownImage"
        /** 显示最大高度（dp），超长图等比缩放。 */
        val MAX_HEIGHT_DP = 320.dp
    }
}
