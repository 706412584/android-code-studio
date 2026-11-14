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

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.compat.OnboardingStep
import com.tom.rv2ide.artificial.agent.compose.compat.onboardingTarget
import com.tom.rv2ide.artificial.agent.compose.components.markdown.formatTokenCount
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.resources.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Maximize2
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Terminal

/**
 * 远程容器连接状态（Aharou `feature/agent/domain/container/ConnectionState` 照搬）。
 *
 * ACS 暂无容器/远程模式，调用点恒传 null；保留枚举使移植逐字。
 */
internal enum class ConnectionState {
    CONNECTED,
    CONNECTING,
    FAILED,
    DISCONNECTED,
}

/**
 * 顶栏右上角「切换」按钮的状态：图标与无障碍描述都随它变。
 *
 * <p>每个状态描述的是**点击后会做什么**，而不是「当前处于什么形态」——
 * 用户看图标决定要不要点，图标必须承诺点击的结果。
 */
internal enum class HeaderToggleMode {
    /** 宿主不支持切换，该按钮不渲染。 */
    HIDDEN,

    /** 不在全屏、点击进入真全屏（放大图标）。 */
    ENTER_FULLSCREEN,

    /** 已在真全屏、点击退出（缩小图标）。 */
    EXIT_FULLSCREEN,

    /**
     * 应用外悬浮窗：点击折叠回 FAB 图标，不是进入全屏。
     *
     * <p>悬浮窗**刻意不支持真全屏**：拉起 AssistantFullscreenActivity 后 overlay 窗口
     * 仍悬在系统窗口层，两层窗口互相盖住、面板「看起来全屏却缩不回去」（用户实测）。
     */
    COLLAPSE_TO_FAB,
}

@Composable
internal fun ChatHeader(
    inputTokens: Int,
    outputTokens: Int,
    /** 当前会话 id：定时任务胶囊据此判断本会话是不是某个任务的目标。 */
    sessionId: String? = null,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToGit: () -> Unit,
    connectionState: ConnectionState? = null,
    showMenuButton: Boolean = true,
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    /**
     * 右上角切换按钮的当前语义。
     *
     * <p><b>为什么是三态而不是布尔</b>：按钮点击后会发生什么由**宿主**决定，同一颗按钮
     * 在不同宿主下代表完全不同的动作。早先用 `isFullscreen: Boolean`，只能表达
     * 「已在全屏 / 不在全屏」，于是应用外悬浮窗（点它是折叠回 FAB）被迫沿用非全屏分支的
     * 「全屏」图标与「全屏」描述——图标说的和点击后做的完全不是一回事（用户实测反馈）。
     * 折叠是第三种语义，布尔装不下。
     *
     * @param onToggle 切换动作；[toggleMode] 为 [HeaderToggleMode.HIDDEN] 时该按钮不渲染
     */
    toggleMode: HeaderToggleMode = HeaderToggleMode.HIDDEN,
    onToggle: (() -> Unit)? = null,
    /**
     * 顶栏空白区的拖动回调（卸载的落点增量）。
     *
     * <p>只有**应用外悬浮窗**需要：它的面板是一个独立系统窗口，用户可以把它拖到
     * 屏幕任意位置。Activity 宿主（侧栏/全屏/内联页）的位置由布局决定，传 null 即可
     * ——拖动也不会产生任何效果。
     */
    onHeaderDrag: ((dx: Float, dy: Float) -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 大屏常驻侧栏时隐掉汉堡键：侧栏已经摆在左边，再给个开关反而困惑。
                if (showMenuButton) {
                    HeaderIconButton(
                        icon = FeatherIcons.Menu,
                        contentDescription = stringResource(R.string.chat_open_sidebar),
                        onClick = onOpenDrawer,
                        modifier = Modifier.onboardingTarget(OnboardingStep.OPEN_SIDEBAR)
                    )
                } else {
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                // 会话标题已从顶栏移除：侧边栏可重命名，顶栏把空间留给按钮。
                // 模型切换在输入栏（ChatInputBar 的模型芯片），顶栏不再重复放一个。
                //
                // 这块空白同时是**拖动柄**（[onHeaderDrag] 非 null 时）：应用外悬浮窗的
                // 面板只有在顶栏空白处才能拖——消息列表要滚、输入框要点、图标按钮要按，
                // 把拖动装到它们身上都会抢走原本的手势。空白区没有任何交互语义，正合适。
                Spacer(
                    modifier =
                        Modifier.weight(1f).then(
                            if (onHeaderDrag != null) {
                              Modifier.pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                  change.consume()
                                  onHeaderDrag(dragAmount.x, dragAmount.y)
                                }
                              }
                            } else Modifier))
                HeaderIconButton(
                    icon = FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.chat_new_session),
                    onClick = onNewChat,
                )
                HeaderIconButton(
                    icon = FeatherIcons.GitBranch,
                    contentDescription = stringResource(R.string.chat_open_git),
                    active = gitActive,
                    onClick = onNavigateToGit,
                )
                HeaderIconButton(
                    icon = FeatherIcons.Terminal,
                    contentDescription = stringResource(R.string.chat_open_terminal),
                    active = terminalActive,
                    onClick = onNavigateToTerminal,
                )
                // 切换按钮：图标与描述都表达「点击会做什么」，不表达「当前是什么形态」。
                if (toggleMode != HeaderToggleMode.HIDDEN) {
                    onToggle?.let { toggle ->
                        val (icon, labelRes) =
                            when (toggleMode) {
                                HeaderToggleMode.EXIT_FULLSCREEN ->
                                    FeatherIcons.Minimize2 to R.string.ai_assistant_minimize
                                HeaderToggleMode.COLLAPSE_TO_FAB ->
                                    FeatherIcons.Minimize2 to R.string.ai_assistant_collapse
                                HeaderToggleMode.ENTER_FULLSCREEN, HeaderToggleMode.HIDDEN ->
                                    FeatherIcons.Maximize2 to R.string.ai_assistant_fullscreen
                            }
                        HeaderIconButton(
                            icon = icon,
                            contentDescription = stringResource(labelRes),
                            onClick = toggle,
                        )
                    }
                }
            }
            // 远程模式：左边 SSH 连接状态，右边 token 累计统计
            if (connectionState != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ConnectionIndicator(state = connectionState)
                    TokenStats(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens
                    )
                }
            }
            // 本地容器会话没有连接状态，胶囊不能挂在上面那个远程分支里。
            // ACS：任务数据源由 JSONL 会话接线填充（ScheduledTaskSnapshot），当前恒传 null、不占位。
            ScheduledTaskPill(sessionId = sessionId, task = null)
        }
    }
}

