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

package com.tom.rv2ide.artificial.agent.compose.components.style

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.tom.rv2ide.resources.R as ResourcesR

/**
 * 消息区（DSH 扁平文档流风格）的共用尺寸与色板。
 *
 * 设计基线：整块对话区不再用「每段文字各套一个描边卡片」的堆叠，而是**一页文档**——
 * 助手正文直接铺在页面底色上，用户消息是一枚浅色药丸，工具调用是带 1px 细线的扁平行，
 * 只有代码/差异这类「内容块」才升起来变成带描边的白底卡。
 *
 * 颜色全部取自当前主题的语义槽位（不写死 hex），因此四套预设 + 深色模式 + 莫奈取色
 * 都能自动适配；深色下的色阶关系由 `copy(alpha)` 叠在主题表面上得到，与浅色同构。
 */
internal object ChatStyle {
    /** 细线/描边宽度：扁平风格里唯一的分隔手段。 */
    val hairline = 1.dp

    /** 用户药丸圆角。 */
    val bubbleCorner = 14.dp

    /** 代码卡/差异卡圆角。 */
    val cardCorner = 10.dp

    /** 行内小面板（指令、结果）圆角。 */
    val panelCorner = 8.dp

    /** 工具行最小高度：保证指腹可点，布局紧凑不松散。 */
    val toolRowMinHeight = 32.dp

    /** 代码卡头部高度。 */
    val codeHeaderHeight = 36.dp

    /** 行首图标格：思考行 / 工具行 / 工具分组头共用同一格宽，三行的图标与后续文字才会左右对齐。 */
    val rowIconSize = 16.dp
}

/** 用户消息底色：浅色主题下即主题容器色（默认蓝即参考图那种淡蓝），深色下自动变深。 */
@Composable
internal fun chatUserBubbleColor(): Color = MaterialTheme.colorScheme.primaryContainer

/** 用户消息文字：压在浅药丸上的深色文字，不再用白字反白。 */
@Composable
internal fun chatUserBubbleTextColor(): Color = MaterialTheme.colorScheme.onPrimaryContainer

/** 1px 细线：扁平风格里唯一的分组手段。 */
@Composable
internal fun chatHairlineColor(): Color = MaterialTheme.colorScheme.outlineVariant

/** 弱面板底色（工具结果、代码区底）：浅色下接近 `#F9FAFB`。 */
@Composable
internal fun chatMutedSurfaceColor(): Color =
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

/** 内容卡底色（差异/代码卡）：浅色下是白底 + 细线，深色下降一档成为「纸面」。 */
@Composable
internal fun chatCardSurfaceColor(): Color = MaterialTheme.colorScheme.surface

/** 横向 1px 细线，占满可用宽度。 */
@Composable
internal fun ChatHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(ChatStyle.hairline)
            .background(chatHairlineColor())
    )
}

/** 消息下方的时间 / token / 耗时小字，统一 12sp 弱化灰。 */
@Composable
internal fun ChatMetaText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private val CHAT_CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 气泡下方的时间戳（如 `17:55`）。 */
internal fun formatClockTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(CHAT_CLOCK_FORMATTER)

/**
 * 「思考」字形：矢量原子/思考图标（对齐用户参考图）。
 * 颜色跟随调用方给的主题色 tint，等比缩放到指定尺寸。
 *
 * TODO: 待接入 ACS 资源。Aharou 此处引的是自绘的 `R.drawable.ic_thinking`（14dp 原子图形），
 *  ACS 没有该资源、也不宜为此单造一个 drawable，故改用 ACS 既有的 `R.drawable.ic_ai_agent`
 *  ——`item_assistant_thinking.xml` 的思维链卡片用的就是它，语义同位，颜色同样由 tint 控制。
 */
@Composable
internal fun ThinkingGlyph(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 16.dp
) {
    Icon(
        painter = painterResource(R.drawable.ic_ai_agent),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(iconSize)
    )
}

