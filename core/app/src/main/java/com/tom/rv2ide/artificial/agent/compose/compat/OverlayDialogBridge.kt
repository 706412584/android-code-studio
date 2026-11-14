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

import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView

/**
 * 在**应用外悬浮窗**里承载 Compose 弹窗内容的宿主能力。
 *
 * <p><b>为什么不能直接用 Compose 的 `Dialog` / `ModalBottomSheet`</b>：它们内部都在
 * `DisposableEffect` 里调 `Dialog.show()`，而 `Dialog` 默认 window type 是
 * `TYPE_APPLICATION`（需要 Activity token）。悬浮窗宿主是 `Service`，没有 token，
 * 于是必然抛：
 *
 * ```
 * WindowManager$BadTokenException: Unable to add window -- token null is not valid
 *   at android.app.Dialog.show(Dialog.java:344)
 *   at androidx.compose.ui.window.AndroidDialog_androidKt$Dialog$1$1(...)
 * ```
 *
 * <p><b>为什么不能在 Compose 层「事后改 type」</b>：试过在 Dialog 内容里放
 * `SideEffect { window.setType(TYPE_APPLICATION_OVERLAY) }`——太晚了，
 * `show()` 已经用旧 type 请求过 token 并失败（黑鲨实测仍然崩，堆栈同上）。
 * 必须在 **`show()` 之前**设置，只有创建 Dialog 的那一方能做到。
 *
 * <p>因此：宿主（[com.tom.rv2ide.artificial.agent.host.OverlayHost]）提供本接口，
 * 它创建**原生** `Dialog`、先 `setType` 再 `show()`，内容用 [ComposeView] 承载。
 * Activity 宿主下本 CompositionLocal 为 null，各处仍走普通 Compose Dialog。
 */
fun interface OverlayDialogHost {
  /**
   * 显示一个悬浮层弹窗。
   *
   * @param onDismissRequest 遮罩/返回键关闭时回调
   * @param content 弹窗内容（已带主题；宿主只需把它塞进 ComposeView）
   * @return 关闭用的句柄；调用 [OverlayDialogHandle.dismiss] 可从 Compose 侧主动关闭
   */
  fun showDialog(
      onDismissRequest: () -> Unit,
      content: @Composable () -> Unit,
  ): OverlayDialogHandle
}

/** 一个已显示的悬浮层弹窗。 */
interface OverlayDialogHandle {
  fun dismiss()
}

/**
 * 当前宿主是否支持悬浮层弹窗；null = 普通 Activity 宿主，走 Compose Dialog。
 *
 * <p>用 `staticCompositionLocalOf`：这个值在整个 Compose 树里恒定（由宿主决定，
 * 不会中途变化），静态版本省去每次重组时的读跟踪开销。
 */
val LocalOverlayDialogHost = staticCompositionLocalOf<OverlayDialogHost?> { null }

/**
 * 当前 Compose 树是否跑在**应用外悬浮窗**（`TYPE_APPLICATION_OVERLAY` 窗口）里。
 *
 * <p>与 [LocalOverlayDialogHost] 的区别：这个只回答「是不是 overlay 宿主」，
 * 不需要弹窗能力；用于选择布局分支（例如 sheet 在 overlay 下贴底、在 Activity 下居中）。
 *
 * <p>默认 `false`（普通 Activity 宿主），行为与改动前一致。
 */
val LocalIsOverlayWindow = staticCompositionLocalOf { false }

/**
 * 悬浮窗里的「模态容器」：宿主支持时用原生 overlay Dialog，否则用 Compose `Dialog`。
 *
 * <p>这是三处弹窗（模型切换、思考强度、上传附件）以及其余 `AdaptiveModalBottomSheet`
 * 宿主的统一入口——**只改这一处**，所有调用点自动获得 overlay 兼容。
 *
 * <p>内容布局由调用方决定（贴底面板 / 居中卡片），本函数只管「窗口怎么建」。
 */
@Composable
fun OverlayAwareDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
  val host = LocalOverlayDialogHost.current
  if (host != null) {
    // 悬浮窗：交给宿主建原生 Dialog（它会在 show() 前设好 TYPE_APPLICATION_OVERLAY）。
    DisposableEffect(host) {
      val handle = host.showDialog(onDismissRequest, content)
      onDispose { handle.dismiss() }
    }
  } else {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) {
      content()
    }
  }
}

/**
 * 创建一个已设置悬浮层窗口类型的原生 Dialog。
 *
 * <p>供宿主实现 [OverlayDialogHost] 时复用；顺序严格遵守
 * **先 setType、后 show**（见 [applyOverlayWindowType]）。
 */
fun createOverlayDialog(
    context: Context,
    onDismiss: () -> Unit,
    /**
     * 给 Dialog 内的 ComposeView 补 ViewTree owner。
     *
     * <p>`ComposeView` 附加到窗口时沿视图树向上找 `ViewTreeLifecycleOwner` 来创建
     * Recomposer；Dialog 窗口没有 Activity，不挂就崩（实测
     * `IllegalStateException: ViewTreeLifecycleOwner not found`）。复用既有的
     * `OverlayViewTreeOwners.install`（它已为悬浮窗面板做过同样的事）。
     */
    installViewTreeOwners: (android.view.View) -> Unit,
    /**
     * 弹窗窗口要覆盖的区域（屏幕坐标：left, top, width, height）。
     *
     * <p><b>为什么需要它</b>：不设时 Dialog 窗口铺满整屏，弹窗看起来像「切到了另一个
     * 界面」而不是面板内的一层浮层（用户反馈）。把窗口限制到底下悬浮面板所占的矩形，
     * 遮罩与贴底卡片就只出现在面板范围内。
     */
    bounds: android.graphics.Rect,
    content: @Composable () -> Unit,
): Dialog {
  val dialog = Dialog(context, com.tom.rv2ide.R.style.Theme_AndroidIDE)
  // **先设 type 再 show**（见 applyOverlayWindowType 的说明）。
  applyOverlayWindowType(dialog.window)
  // 位置与尺寸：必须在 show() 之前写进 window attributes，show() 之后再改会闪一下。
  dialog.window?.apply {
    setLayout(bounds.width(), bounds.height())
    val attrs = attributes
    attrs.gravity = android.view.Gravity.TOP or android.view.Gravity.START
    attrs.x = bounds.left
    attrs.y = bounds.top
    attributes = attrs
  }
  val composeView = ComposeView(context).apply { setContent(content) }
  installViewTreeOwners(composeView)
  dialog.setContentView(
      composeView,
      ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  dialog.setOnDismissListener { onDismiss() }
  return dialog
}

/**
 * 把 Dialog 窗口挪到系统悬浮层。
 *
 * <p>**必须在 `show()` 之前调用**：`show()` 会用当时的 type 请求 window token，
 * 之后改 type 不会重新请求（见 [OverlayDialogHost] 的说明）。
 */
fun applyOverlayWindowType(window: android.view.Window?) {
  window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
}