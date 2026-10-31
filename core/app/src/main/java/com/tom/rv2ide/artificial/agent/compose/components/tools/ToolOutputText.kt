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

package com.tom.rv2ide.artificial.agent.compose.components.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.components.style.breakableText
import com.tom.rv2ide.artificial.agent.compose.components.style.boundedTextChunks
import com.tom.rv2ide.artificial.agent.compose.components.style.capLineLengths
import com.tom.rv2ide.artificial.agent.compose.components.style.formatOutputSize
import com.tom.rv2ide.artificial.agent.compose.components.style.tailPreview
import com.tom.rv2ide.resources.R

/*
 * 工具输出/工具参数的三段渲染护栏（移植自 Aharou ExpandableOutputText，阈值同数量级）。
 *
 * 为什么需要：Text 的测量成本与「最长那一行」成正比，而不是与总长度成正比。工具结果常是
 * 几十万字符的压缩 JSON / 无换行长 token，整段交给 Text 时 minikin 要一路扫到行尾才能定断点，
 * 主线程在首帧里跑满 CPU 并 ANR。32k 字符已相当于十几屏 12sp 等宽文本，远超人工阅读量。
 *
 * 三段：
 *   1) ≤ 32k：原样一个 Text（单行仍按 [TOOL_OUTPUT_INLINE_LINE_MAX_CHARS] 封顶）；
 *   2) > 32k：只渲染尾部预览 + 折叠徽标，全文不进排版；
 *   3) 点「展开」后按有界区间分块揭示，每块一个独立 Text，绝不把全文拼回一个字符串。
 */

/** 超过该长度的文本不再整段交给 Text。 */
internal const val TOOL_OUTPUT_COLLAPSE_THRESHOLD_CHARS = 32_000

/**
 * 折叠预览只回看尾部这么多个字符：工具结果的有效信息（报错、汇总、退出码、失败用例）
 * 几乎总在末尾，固定窗口意味着预览开销与全文多大完全无关。
 */
private const val TOOL_OUTPUT_PREVIEW_SCAN_CHARS = 8_000

/**
 * 折叠预览最多显示这么多行。
 *
 * <p>取 8 而不是更多：折叠态要与「大小提示 + 展开」一起**在一屏内看全**，
 * 而调用方（工具卡结果区）的滚动窗只有 240dp。预览行数一多，动作行就被挤到窗外，
 * 用户看到输出却找不到展开入口（实机验证时正是这样）。
 */
private const val TOOL_OUTPUT_PREVIEW_LINES = 8

/** 预览里单行的字符上限：一行 200KB 的压缩 JSON 交给 Text，排版成本同样与全文等大。 */
private const val TOOL_OUTPUT_PREVIEW_LINE_MAX_CHARS = 400

/** 正常路径（≤ 阈值）里单行的字符上限，防止无换行长串拖慢测量。 */
private const val TOOL_OUTPUT_INLINE_LINE_MAX_CHARS = 4_000

/** 展开态每块的字符上限（含换行）。每块一个独立 Text，单块排版成本有界。 */
private const val TOOL_OUTPUT_REVEAL_CHUNK_MAX_CHARS = 4_000

/** 展开态每块的行数上限。 */
private const val TOOL_OUTPUT_REVEAL_CHUNK_LINES = 40

/** 展开后首批 / 每批揭示的块数。 */
private const val TOOL_OUTPUT_REVEAL_INITIAL_CHUNKS = 2
private const val TOOL_OUTPUT_REVEAL_BATCH_CHUNKS = 2

/**
 * 正常路径的单行封顶。流式（live）路径也用它：那里文本每个 tick 都在变，
 * 若走折叠护栏，一旦跨过阈值就会在「徽标 ↔ 正文」之间反复跳。
 */
internal fun capToolOutputLines(text: String): String =
    capLineLengths(text, TOOL_OUTPUT_INLINE_LINE_MAX_CHARS)

/**
 * 该文本是否需要走折叠护栏。
 *
 * <p>调用方据此决定要不要把内容放进自己的固定高度滚动窗：**护栏态不能进**——
 * 预览 + 「展开」动作行一旦被裁进 240dp 的窗口里，用户就看得到输出、点不到展开
 * （实机验证时正是这样）。护栏态自己管高度，动作行始终可见。
 */
internal fun toolOutputNeedsGuard(text: String): Boolean =
    text.length > TOOL_OUTPUT_COLLAPSE_THRESHOLD_CHARS