/**
 * 内容卡：白底 + 1px 细线 + 可选头部（标题 + 复制）与页脚统计。
 *
 * 参考图里「写入 .dsh-write-test.txt」下面那块就是它：头部一行放文件路径与「复制」，
 * 正文是等宽代码/差异，页脚给 `+3 −0 · 1 个文件` 这类汇总。
 */
@Composable
internal fun ChatCodeCard(
    title: String?,
    copyText: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(ChatStyle.cardCorner)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(chatCardSurfaceColor())
            .border(ChatStyle.hairline, chatHairlineColor(), shape)
    ) {
        if (!title.isNullOrBlank() || !copyText.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ChatStyle.codeHeaderHeight)
                    .padding(start = Spacing.sm, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.orEmpty(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!copyText.isNullOrBlank()) {
                    ChatCopyAction(copyText = copyText)
                }
            }
            ChatHairline()
        }
        content()
        if (!footer.isNullOrBlank()) {
            ChatHairline()
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            )
        }
    }
}

/** 行内等宽小面板：工具「指令」「结果」这类不需要复制按钮的次要内容块。 */
@Composable
internal fun ChatMonoPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChatStyle.panelCorner))
            .background(chatMutedSurfaceColor())
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
    ) {
        content()
    }
}

/**
 * 手指拖动允许在边界接力外层；松手后的惯性不得越过内层窗口。
 * 内层为 LazyColumn（如思考展开窗口）时用无参重载——该行为不依赖具体滚动状态。
 */
@Composable
internal fun rememberBoundNestedScrollConnection(): NestedScrollConnection {
    return remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = if (source == NestedScrollSource.SideEffect) Offset(0f, available.y) else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }
}

/** 内层为 ScrollState（如工具结果窗口）时以滚动状态作记忆键。 */
@Composable
internal fun rememberBoundNestedScrollConnection(scrollState: ScrollState): NestedScrollConnection {
    return remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = if (source == NestedScrollSource.SideEffect) Offset(0f, available.y) else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }
}

// TODO: 待接入 ACS 字符串资源。Aharou 原为 `R.string.chat_copy` / `R.string.chat_copied`
//  （`values/` 中文、`values-en/` 英文，共 2 条），ACS 的 strings.xml 里没有对应条目，
//  这里按"少量文案内联"处理，暂不新增资源键。
private const val CHAT_COPY_LABEL = "复制"
private const val CHAT_COPIED_LABEL = "已复制"

/**
 * 卡片头部的「复制」文字按钮：点完短暂变成「已复制」。
 *
 * 高度取 32dp（含内边距后命中区约 36dp）：这是密度很高的消息区里的次级动作，
 * 与工具行 40dp 的主命中区错开层级，避免整屏都是 44dp 的大按钮。
 */
@Composable
internal fun ChatCopyAction(
    copyText: String,
    modifier: Modifier = Modifier,
    // 默认值来自资源：@Composable 默认参数允许调 stringResource，
    // 这样调用方不传 label 时也随系统语言走
    label: String = stringResource(ResourcesR.string.compose_style_chat_copy)
) {
    var copied by remember(copyText) { mutableStateOf(false) }
    // 注：Aharou 用的是 `LocalClipboard` + `ClipEntry` + `setClipEntry(...)`，
    // 那是 Compose UI 1.8 才有的剪贴板 API；本项目锁定 compose-ui 1.7.6，
    // 该版本只有 `LocalClipboardManager`（`ClipboardManager.setText(AnnotatedString)`）。
    // 写入内容与可见行为一致，仅 API 形状不同。
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ChatStyle.panelCorner))
            .clickable {
                scope.launch {
                    clipboard.setText(AnnotatedString(copyText))
                    copied = true
                }
            }
            .heightIn(min = 32.dp)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(
            imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = if (copied) stringResource(ResourcesR.string.compose_style_chat_copied) else label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (copied) {
        LaunchedEffect(copied) {
            delay(1500)
            copied = false
        }
    }
}
