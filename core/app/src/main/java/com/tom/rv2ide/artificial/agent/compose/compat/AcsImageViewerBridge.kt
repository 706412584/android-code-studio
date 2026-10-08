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

package com.tom.rv2ide.artificial.agent.compose.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.tom.rv2ide.artificial.agent.ToolResultImageSupport
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [LocalImageViewer] 的 ACS 接线：把「看大图」转给 ACS **已有**的查看器。
 *
 * <p><b>为什么不把 Aharou 的 `ImageViewerHost` / `ZoomableImage` 搬过来</b>：那是重复实现。
 * ACS 已有 `ToolResultImageSupport.showLightbox(context, bytes, cacheKey)` —— 全屏 Dialog、
 * 双指缩放、放大后拖动、点任意处关闭，能力与 Aharou 那个查看器重合。再搬一份进来，
 * 两套手势代码会在同一屏里各修各的 bug。这是「复用优先于重写」的直接应用。
 *
 * <p><b>为什么是可选包一层而不是默认自动接线</b>：查看器要拿 Context 与协程作用域，
 * 还得有个东西负责把 [ImageSource] 读成字节（可能要 IO）。这些都属于接入层的决定，
 * 不该塞进 `LocalImageViewer` 的默认值里 —— 默认值保持空实现，谁渲染聊天面板谁决定包不包。
 *
 * @param fileAccess 解析 [ImageSource.ContainerPath] 用；不传则容器路径直接跳过
 *   （附件只要带 localPath 或内联 base64 就仍能看到图）。
 */
@Composable
fun ProvideAcsImageViewer(
    fileAccess: FileAccessProvider? = null,
    content: @Composable () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val viewer = remember(context, fileAccess) {
    ImageViewer { request ->
      scope.launch {
        // 解码是几 MB 级的 CPU 活，放 IO；showLightbox 必须回主线程跑（它会 show Dialog 并挂手势）
        val loaded = withContext(Dispatchers.IO) { loadFirstAvailable(request.sources, fileAccess) }
        if (loaded != null) {
          ToolResultImageSupport.showLightbox(context, loaded.bytes, loaded.cacheKey)
        }
      }
    }
  }
  CompositionLocalProvider(LocalImageViewer provides viewer, content = content)
}

/** 已解析出的图片字节 + 它的缓存键。 */
private class LoadedImage(val bytes: ByteArray, val cacheKey: String)

/**
 * 按顺序尝试每个来源，返回第一个拿得到的。
 *
 * <p>逐个 `try` 而不是「全失败就抛」：来源列表本来就是「本地临时文件 → 容器路径 → 内联 base64」
 * 的降级链（Aharou 的设计意图），前一个拿不到是常态，不该打断整条链。
 */
private fun loadFirstAvailable(
    sources: List<ImageSource>,
    fileAccess: FileAccessProvider?
): LoadedImage? {
  for (source in sources) {
    val loaded = load(source, fileAccess)
    if (loaded != null) return loaded
  }
  return null
}

private fun load(source: ImageSource, fileAccess: FileAccessProvider?): LoadedImage? =
    runCatching {
          when (source) {
            is ImageSource.LocalFile -> fromFile(File(source.path), source.path)
            is ImageSource.ContainerPath ->
                fileAccess?.copyToLocal(source.path)?.let { fromFile(it, source.path) }
            is ImageSource.Base64 -> {
              val bytes = Base64.getDecoder().decode(source.data)
              LoadedImage(bytes, ToolResultImageSupport.cacheKey(source.data))
            }
          }
        }
        .getOrNull()

/**
 * 从本地文件读字节。
 *
 * <p>缓存键用「原始来源路径 + 字节数」而不是内容摘要：查看器只按它区分「是不是同一张图」，
 * 而路径+长度已经足够区分；算内容摘要要遍历几 MB 数据，不值当。
 */
private fun fromFile(file: File, sourcePath: String): LoadedImage? {
  if (!file.isFile) return null
  val bytes = file.readBytes()
  return LoadedImage(bytes, "$sourcePath:${bytes.size}")
}
