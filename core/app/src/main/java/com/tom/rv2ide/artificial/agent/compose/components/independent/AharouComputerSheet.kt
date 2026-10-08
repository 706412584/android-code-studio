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

/*
 * ── ACS 移植留档：缺失 / 裁剪的 Aharou 符号（其余符号均已有 ACS 对应物，见各 import）──
 *
 * 1. `com.aharou.feature.browser.BrowserTabPool` —— ACS 无浏览器池，browserPool 参数
 *    整体删除（含 ToolComputerPage / BrowserCard / BrowserLivePreview 的透传）。
 *    行为影响：browser 工具页不再有实时画面预览，BrowserLivePreview 永远显示占位文案
 *    （URL 与结果文本照常显示，与原版 pool == null 时的表现一致）。
 * 2. `vdCapture`（虚拟屏抓帧回调，Aharou 侧由 VdController 提供）—— ACS 无 VdController，
 *    参数签名保留、调用方恒传 null：VdCard 抓帧循环空转、永远显示占位框
 *    （与原版 capture == null 时的表现一致），未来接线无需再改本文件。
 * 3. `R.font.jetbrains_mono_nl` —— ACS 仅有 jetbrains_mono.ttf（同一 JetBrains Mono
 *    等宽字体族，无 NL 变体），映射为 `AppR.font.jetbrains_mono`。
 * 4. 本文件的界面文案（Aharou Computer / 执行中… / 失败 / 完成 / 参数 / 结果 等）在
 *    Aharou 原版即硬编码中文、未走字符串资源，按逐字移植原则原样保留；仅
 *    contentDescription 与影子屏占位走了字符串资源（common_close / common_copy /
 *    tool_action_open_terminal / tool_action_open_browser / vd_capturing / vd_not_running）。
 */

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.rv2ide.R as AppR
import com.tom.rv2ide.artificial.agent.compose.compat.LocalBrowserOpener
import com.tom.rv2ide.artificial.agent.compose.compat.LocalTerminalOpener
import com.tom.rv2ide.artificial.agent.compose.compat.SessionUseCase
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.resources.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import org.json.JSONObject

// ── 终端卡配色（与 原版 ChatToolDetailUI 的「黑色终端卡」对齐）──
private val ComputerCardBg = Color(0xFF141414)
private val ComputerCardBorder = Color(0xFF404040)
private val ComputerGreen = Color(0xFF34C759)
private val ComputerRed = Color(0xFFFF6666)
private val ComputerWhite = Color(0xFFE6E6E6)
private val ComputerBlue = Color(0xFF7CC4FF)

/** 等宽字体：与终端/浏览器一致（fusion 内置 JetBrains Mono）。 */
private val ComputerMono = FontFamily(Font(AppR.font.jetbrains_mono))

