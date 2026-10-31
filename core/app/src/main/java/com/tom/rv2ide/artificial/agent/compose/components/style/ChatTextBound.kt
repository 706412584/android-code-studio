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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.Locale

/**
 * 聊天文本的「排版有界」处理：长正文拆块，以及给「无断点长串」补断点。
 *
 * 单独成文件是因为 Aharou 侧的消息面板与工具消息组件都受单文件行数棘轮约束，只能缩不能涨。
 */

/** 超过该长度（字符）的助手正文拆成多条有界 chunk。 */
internal const val CHUNK_SPLIT_THRESHOLD_CHARS = 2_000

/** 每条 chunk 的目标字符预算：正文按 markdown 块打包，单块超出预算（如巨大表格）时
 *  按行硬切兜底，保证任意 chunk 高度有界（≈0.5-0.7 屏）。 */
private const val CHUNK_BUDGET_CHARS = 1_200

/**
 * 长正文拆块：以行为单位识别三类 markdown 块——代码围栏（整段）、表格（连续 | 行，
 * 整表保持完整）、普通段落（以空行分隔），然后按字符预算贪心打包成 chunk。
 * 超预算的单块按行拆分为多个块，宁可打断表格也不让某条 item 无界长高。
 */
internal fun splitLongContent(text: String): List<String> {
    val lines = text.lines()
    val rawBlocks = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith(fence)) {
                    sb.append('\n').append(lines[j]); j++
                }
                if (j < lines.size) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            line.isBlank() -> i++
            trimmed.startsWith("|") -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && lines[j].trimStart().startsWith("|")) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            else -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() &&
                    !lines[j].trimStart().startsWith("```") &&
                    !lines[j].trimStart().startsWith("~~~") &&
                    !lines[j].trimStart().startsWith("|")
                ) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
        }
    }
    if (rawBlocks.isEmpty()) return listOf(text)

    // 超预算单块（如巨型表格/巨型段落）按行切成预算内的小块，兜底保证有界。
    // 代码块（``` / ~~~）作为不可分割的语法单元必须保持完整，绝不能按行拆碎，
    // 否则围栏闭合被破坏，后续片段会退化为普通正文、丢失高亮与复制，HTML 更会被预处理器误清洗。
    val blocks = ArrayList<String>()
    for (block in rawBlocks) {
        val isCodeBlock = block.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }
        if (block.length <= CHUNK_BUDGET_CHARS || isCodeBlock) {
            blocks.add(block)
        } else {
            val piece = StringBuilder()
            var weight = 0
            for (ln in block.lines()) {
                // 单行自身超预算（工具输出式的压缩 JSON / 无空格长 token）：按行切不动它，必须按字符硬切，
                // 否则整行会作为一个无界 item 交给文本排版，几万字符的单行足以拖死主线程。
                if (ln.length > CHUNK_BUDGET_CHARS) {
                    if (piece.isNotBlank()) { blocks.add(piece.toString()); piece.setLength(0) }
                    weight = 0
                    var start = 0
                    while (start < ln.length) {
                        val end = minOf(start + CHUNK_BUDGET_CHARS, ln.length)
                        blocks.add(ln.substring(start, end))
                        start = end
                    }
                    continue
                }
                if (weight > 0 && weight + 1 + ln.length > CHUNK_BUDGET_CHARS) {
                    blocks.add(piece.toString()); piece.setLength(0); weight = 0
                }
                piece.append(ln).append('\n'); weight += ln.length + 1
            }
            if (piece.isNotBlank()) blocks.add(piece.toString())
        }
    }

    val chunks = ArrayList<String>()
    val cur = StringBuilder()
    var weight = 0
    for (block in blocks) {
        val w = block.length + 2
        if (weight > 0 && weight + w > CHUNK_BUDGET_CHARS) {
            chunks.add(cur.toString()); cur.setLength(0); weight = 0
        }
        // 块间必须留空行：丢空行会让 markdown 语义粘连（段落 + 紧跟 --- 会被解析成 setext 标题，段落被夸成标题字号）。
        if (weight > 0) cur.append('\n')
        cur.append(block).append('\n'); weight += w
    }
    if (cur.isNotBlank()) chunks.add(cur.toString())
    return chunks
}

/**
 * 给超长「无断点连续段」补零宽空格（U+200B）断点。
 *
 * 工具结果（Bash / Shizuku 的原始输出）常是压缩 JSON 或长路径：整条既没有换行也没有空格。
 * Android 的断行器（minikin）在这种文本上要一路扫到行尾才能确定断点，开销接近 O(n²)——
 * 一条两万字符的单行结果就足以让主线程在首帧里跑满 CPU 并 ANR。
 *
 * 只处理连续超过 [maxRun] 个字符、期间既无空白也无换行的片段（CJK 本身逐字可断，不参与）。
 * ZWSP 渲染为零宽，显示效果与排版结果不变；复制按钮读的是原始 content，也不受影响。
 * 幂等：已有的 ZWSP 会被当成断点，不会重复插入。
 */
internal fun withBreakOpportunities(text: String, maxRun: Int = 48): String {
    if (text.length <= maxRun) return text
    val sb = StringBuilder(text.length + text.length / maxRun + 16)
    var run = 0
    for (ch in text) {
        if (ch == '\u200B' || ch.isWhitespace() || ch.code > 0x2E80) {
            run = 0
            sb.append(ch)
        } else {
            if (run == maxRun) {
                sb.append('\u200B')
                run = 0
            }
            sb.append(ch)
            run++
        }
    }
    return sb.toString()
}

