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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.tom.rv2ide.artificial.agent.compose.model.AgentAttachment

/**
 * 图片来源（源自 Aharou `core/ui/ImageViewer.kt`）。
 *
 * <p>刻意做成值类型而不是「每个调用点自带一个 suspend 加载 lambda」：值类型 equals 稳定，可以直接
 * 当 `produceState` 的 key；lambda 那种写法会把待发送附件最大 5MB 的 base64 字符串一直攥在手里，
 * 附件都发出去、内存里那份已经清空了，查看器还在替它续命。
 */
sealed interface ImageSource {
  /** 宿主本地文件绝对路径，可以直接解码。 */
  data class LocalFile(val path: String) : ImageSource

  /** AI 视角的容器 / 远程路径，需要先经 [FileAccessProvider] 落到宿主文件。 */
  data class ContainerPath(val path: String) : ImageSource

  /** 内存里的 base64 图片数据。 */
  data class Base64(val data: String) : ImageSource
}

/**
 * 一次查看请求。
 *
 * <p>[sources] 按顺序尝试，前一个拿不到就退到下一个 —— 远程 SSH 模式下已发送附件记的
 * `localPath` 是 `createTempFile` + `deleteOnExit` 的临时文件，进程重启或缓存被清掉后就没了，
 * 必须能退回容器路径重新拉一次。
 */
@Immutable
data class ImageViewerRequest(
    val sources: List<ImageSource>,
    /** 无障碍描述与失败提示用，通常是文件名；留空则回退到「图片预览」。 */
    val title: String = ""
)

/** 打开全屏查看器的句柄：调用方只管 show，不关心加载与手势。 */
@Stable
fun interface ImageViewer {
  fun show(request: ImageViewerRequest)
}

/**
 * 全屏查看器句柄的注入点。
 *
 * <p>默认是**空实现**：没挂实现时点图片静默无反应，不会崩。这与 Aharou 原版行为一致。
 * ACS 侧要真正能看到图，需在聊天面板外层包一层 [ProvideAcsImageViewer]
 * （它把请求转发给 ACS 已有的 `ToolResultImageSupport.showLightbox`，不重复造查看器）。
 *
 * <p>用 CompositionLocal 而不是一路加回调参数，否则 `AgentMessageItem` / 输入栏这些中间层
 * 都要为了转发一个 lambda 改签名。
 */
val LocalImageViewer = staticCompositionLocalOf<ImageViewer> { NoOpImageViewer }

/** 空实现单例：不要写成 `{ ImageViewer { } }`，那样每次读取默认值都会新建一个 SAM 对象。 */
private val NoOpImageViewer = ImageViewer { }

/** [LocalImageViewer] 的宿主状态：谁触发 [show]，谁负责把它挂进组合树。 */
@Stable
class ImageViewerState : ImageViewer {
  var request by mutableStateOf<ImageViewerRequest?>(null)
    private set

  override fun show(request: ImageViewerRequest) {
    this.request = request
  }

  fun dismiss() {
    request = null
  }
}

@Composable
fun rememberImageViewerState(): ImageViewerState = remember { ImageViewerState() }

/**
 * 已发送附件 → 查看请求（源自 Aharou `ChatImageLoader.kt`）。
 *
 * <p>两个来源都放进去而不是只挑一个：`localPath` 是临时文件，进程重启或缓存被清掉后就没了，
 * 那时靠 `containerPath` 重新拉一次（Aharou 的原始设计意图，见 [ImageViewerRequest.sources]）。
 *
 * <p>放在 compat 而不是附件组自己的包里：它的三个依赖（[AgentAttachment] / [ImageSource] /
 * [ImageViewerRequest]）都在这一层，而附件卡与助手气泡两边都要用它。
 */
fun AgentAttachment.toViewerRequest(): ImageViewerRequest =
    ImageViewerRequest(
        sources =
            listOfNotNull(
                localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
                containerPath.takeIf { it.isNotBlank() }?.let { ImageSource.ContainerPath(it) }),
        title = fileName)