/**
 * 「Aharou Computer」工具详情面板（Aharou 移植版）。
 *
 * 对齐 原版 的 Aharou Computer（ToolDetailSheet）核心体验：
 *  - 点聊天里的工具行 → 掀开这个面板；
 *  - 上下滑动/翻页在会话的工具调用之间切换；
 *  - shell 命令 → 黑色终端卡（`$ 命令` + 输出，绿色等宽字）；
 *  - browser → 页面 URL + **实时快照**（每 2.5s 从浏览器池的活动标签抓帧）+ 结果文本；
 *  - 其余工具 → 参数 + 结果卡；
 *  - 右上角动作钮随工具类型变：终端预填 / 地球（打开浏览器）/ 复制结果。
 *
 * 裁剪说明（v1）：Minis 原版的「换模型重跑 / 文件提及 / 权限跳转」等周边未搬。
 *
 * [Aharou] ACS 裁剪：原版的 browserPool 参数（浏览器池抓帧）已删除，理由见文件头留档。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AharouComputerSheet(
    messages: List<AgentUIMessage>,
    initialMessageId: String,
    vdCapture: (suspend (Int) -> Bitmap?)? = null,
    onDismiss: () -> Unit,
) {
    val toolMessages = remember(messages) { messages.filter { it.toolName != null } }
    if (toolMessages.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val startIndex = toolMessages.indexOfFirst { it.id == initialMessageId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { toolMessages.size }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = toolMessages.getOrNull(pagerState.currentPage)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            // ── 顶栏：✕ + "Aharou Computer" + 动作钮 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_close),
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Aharou Computer",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                ComputerActionButton(message = current)
            }
            HorizontalDivider(thickness = 0.5.dp)

            if (toolMessages.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${toolMessages.size}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 6.dp),
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ToolComputerPage(
                    message = toolMessages[page],
                    vdCapture = vdCapture,
                )
            }
        }
    }
}

/** 右上角动作钮：随工具类型分发（终端预填 / 打开浏览器 / 复制结果）。 */
@Composable
private fun ComputerActionButton(message: AgentUIMessage?) {
    val clipboard = LocalClipboardManager.current
    val browserOpener = LocalBrowserOpener.current
    val terminalOpener = LocalTerminalOpener.current
    var copyDone by remember { mutableStateOf(false) }

    val shellCommand = message?.let {
        if (it.toolName == "Bash" || it.toolName == "terminal") extractComputerShellCommand(it.toolArgs) else null
    }
    val browserUrl = message?.let {
        if (it.toolName == "browser") extractComputerBrowserUrl(it.toolArgs) else null
    }

    when {
        shellCommand != null && terminalOpener != null -> {
            IconButton(onClick = { terminalOpener.invoke(shellCommand) }) {
                Icon(
                    FeatherIcons.Terminal,
                    contentDescription = stringResource(R.string.tool_action_open_terminal),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        browserUrl != null && browserOpener != null -> {
            IconButton(onClick = { browserOpener.invoke(browserUrl) }) {
                Icon(
                    FeatherIcons.Globe,
                    contentDescription = stringResource(R.string.tool_action_open_browser),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        else -> {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(message?.content.orEmpty()))
                copyDone = true
            }) {
                Icon(
                    if (copyDone) FeatherIcons.Check else FeatherIcons.Copy,
                    contentDescription = stringResource(R.string.common_copy),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolComputerPage(
    message: AgentUIMessage,
    vdCapture: (suspend (Int) -> Bitmap?)? = null,
) {
    val running = message.content.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
        message.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message.toolName ?: "tool",
                fontFamily = ComputerMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    running -> "执行中…"
                    message.isError -> "失败"
                    else -> "完成"
                },
                fontSize = 11.sp,
                color = when {
                    running -> MaterialTheme.colorScheme.onSurfaceVariant
                    message.isError -> ComputerRed
                    else -> ComputerGreen
                },
            )
        }

        when {
            message.toolName == "Bash" || message.toolName == "terminal" -> {
                TerminalCard(
                    command = extractComputerShellCommand(message.toolArgs),
                    output = formatComputerOutput(message.content, running),
                    isError = message.isError,
                )
            }
            message.toolName == "browser" -> {
                BrowserCard(
                    url = extractComputerBrowserUrl(message.toolArgs),
                    output = formatComputerOutput(message.content, running),
                    running = running,
                )
            }
            message.toolName == "vscreen" -> {
                // [Aharou] 影子屏：大图实时围观 Agent 的离屏操作
                VdCard(capture = vdCapture, running = running)
                message.toolArgs?.takeIf { it.isNotBlank() && it != "{}" }?.let {
                    InfoCard(title = "参数", body = it, mono = true)
                }
                InfoCard(
                    title = "结果",
                    body = formatComputerOutput(message.content, running),
                    mono = false,
                    isError = message.isError,
                )
            }
            else -> {
                val args = message.toolArgs?.takeIf { it.isNotBlank() && it != "{}" }
                if (args != null) {
                    InfoCard(title = "参数", body = args, mono = true)
                }
                InfoCard(
                    title = "结果",
                    body = formatComputerOutput(message.content, running),
                    mono = false,
                    isError = message.isError,
                )
            }
        }

        if (message.toolName == "Bash" || message.toolName == "terminal" || message.toolName == "browser") {
            Text(
                text = "右上角可在终端 / 浏览器中打开",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 影子屏卡：实时抓虚拟屏画面（每 1.5s 一帧）放大展示。
 * Agent 在影子屏里静默操作（不占用户主屏）时，用户点开工具行就能大图围观。
 *
 * [Aharou] ACS 裁剪：ACS 暂无 VdController，[capture] 恒为 null（签名保留以便未来接线），
 * 当前永远显示占位框；抓帧循环按原样保留，capture 为 null 时空转，与原版 null 行为一致。
 */
@Composable
private fun VdCard(
    capture: (suspend (Int) -> Bitmap?)?,
    running: Boolean,
) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(capture, running) {
        while (true) {
            val shot = capture?.let { runCatching { it(VD_FRAME_SAMPLE) }.getOrNull() }
            if (shot != null) frame = shot
            delay(1500)
        }
    }
    val bmp = frame
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .background(ComputerCardBg)
            .border(1.dp, ComputerCardBorder, RoundedCornerShape(Radius.mdLarge))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                // 竖屏虚拟屏（9:16）只给 fillMaxWidth 会被 sheet 的高度框住，
                // Fit 之后缩成窄窄一条、字全看不清；显式声明比例才能按宽度铺开。
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    .clip(RoundedCornerShape(Radius.mdLarge)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (running) stringResource(R.string.vd_capturing)
                           else stringResource(R.string.vd_not_running),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 抓帧采样率：这张图要按屏宽铺开，得取到接近虚拟屏原宽的一半以上才看得清。 */
private const val VD_FRAME_SAMPLE = 2

/** 黑色终端卡：`$ 命令` + 输出（绿字等宽，对齐 原版 的视觉）。 */
@Composable
private fun TerminalCard(command: String?, output: String, isError: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ComputerCardBg)
            .border(1.dp, ComputerCardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!command.isNullOrBlank()) {
            Text(
                text = "$ $command",
                fontFamily = ComputerMono,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = ComputerWhite,
            )
            HorizontalDivider(thickness = 0.5.dp, color = ComputerCardBorder)
        }
        Text(
            text = output.ifBlank { "（无输出）" },
            fontFamily = ComputerMono,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (isError) ComputerRed else ComputerGreen,
        )
    }
}

/**
 * 浏览器卡：URL + 实时快照（每 2.5s 抓帧）+ 结果文本。
 *
 * [Aharou] ACS 裁剪：原版经 `BrowserTabPool.activeManager.captureLiveSnapshot(...)`
 * 抓帧，ACS 无浏览器池（pool 参数已删），实时预览永远走占位分支——故直接裁掉
 * BrowserLivePreview 的抓帧循环与 Image 分支，只保留占位文案
 * （表现与原版 pool == null 完全一致）。
 */
@Composable
private fun BrowserCard(
    url: String?,
    output: String,
    running: Boolean,
) {
    if (!url.isNullOrBlank()) {
        Text(
            text = url,
            fontFamily = ComputerMono,
            fontSize = 12.sp,
            color = ComputerBlue,
            maxLines = 2,
        )
    }
    BrowserLivePreview(running = running)
    Text(
        text = output.ifBlank { "（无输出）" },
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 浏览器实况：轮询浏览器池的活动 WebView 抓缩略帧（对齐 原版 的 3s 轮询机制）。
 * [Aharou] ACS 裁剪：见 [BrowserCard]，抓帧依赖的浏览器池未移植，只留占位文案。
 */
@Composable
private fun BrowserLivePreview(running: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ComputerCardBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (running) "正在抓取页面…" else "（暂无实时画面）",
            color = ComputerGreen,
            fontFamily = ComputerMono,
            fontSize = 12.sp,
        )
    }
}

/** 通用信息卡（参数 / 结果）。 */
@Composable
private fun InfoCard(title: String, body: String, mono: Boolean, isError: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = body.ifBlank { "（空）" },
            fontFamily = if (mono) ComputerMono else FontFamily.Default,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── 小工具：从工具消息里提取命令 / URL / 整理输出 ──────────────────────────

internal fun extractComputerShellCommand(toolArgs: String?): String? {
    val t = toolArgs ?: return null
    return runCatching {
        val obj = JSONObject(t)
        obj.optString("command").ifBlank { obj.optString("input") }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun extractComputerBrowserUrl(toolArgs: String?): String? {
    val t = toolArgs ?: return null
    return runCatching { JSONObject(t).optString("url") }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** 把工具结果（传输 JSON 或纯文本）整理成可读输出。 */
internal fun formatComputerOutput(raw: String, running: Boolean): String {
    if (running) return "…"
    if (raw.isBlank()) return ""
    val t = raw.trim()
    if (t.startsWith("{")) {
        val parsed = runCatching {
            val obj = JSONObject(t)
            val status = obj.optString("status")
            val data = obj.optJSONObject("data")
            val text = when {
                data != null -> data.optString("result")
                    .ifBlank { data.optString("message") }
                    .ifBlank { data.optString("output") }
                else -> obj.optString("message")
            }
            if (text.isNotBlank()) {
                (if (status == "error") "Error: " else "") + text
            } else null
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return raw
}