/**
 * 顶栏图标按钮。
 *
 * <p>尺寸收到 [BUTTON_SIZE]：侧栏只有约 300dp 宽，默认 48dp 触摸目标放不下
 * 「菜单 + 新建 + Git + 终端 + 全屏」五个——最后一个会被推到可视区外。
 * 这里同时把触摸目标收窄到 36dp，`contentDescription` 仍保证无障碍可读。
 *
 * @param active 工作台入口高亮：大屏右栏开着对应内容时，否则看不出点一下是开还是关
 */
@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    IconButton(onClick = onClick, modifier = modifier.size(BUTTON_SIZE)) {
        Box(
            modifier = Modifier
                .size(BUTTON_GLYPH_SIZE)
                .then(
                    if (active) {
                        Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 顶栏按钮的触摸目标边长。窄侧栏里 48dp 的默认值放不下 5 个按钮。 */
private val BUTTON_SIZE = 36.dp

/** 按钮内高亮底块（[active] 时可见）的边长。 */
private val BUTTON_GLYPH_SIZE = 30.dp

@Composable
private fun ConnectionIndicator(
    state: ConnectionState
) {
    val (dotColor, text) = when (state) {
        ConnectionState.CONNECTED ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.chat_ssh_connected)
        ConnectionState.CONNECTING ->
            MaterialTheme.colorScheme.tertiary to stringResource(R.string.chat_ssh_connecting)
        ConnectionState.FAILED ->
            MaterialTheme.colorScheme.error to stringResource(R.string.chat_ssh_failed)
        ConnectionState.DISCONNECTED ->
            MaterialTheme.colorScheme.outline to stringResource(R.string.chat_ssh_disconnected)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TokenStats(inputTokens: Int, outputTokens: Int) {
    val inStr = formatTokenCount(inputTokens.toLong())
    val outStr = formatTokenCount(outputTokens.toLong())
    Text(
        text = "↑$inStr ↓$outStr",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun RemoteConnectingPlaceholder(
    state: ConnectionState
) {
    val text = when (state) {
        ConnectionState.CONNECTING -> stringResource(R.string.chat_connecting_remote)
        ConnectionState.FAILED -> stringResource(R.string.chat_remote_connect_failed)
        ConnectionState.DISCONNECTED -> stringResource(R.string.chat_no_remote_connection)
        ConnectionState.CONNECTED -> ""
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state == ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WelcomeState(bottomReserve: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        val targetOffset = minOf(-(maxHeight * 0.13f), -(bottomReserve / 2))
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = if (Build.VERSION.SDK_INT >= 30) snap() else tween(durationMillis = 220),
            label = "welcome-offset"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.offset(y = animatedOffset)
        ) {
            Text(
                text = stringResource(R.string.chat_placeholder),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_input_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
