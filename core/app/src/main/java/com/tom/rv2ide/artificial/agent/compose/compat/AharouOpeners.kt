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

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Aharou 特有的三个「打开某处」注入点 + 工具详情面板信号。
 *
 * <p><b>默认全部为空（null）是刻意的，也是 Aharou 原版行为</b>：调用点都做了 null 判定
 * （`if (browserUrl != null && browserOpener != null)`），拿不到就**不显示该按钮**。
 * 所以「未接线」的表现是按钮缺席，而不是点了没反应的死按钮 —— 这比给一个假实现更诚实。
 *
 * <p>要真正接上，在外层包一层 [ProvideAcsToolOpeners]（只接浏览器）；
 * 终端与工具详情页**未接线**，理由见 [ProvideAcsToolOpeners]。
 */

/** 打开某个 URL 围观（Aharou：内置浏览器）。null = 未接线，调用点会隐藏入口。 */
val LocalBrowserOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * 「在终端中运行这条命令」。null = 未接线。
 *
 * <p>**为什么接不上**：ACS 的终端在 `termux/application` 里（包名 `com.termux.app`，
 * 有独立的 TermuxActivity/TermuxService），而 `core/app` 对它有**零引用**、也没有模块依赖。
 * 从 `compose/compat` 接线就得改 `build.gradle.kts` 加依赖 —— 那是本次移植明令禁止的动作。
 * 更关键的是这只是个 `(String) -> Unit` 的即发即忘回调，而 ACS 起终端会话需要 Activity
 * 生命周期与 TermuxService 绑定，塞不进这个签名。
 *
 * <p>ACS 里「跑一条 shell 命令」的正主是 agent 工具链的 shell 后端
 * （`ToolSettingsPort.getShellBackendId()` 返回 `"termux"`），不在渲染层。
 */
val LocalTerminalOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * 打开工具详情页（整屏看指令与结果）。null = 未接线，工具行上的入口会隐藏。
 *
 * <p>**为什么接不上**：Aharou 它由聊天页在导航层提供（要一个能承载整屏 Compose 页面的导航位）。
 * ACS 的聊天面板宿主还没建（属接入任务），此时给它一个假实现只会变成死按钮。
 *
 * <p><b>另一件必须知道的事</b>：Aharou 把这一行声明写在 `ToolCallPreview.kt` 里，
 * 而 `ToolMessageComponents.kt` 是同包裸用（无 import）。移植 ToolCallPreview.kt 时
 * **必须删掉它自己那份声明**并 import 本处，否则同包出现两个同名顶层属性，直接编译失败。
 */
val LocalToolPreviewOpener = staticCompositionLocalOf<((AgentUIMessage) -> Unit)?> { null }

/**
 * 「Aharou Computer」（工具详情面板）打开信号（源自 Aharou `ToolComputerSignal.kt`）。
 *
 * <p>点聊天里的工具行 → [request]；聊天宿主监听后掀开面板。
 * 值 = 最近一次请求的工具消息 id（null = 无请求）。
 *
 * <p>用 Flow 而不是直接回调，是因为「点工具行」与「面板宿主」之间隔着若干层组合，
 * 而 Aharou 的宿主本来就以 `collectAsStateWithLifecycle` 订阅。
 */
internal object ToolComputerSignal {
  private val _target = MutableStateFlow<String?>(null)
  val target: StateFlow<String?> = _target.asStateFlow()

  fun request(messageId: String) {
    _target.value = messageId
  }

  fun consume() {
    _target.value = null
  }
}

/**
 * ACS 侧的 opener 接线：目前**只接浏览器**。
 *
 * <p>浏览器的等价物是系统浏览器（`ACTION_VIEW`）：Aharou 有自己的内置浏览器页，
 * ACS 没有，而「在浏览器里看一眼这个 URL」用系统浏览器语义上完全成立 —— 只是会离开本应用。
 * 因此它是**可选接线**而不是默认行为，由面板宿主决定要不要包这一层。
 *
 * <p>终端与工具详情页不提供实现，保持 null（入口随之隐藏），理由见各自的注释。
 */
@Composable
fun ProvideAcsToolOpeners(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val browserOpener: (String) -> Unit = remember(context) { { url -> openInSystemBrowser(context, url) } }
  CompositionLocalProvider(LocalBrowserOpener provides browserOpener, content = content)
}

/**
 * 交给系统浏览器打开。
 *
 * <p>整段包在 `runCatching` 里：设备上没有任何浏览器（`ActivityNotFoundException`）时，
 * 用户点一下没反应是可以接受的降级，但**崩掉不是**。
 * `NEW_TASK` 是为「LocalContext 不是 Activity」的情形兜底（例如从 application context 调）。
 */
private fun openInSystemBrowser(context: Context, url: String) {
  runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }
}
