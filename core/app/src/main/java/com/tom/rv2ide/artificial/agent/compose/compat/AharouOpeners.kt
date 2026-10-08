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
 * <p>**为什么决定不接**（2026-10-09 复核，先前这里的理由有误，已更正）：
 * - 先前说「`core/app` 对 termux 零引用、也没有模块依赖」——**不成立**：
 *   `core/app/build.gradle.kts` 已依赖 `projects.termux.application`（以及 view/emulator/shared）。
 * - 真实障碍是**语义与签名**：这个回调是 `(String) -> Unit` 的即发即忘，意思是「把这条命令
 *   扔进终端让用户看着跑」。而 ACS 里 `TermuxShellBackend.execute()` 已经会 bindService 拿
 *   `TermuxService`、用 `createTermuxTask` 跑命令并**回收结果**——那是 agent 工具链的执行语义
 *   （同步、有输出、有超时），不是「开个可见终端给用户看」。
 * - 于是有两条路，都不理想：① 用 `TermuxShellBackend` 实现它 → 用户点「在终端中运行」后
 *   什么界面都不出现（后台跑完即弃），入口看起来是坏的；② 起 `TerminalActivity` 并传命令 →
 *   需要给 `TerminalActivity` 加一个「启动时执行该命令」的 extra，那是改宿主行为，
 *   属独立功能而非移植收尾。
 * - 结论：**保持 null，入口隐藏**。若要做，按 ② 单独提一个改动，并想清楚会话命名与
 *   失败时用户怎么看到错误。
 */
val LocalTerminalOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * 打开工具详情页（整屏看指令与结果）。null = 未接线，工具行上的入口会隐藏。
 *
 * <p>**为什么决定不接**（2026-10-09 复核）：Aharou 由聊天页在导航层提供，要一个能承载整屏
 * Compose 页面的导航位。ACS 没有这个导航位；更关键的是**当前没有调用点**——
 * `ToolMessageBody` 已按「无宿主即不显示」移除了工具行上的详情入口（见其实现），
 * 所以即便现在给一个实现也不会有人用。要接应先恢复入口，属独立功能。
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
 *
 * <p><b>已被面板接入层调用</b>：`AssistantComposePanel` 在组件树外层包了本函数
 * （与 [ProvideAcsImageViewer] 一起），所以浏览器入口是**活的**，不是「提供了没人用」。
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