/**
 * 工具「指令 / 结果」的正文渲染：自带长内容护栏。
 *
 * 文本节点各自包在 [SelectionContainer] 里，动作行（展开 / 继续加载）放在外面——
 * 若把可点击控件也塞进 SelectionContainer，长按选择手势会吞掉点击。
 */
@Composable
internal fun ToolOutputText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    /** null = 工具卡默认的等宽小字号。 */
    textStyle: TextStyle? = null,
) {
    val style =
        textStyle
            ?: MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    if (text.length <= TOOL_OUTPUT_COLLAPSE_THRESHOLD_CHARS) {
        // 键用长度：工具结果只追加增长，长度相同即同一份内容（与 ToolMessageBody 的
        // contentKey 同一口径）。用整串做键会在每次重组时多一次几十万字符的 O(n) 相等比较。
        val bounded =
            remember(text.length) { capLineLengths(text, TOOL_OUTPUT_INLINE_LINE_MAX_CHARS) }
        SelectionContainer {
            Text(
                text = breakableText(bounded),
                color = color,
                style = style,
                modifier = modifier,
            )
        }
        return
    }

    // 展开态同理用长度做 key；rememberSaveable 让折叠/展开跨配置变更保留。
    var expanded by rememberSaveable(text.length) { mutableStateOf(false) }
    if (expanded) {
        RevealedToolOutput(text, color, style, modifier)
    } else {
        CollapsedToolOutput(text, color, style, onExpand = { expanded = true }, modifier = modifier)
    }
}

/** 折叠预览的最大显示高度：动作行必须留在它下面且可见，不能被预览挤出去。 */
private val TOOL_OUTPUT_PREVIEW_MAX_HEIGHT = 150.dp

/**
 * 折叠态：尾部预览 + 大小提示 + 展开入口。
 *
 * <p>预览本身既限行数也限高度：只限行数时，一条超长单行（压缩 JSON）会折成十几行，
 * 把「展开」按钮顶到调用方的滚动窗口之外——实测就是这样，用户看得到输出却点不到展开。
 * 动作行必须恒定可见，所以预览用固定高度裁切。
 */
@Composable
private fun CollapsedToolOutput(
    text: String,
    color: Color,
    style: TextStyle,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = remember(text.length) {
        tailPreview(
            text = text,
            maxLines = TOOL_OUTPUT_PREVIEW_LINES,
            maxLineChars = TOOL_OUTPUT_PREVIEW_LINE_MAX_CHARS,
            scanChars = TOOL_OUTPUT_PREVIEW_SCAN_CHARS,
        )
    }
    Column(modifier = modifier.fillMaxWidth()) {
        // 预览区高度封顶：超长单行折成十几行时，动作行会被顶出调用方的滚动窗口。
        SelectionContainer {
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = TOOL_OUTPUT_PREVIEW_MAX_HEIGHT)) {
                Text(
                    text = breakableText(preview),
                    color = color.copy(alpha = 0.85f),
                    style = style,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    stringResource(
                        R.string.compose_tool_card_output_collapsed, formatOutputSize(text.length)),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.compose_tool_card_expand),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier.clickable(onClick = onExpand)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** 展开态：按块揭示，每块独立 Text；块边界只算下标区间，揭示时才 substring。 */
@Composable
private fun RevealedToolOutput(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val chunks = remember(text.length) {
        boundedTextChunks(
            text = text,
            maxCharsPerChunk = TOOL_OUTPUT_REVEAL_CHUNK_MAX_CHARS,
            maxLinesPerChunk = TOOL_OUTPUT_REVEAL_CHUNK_LINES,
        )
    }
    val total = chunks.size
    var revealed by remember(text.length) {
        mutableStateOf(TOOL_OUTPUT_REVEAL_INITIAL_CHUNKS.coerceAtMost(total))
    }
    Column(modifier = modifier.fillMaxWidth()) {
        chunks.take(revealed).forEach { range ->
            SelectionContainer {
                Text(
                    text = breakableText(text.substring(range.first, range.last + 1)),
                    color = color,
                    style = style,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (revealed < total) {
            Text(
                text =
                    stringResource(R.string.compose_tool_card_output_load_more, total - revealed),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier.clickable {
                            revealed =
                                (revealed + TOOL_OUTPUT_REVEAL_BATCH_CHUNKS).coerceAtMost(total)
                        }
                        .padding(vertical = 4.dp),
            )
        }
    }
}
