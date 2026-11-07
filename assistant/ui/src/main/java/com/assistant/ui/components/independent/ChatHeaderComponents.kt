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

package com.assistant.ui.components.independent

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.ui.compat.OnboardingStep
import com.assistant.ui.compat.onboardingTarget
import com.assistant.ui.components.markdown.formatTokenCount
import com.assistant.ui.theme.Radius
import com.assistant.ui.theme.Spacing
import com.assistant.ui.R
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

@Composable
internal fun ChatHeader(
    inputTokens: Int,
    outputTokens: Int,
    /** 当前会话 id：定时任务胶囊据此判断本会话是不是某个任务的目标。 */
    sessionId: String? = null,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    /**
     * 终端按钮；null 时**不渲染**。
     *
     * <p>独立助手（assistant）与 ACS 的差别就在这：ACS 里终端必然存在，所以原来是
     * 非空参数、按钮恒显示。剥离出来后宿主可能没有终端能力，此时必须让按钮消失——
     * 一颗点了没反应的按钮会被当成「坏了」，比不显示更糟。
     */
    onNavigateToTerminal: (() -> Unit)? = null,
    /** Git 按钮；null 时不渲染（独立助手的工作区不是 git 仓库）。 */
    onNavigateToGit: (() -> Unit)? = null,
    connectionState: ConnectionState? = null,
    showMenuButton: Boolean = true,
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    /**
     * 全屏 / 最小化二选一按钮（ACS 自有入口，Aharou 顶栏没有）。
     *
     * <p>**一颗按钮两种状态**：非全屏时显示「全屏」图标（点击进入全屏），全屏时显示
     * 「最小化」图标（点击退出）。分成两颗常驻按钮会在侧栏这种窄宽度里挤掉别的入口，
     * 而任一时刻只有其中一个动作是有意义的。
     *
     * @param isFullscreen 当前是否处于全屏
     * @param onToggleFullscreen 切换全屏；null 时该按钮不渲染（宿主不支持全屏）
     */
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    /**
     * 顶栏标题（第一行）。null 时保持 ACS 的布局（标题留空，空间全给按钮）。
     *
     * <p>参考项目 Aharou 的顶栏左侧是「标题 + 模型名」两行；ACS 把标题放进侧边栏，
     * 顶栏只放按钮。两种都要支持，因此做成可选参数而不是改默认布局。
     */
    title: String? = null,
    /** 标题下方的副标题（通常显示当前模型）。仅 [title] 非 null 时生效。 */
    subtitle: String? = null,
    /** 副标题前的图标（服务商 logo 或通用图标）。 */
    subtitleIcon: (@Composable () -> Unit)? = null,
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
                // 标题区（可选）：参考项目 Aharou 的顶栏左侧是「会话标题 + 模型名」
                // 两行，ACS 的顶栏把这块留空（标题在侧边栏里可重命名）。
                // 传 null 时保持 ACS 的留空行为，传了才渲染——两种布局都能要。
                if (title != null) {
                    HeaderTitle(
                        title = title,
                        subtitle = subtitle,
                        leadingIcon = subtitleIcon,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // 会话标题已从顶栏移除：侧边栏可重命名，顶栏把空间留给按钮。
                    // 模型切换在输入栏（ChatInputBar 的模型芯片），顶栏不再重复放一个。
                    Spacer(modifier = Modifier.weight(1f))
                }
                HeaderIconButton(
                    icon = FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.chat_new_session),
                    onClick = onNewChat,
                )
                // Git / 终端：null = 宿主没有这个能力，不渲染。
                // 与下面全屏按钮同一模式（也是 `?.let`）。
                onNavigateToGit?.let { openGit ->
                    HeaderIconButton(
                        icon = FeatherIcons.GitBranch,
                        contentDescription = stringResource(R.string.chat_open_git),
                        active = gitActive,
                        onClick = openGit,
                    )
                }
                onNavigateToTerminal?.let { openTerminal ->
                    HeaderIconButton(
                        icon = FeatherIcons.Terminal,
                        contentDescription = stringResource(R.string.chat_open_terminal),
                        active = terminalActive,
                        onClick = openTerminal,
                    )
                }
                // 全屏 / 最小化二选一：同一颗按钮，按当前状态换图标与动作。
                onToggleFullscreen?.let { toggle ->
                    HeaderIconButton(
                        icon = if (isFullscreen) FeatherIcons.Minimize2 else FeatherIcons.Maximize2,
                        contentDescription =
                            stringResource(
                                if (isFullscreen) {
                                    R.string.ai_assistant_minimize
                                } else {
                                    R.string.ai_assistant_fullscreen
                                }),
                        onClick = toggle,
                    )
                }
            }
            // 第二行：远程时是「连接状态 + token 统计」，本地时只有 token 统计。
            //
            // **token 统计不能挂在远程分支里**：本地容器会话（本 App 就是）永远没有
            // connectionState，那样统计就永不渲染——宿主认真填了 inputTokens/
            // outputTokens 却一个数字都看不到（实测反馈「token 数恒为空」）。
            if (connectionState != null || inputTokens > 0 || outputTokens > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (connectionState != null) {
                        ConnectionIndicator(state = connectionState)
                    } else {
                        // 占位，把统计推到右边——不加的话 SpaceBetween 会把统计顶到左侧，
                        // 与有连接状态时的位置不一致（切换视图时数字会左右跳）。
                        Spacer(Modifier.weight(1f))
                    }
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
/**
 * 顶栏标题区：第一行会话标题，第二行模型名（带图标）。
 *
 * <p>形制照参考项目 Aharou 的顶栏（标题粗体 + 下方一行小字模型名）。
 * 两行都限单行并省略，因为顶栏高度固定，折行会把右侧图标挤下去。
 */
@Composable
private fun HeaderTitle(
    title: String,
    subtitle: String?,
    leadingIcon: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(start = Spacing.xs),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leadingIcon?.invoke()
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (leadingIcon != null) 4.dp else 0.dp),
                )
            }
        }
    }
}

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