/**
 * [withBreakOpportunities] 的组合期包装：同一段文本在一次组合里只处理一次。
 *
 * 包装成 @Composable 是为了让调用点保留「表达式直接塞进 `text = `」的写法——
 * 调用方都在单文件行数棘轮里，多一行都过不了门禁。
 */
@Composable
internal fun breakableText(text: String): String = remember(text) { withBreakOpportunities(text) }

// ── 有界渲染的公共工具（移植自 Aharou ExpandableOutputText.kt）────────────────
//
// 这三个函数解决的是同一类问题：Text 的测量成本与「最长那一行」成正比，而不是与总长度
// 成正比。一条几十万字符的压缩 JSON（无换行）交给 Text，minikin 要一路扫到行尾才能定断点，
// 主线程在首帧里跑满 CPU 并 ANR。所以凡是要把外部来源的长文本塞进 Text 的地方，
// 都应先过这里：单行封顶、只读尾部窗口、按有界区间分块。

/**
 * 把 [text] 切成「每块最多 [maxLinesPerChunk] 行或 [maxCharsPerChunk] 字符」的下标区间
 * （左闭右开，区间非空）。只记录边界、不复制文本，所以对几十万字符的输入也是 O(n) 扫描、
 * 常数内存；超长单行（无换行可断）在这一层被硬切，并避开 UTF-16 代理对。
 *
 * 调用方按区间 substring 渲染，避免「一次点击就把全文拼回一个字符串」。
 */
internal fun boundedTextChunks(
    text: String,
    maxCharsPerChunk: Int,
    maxLinesPerChunk: Int,
): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    require(maxCharsPerChunk > 0 && maxLinesPerChunk > 0)
    val ranges = ArrayList<IntRange>(text.length / maxCharsPerChunk + 1)
    var start = 0
    while (start < text.length) {
        val hardLimit = minOf(text.length, start + maxCharsPerChunk)
        var end = hardLimit
        var lines = 0
        var i = start
        while (i < hardLimit) {
            if (text[i] == '\n' && ++lines == maxLinesPerChunk) {
                end = i + 1
                break
            }
            i++
        }
        // 落在硬上限里且不在行尾：退回本块内最后一个换行，避免把一行劈成两半；
        // 整块都没有换行（压缩 JSON / 无空格长 token）时才按字符硬切。
        if (end < text.length && text[end - 1] != '\n') {
            var newline = end - 1
            while (newline > start && text[newline] != '\n') newline--
            end = if (newline > start) newline + 1 else surrogateSafeCut(text, end)
        }
        ranges.add(start until end)
        start = end
    }
    return ranges
}

/** 行首的代理对不能被切掉一半，否则后半块开头会渲染成乱码方块。 */
private fun surrogateSafeCut(text: String, at: Int): Int =
    if (at in 1 until text.length && text[at - 1].isHighSurrogate()) at + 1 else at

/**
 * 尾部预览：只读最后 [scanChars] 个字符，取最后 [maxLines] 行，每行超过 [maxLineChars] 时
 * 保留行尾并补前置省略号（报错信息在行尾）。被窗口截断的首行同样补省略号。
 *
 * 工具结果的有效信息（报错、汇总、退出码、失败用例）几乎总在末尾，而固定窗口意味着
 * 预览的开销与全文多大完全无关。
 */
internal fun tailPreview(
    text: String,
    maxLines: Int,
    maxLineChars: Int,
    scanChars: Int,
): String {
    var start = (text.length - scanChars).coerceAtLeast(0)
    if (start in 1 until text.length && text[start - 1].isHighSurrogate()) start++
    val windowStart = start > 0
    val lines = text.substring(start).split('\n')
    val firstIndex = (lines.size - maxLines).coerceAtLeast(0)
    val sb = StringBuilder()
    for (index in firstIndex until lines.size) {
        if (sb.isNotEmpty()) sb.append('\n')
        val raw = lines[index].trimEnd('\r')
        val cutAt = raw.length - maxLineChars
        if (cutAt > 0) {
            var from = cutAt
            if (raw[from - 1].isHighSurrogate()) from++
            sb.append('…').append(raw, from, raw.length)
        } else {
            if (index == firstIndex && windowStart) sb.append('…')
            sb.append(raw)
        }
    }
    return sb.toString()
}

/**
 * 每行超过 [maxChars] 的部分截断并补行尾省略号；没有任何行超限时原样返回（不复制字符串）。
 * 用于正常路径与占位预览，防止「一条几万字符的单行」把 Text 的测量拖成 O(n) 主线程长任务。
 */
internal fun capLineLengths(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    var lineStart = 0
    var needsCut = false
    var i = 0
    while (i <= text.length) {
        if (i == text.length || text[i] == '\n') {
            if (i - lineStart > maxChars) {
                needsCut = true
                break
            }
            lineStart = i + 1
        }
        i++
    }
    if (!needsCut) return text
    val sb = StringBuilder(text.length)
    lineStart = 0
    i = 0
    while (i <= text.length) {
        if (i == text.length || text[i] == '\n') {
            val over = i - lineStart > maxChars
            sb.append(text, lineStart, if (over) lineStart + maxChars else i)
            if (over) sb.append('…')
            if (i < text.length) sb.append('\n')
            lineStart = i + 1
        }
        i++
    }
    return sb.toString()
}

/** 字符数折算成人读的大小提示；按 1 字符 ≈ 1 字节估算，够用即可。 */
internal fun formatOutputSize(chars: Int): String = when {
    chars >= 1_000_000 -> String.format(Locale.US, "%.1f MB", chars / 1_000_000.0)
    chars >= 1_000 -> "${chars / 1_000} KB"
    else -> "$chars B"
}
