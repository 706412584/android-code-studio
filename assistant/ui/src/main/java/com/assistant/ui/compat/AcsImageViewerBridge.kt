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

package com.assistant.ui.compat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.X
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [LocalImageViewer] 的接线：把「看大图」实现成本模块内的 Compose 全屏查看器。
 *
 * <p><b>为什么不再复用 ACS 的灯箱</b>：ACS 那个实现是 `Dialog` + XML 布局
 * （`dialog_tool_image_gallery` + `R.id.lightbox*`），属于本次剥离要去掉的视图层。
 * 剥掉 XML 之后它无法编译，而把它连同布局一起搬进来，等于为了看一张图
 * 重新引入 `com.tom.rv2ide.R` 与整套 `ScaleGestureDetector` 代码。
 *
 * <p>这里的实现用 Compose 的 `detectTransformGestures` + `graphicsLayer`：
 * 双指缩放、拖动、点背景关闭、左右切换。能力与 ACS 那个灯箱对齐，
 * 但没有 XML、没有 `R.id`、也没有第三方手势库。
 *
 * @param fileAccess 解析 [ImageSource.ContainerPath] 用；不传则容器路径直接跳过
 *   （附件只要带 localPath 或内联 base64 就仍能看到图）。
 */
@Composable
fun ProvideAcsImageViewer(
    fileAccess: FileAccessProvider? = null,
    content: @Composable () -> Unit
) {
  val scope = rememberCoroutineScope()
  // 当前打开的图集；null 表示没有打开。存字节而不是 base64：解码一次就够，
  // 且 ByteArray 可以直接交给 Coil 做模型（base64 字符串会被当成路径解析而失败）。
  var gallery by remember { mutableStateOf<List<LoadedImage>?>(null) }
  var galleryStart by remember { mutableStateOf(0) }

  val viewer = remember(fileAccess) {
    object : ImageViewer {
      override fun show(request: ImageViewerRequest) {
        scope.launch {
          // 解码是几 MB 级的 CPU 活，放 IO。
          val loaded = withContext(Dispatchers.IO) { loadFirstAvailable(request.sources, fileAccess) }
          if (loaded != null) {
            galleryStart = 0
            gallery = listOf(loaded)
          }
        }
      }

      /**
       * 图集：先把**全部**来源解析成字节，再一次性交给查看器。
       *
       * <p>逐张解析而不是懒加载：多图场景最多 4 张（发送侧上限），一次解完的内存代价可接受，
       * 换来的是切换即时。解析失败的图**从图集里剔除**而不是留一个空位——
       * 留下空位会让计数器显示「3 / 5」而实际只能翻到 4 张，用户会以为卡住了。
       */
      override fun showGallery(requests: List<ImageViewerRequest>, startIndex: Int) {
        if (requests.isEmpty()) {
          return
        }
        val target = startIndex.coerceIn(0, requests.lastIndex)
        scope.launch {
          val loaded =
              withContext(Dispatchers.IO) {
                val items = ArrayList<LoadedImage>(requests.size)
                // 记住目标那张在过滤后的下标：前面若有解析失败的图，下标会平移。
                var adjusted = 0
                requests.forEachIndexed { index, request ->
                  val image = loadFirstAvailable(request.sources, fileAccess)
                  if (image != null) {
                    if (index <= target) adjusted = items.size
                    items.add(image)
                  }
                }
                items to adjusted
              }
          val (items, index) = loaded
          if (items.isNotEmpty()) {
            galleryStart = index
            gallery = items
          }
        }
      }
    }
  }

  CompositionLocalProvider(LocalImageViewer provides viewer) {
    content()
    gallery?.let { images ->
      ImageLightbox(
          images = images,
          initialIndex = galleryStart,
          onDismiss = { gallery = null },
      )
    }
  }
}

/** 已解析出的图片字节 + 它的缓存键。 */
private class LoadedImage(val bytes: ByteArray, val cacheKey: String)

/**
 * 全屏看图：双指缩放、拖动、左右切换、点背景/关闭按钮退出。
 *
 * <p>切换图片时**重置缩放**：不重置的话，在第一张上放大到 4x 再切到第二张，
 * 第二张会以同样的倍率出现在屏幕外——用户看到一片黑，以为图没加载出来。
 */
@Composable
private fun ImageLightbox(
    images: List<LoadedImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
  var current by remember(images) { mutableStateOf(initialIndex.coerceIn(0, images.lastIndex)) }
  var scale by remember { mutableFloatStateOf(1f) }
  var offsetX by remember { mutableFloatStateOf(0f) }
  var offsetY by remember { mutableFloatStateOf(0f) }

  fun resetTransform() {
    scale = 1f
    offsetX = 0f
    offsetY = 0f
  }

  Dialog(
      onDismissRequest = onDismiss,
      properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                // 半透明黑而不是主题色：看图时周围应该是「暗房」，
                // 浅色主题下用 surface 会让图片显得发灰、对比度不足。
                .background(Color(0xE6000000))
                // 点背景关闭：只认「没落在图片上」的点击。图片自己会消费事件
                // （下面的 pointerInput），否则点图想放大时会误关。
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
      AsyncImage(
          model = images[current].bytes,
          contentDescription = null,
          contentScale = ContentScale.Fit,
          modifier =
              Modifier.fillMaxSize()
                  .padding(16.dp)
                  .graphicsLayer(
                      scaleX = scale,
                      scaleY = scale,
                      translationX = offsetX,
                      translationY = offsetY,
                  )
                  .pointerInput(current) {
                    detectTransformGestures { _, pan, zoom, _ ->
                      // 上限 8x：再放大只会看到像素块。下限 1x：整图可见。
                      scale = (scale * zoom).coerceIn(1f, 8f)
                      if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                      } else {
                        offsetX = 0f
                        offsetY = 0f
                      }
                    }
                  },
      )

      // 只有一张时不显示切换控件：一个永远点不动的箭头比没有更让人困惑。
      if (images.size > 1) {
        IconButton(
            onClick = {
              current = (current - 1 + images.size) % images.size
              resetTransform()
            },
            modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
        ) {
          Icon(FeatherIcons.ChevronLeft, contentDescription = null, tint = Color.White)
        }
        IconButton(
            onClick = {
              current = (current + 1) % images.size
              resetTransform()
            },
            modifier = Modifier.align(Alignment.CenterEnd).size(48.dp),
        ) {
          Icon(FeatherIcons.ChevronRight, contentDescription = null, tint = Color.White)
        }
        Text(
            text = "${current + 1} / ${images.size}",
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        )
      }

      // 关闭入口：**必须有一个看得见的**。全屏黑底上没有任何可点的提示，
      // 用户会以为卡住了（ACS 侧真机反馈正是「没有关闭按钮」）。
      IconButton(
          onClick = onDismiss,
          modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp),
      ) {
        Icon(FeatherIcons.X, contentDescription = null, tint = Color.White)
      }
    }
  }
}

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
              LoadedImage(bytes, ToolImageSupport.cacheKey(source.data))
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
