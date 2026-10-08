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

package com.tom.rv2ide.artificial.agent.compose.components.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State as MarkdownParseState
import com.tom.rv2ide.artificial.agent.compose.components.style.MarkdownPreprocessor
import com.tom.rv2ide.artificial.agent.compose.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.R as AppR

// 代码块与行内代码共用字体。
//
// 为什么必须内置而不是用系统等宽：Aharou 的注释记了原因——系统等宽**缺下标字形且不回落**，
// 数学公式与代码里的 ₀-₉ 等下标/上标字符会显示成豆腐块。
//
// ACS 本来就有 JetBrains Mono（`core/app/src/main/assets/fonts/jetbrains-mono.ttf`，
// 供编辑器用），此处把它同时放进 `res/font/` 以便 Compose 按 `R.font` 引用——**同一份字体
// 文件两处存放**是资源系统的要求：assets 走 `Typeface.createFromAsset`，
// Compose 的 `Font(R.font.x)` 只认 res/font 下的资源。字节完全相同，不存在版本漂移。
private val CodeFontFamily = FontFamily(Font(AppR.font.jetbrains_mono))

/** 代码块头部在语言未知时显示的占位文案。TODO(strings): 接入 ACS 字符串资源。 */
// 代码块无语言标注时的兜底标签；走资源以便随系统语言切换
private const val MARKDOWN_CODE_LABEL_FALLBACK = "代码"

internal class WeightedLruCache<K, V>(
    private val maxEntries: Int,
    private val maxWeight: Int,
    private val maxItemWeight: Int,
    private val itemWeight: (K, V) -> Int,
) {
    private val entries = LinkedHashMap<K, V>(maxEntries, 0.75f, true)
    private var weight = 0

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        val entryWeight = itemWeight(key, value)
        if (entryWeight > maxItemWeight || entryWeight > maxWeight) return
        entries.remove(key)?.let { weight -= itemWeight(key, it) }
        entries[key] = value
        weight += entryWeight
        while (entries.size > maxEntries || weight > maxWeight) {
            val eldest = entries.entries.iterator().next()
            weight -= itemWeight(eldest.key, eldest.value)
            entries.remove(eldest.key)
        }
    }

    @Synchronized
    fun snapshot(): List<Pair<K, V>> = entries.map { it.key to it.value }

    @Synchronized
    fun size(): Int = entries.size
}

internal const val MARKDOWN_CACHE_MAX_ENTRIES = 80
internal const val MARKDOWN_CACHE_MAX_WEIGHT = 1_000_000
internal const val MARKDOWN_CACHE_MAX_ITEM_WEIGHT = 100_000

internal class MarkdownRenderCache(
    maxEntries: Int = MARKDOWN_CACHE_MAX_ENTRIES
) {
    private val parsedStates = WeightedLruCache<String, MarkdownParseState.Success>(
        maxEntries = maxEntries,
        maxWeight = MARKDOWN_CACHE_MAX_WEIGHT,
        maxItemWeight = MARKDOWN_CACHE_MAX_ITEM_WEIGHT,
        itemWeight = { key, value -> key.length + value.content.length },
    )

    fun get(text: String): MarkdownParseState.Success? =
        parsedStates.get(text) ?: parsedStates.get(text.trim())

    fun getBestPrefix(text: String): MarkdownParseState.Success? {
        val exact = get(text)
        if (exact != null) return exact
        var bestMatch: MarkdownParseState.Success? = null
        var bestLength = 0
        for ((key, value) in parsedStates.snapshot()) {
            if (key.length in (bestLength + 1)..text.length && text.startsWith(key)) {
                bestMatch = value
                bestLength = key.length
            }
        }
        return bestMatch
    }

    fun put(state: MarkdownParseState.Success) {
        if (state.content.length > MARKDOWN_CACHE_MAX_ITEM_WEIGHT / 2) return
        parsedStates.put(state.content, state)
        val trimmed = state.content.trim()
        if (trimmed != state.content) {
            parsedStates.put(trimmed, state)
        }
    }
}

internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "%.1fB".format(tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
internal fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    compact: Boolean = false,
    /**
     * 解析未完成（Loading）时显示的内容；为 null 时回退显示原文纯文本（聊天流式场景）。
     *
     * Aharou 的 `StreamingBubble` 正是传 null —— 它把「仍在生成」的三点指示器画在本组件
     * **下方**（`components/bubbles/StatusBubbles.kt` 的 `StreamingBubble`；注意它在
     * `StatusBubbles.kt` 而**不在** `MessageBubbles.kt`），因此流式期看到的是
     * 「原文正文 + 下方三点」。**这是刻意的形态，不是遗漏**：若要让指示器替掉正文，
     * 那是改设计，不是修 bug。
     */
    loading: (@Composable () -> Unit)? = null,
    /** 长文本虚拟化渲染：正文改用 LazyColumn 逐块渲染（调用方需配合 heightIn 等有限高度约束
     *  才能触发懒加载），避免超大 md 一次性全量组合/测量造成的卡顿。 */
    lazyScroll: Boolean = false,
    cacheEnabled: Boolean = true,
    retainState: Boolean = true
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val semantic = MaterialTheme.semanticColors

    // markdownColor / markdownTypography / markdownPadding / markdownDimens / markdownComponents
    // 都是库提供的 @Composable 工厂函数（内部自带 remember 记忆化），不能也无需再套一层
    // remember（套了会报 “@Composable invocations can only happen from ...”）。直接调用即可。
    val mdColors = markdownColor(
        text = color,
        codeBackground = semantic.mutedSurface,
        inlineCodeBackground = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f),
        dividerColor = semantic.subtleBorder,
        tableBackground = semantic.mutedSurface,
    )

    val typography = MaterialTheme.typography
    // compact：正文、列表用 bodySmall，标题降一档，用于思考气泡等次要文本区域
    val body = if (compact) typography.bodySmall else typography.bodyMedium
    val bodyLineHeight = if (compact) 18.sp else 20.sp
    val codeSize = if (compact) 12.sp else 13.sp
    val mdTypography = markdownTypography(
        h1 = (if (compact) typography.titleMedium else typography.headlineSmall).copy(fontWeight = FontWeight.Bold, color = color),
        h2 = (if (compact) typography.titleSmall else typography.titleLarge).copy(fontWeight = FontWeight.Bold, color = color),
        h3 = (if (compact) typography.bodyLarge else typography.titleMedium).copy(fontWeight = FontWeight.SemiBold, color = color),
        h4 = (if (compact) typography.bodyMedium else typography.titleSmall).copy(fontWeight = FontWeight.SemiBold, color = color),
        h5 = (if (compact) typography.bodySmall else typography.bodyLarge).copy(fontWeight = FontWeight.Medium, color = color),
        h6 = (if (compact) typography.bodySmall else typography.bodyMedium).copy(fontWeight = FontWeight.Medium, color = color),
        paragraph = body.copy(color = color, lineHeight = bodyLineHeight),
        code = TextStyle(fontFamily = CodeFontFamily, fontSize = codeSize, color = MaterialTheme.colorScheme.onSurface),
        inlineCode = TextStyle(fontFamily = CodeFontFamily, color = MaterialTheme.colorScheme.onSurface),
        ordered = body.copy(color = color, lineHeight = bodyLineHeight),
        bullet = body.copy(color = color, lineHeight = bodyLineHeight),
        table = typography.bodySmall.copy(color = color),
    )

    val mdPadding = markdownPadding(
        block = 4.dp,
        list = 2.dp,
        listItemBottom = 1.dp,
        listIndent = 12.dp,
        codeBlock = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    )

    val mdDimens = markdownDimens(
        codeBackgroundCornerSize = 6.dp,
        tableCellPadding = 6.dp,
        tableCornerSize = 6.dp,
    )

    val highlightsBuilder = remember(isDark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
    }

    val processed = remember(text, cacheEnabled) { MarkdownPreprocessor.process(text, cacheEnabled) }
    val baseTransformer = LocalMarkdownImageTransformer.current
    val mathTransformer = remember(baseTransformer, body.fontSize.value) {
        MathImageTransformer(baseTransformer, body.fontSize.value)
    }

    // 后台预热本条消息里的所有 LaTeX 公式：在 item 首次组合时就把 measure+render 摆到后台算好
    // 写缓存，等快速 fling 掠过时主线程 getOrMeasure 直接命中、不再同步 build drawable（实测单
    // 次高达 113ms，是 fling 抽搐的直接根因）。
    val density = LocalDensity.current
    val textSizePx = with(density) { body.fontSize.toPx() }
    val colorArgb = color.toArgb()
    LaunchedEffect(processed, textSizePx, colorArgb) {
        if (!processed.contains("aicode-math-")) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            MathImageTransformer.prewarm(processed, textSizePx, colorArgb)
        }
    }

    CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color
    ) {
        // 0.35.0 适配：Aharou 在 0.41.0 上调用
        //   rememberMarkdownState(content = processed, retainState = retainState)
        // 而 0.35.0 的签名是
        //   rememberMarkdownState(content, immediate, flavour, parser, referenceLinkHandler, <boolean>)
        // —— **没有 retainState 参数**（已核验：`retainState` 这个名字在 0.35.0 全部 class 的
        // Kotlin 元数据里都不存在；第 6 个 boolean 在字节码里用于 gate MarkdownStateImpl.parseBlocking，
        // 与「重解析期间保留上次结果」不是同一语义，故不能拿来顶替）。
        //
        // 因此这里只传 content，其余走库默认值。**保留语义并未丢失**：Aharou 真正用来防止流式闪烁的
        // 是下面自己那套逻辑 —— key(...) 的重建开关、lastSuccessState、以及 renderState 的
        // prefixFallback 兜底 —— 这些全部逐字保留在本函数内，不依赖库参数。
        val mdState = key(if (retainState) null else processed) {
            rememberMarkdownState(content = processed)
        }
        val parseState by mdState.state.collectAsState()
        val cachedState = if (cacheEnabled) cache?.get(processed) else null
        val prefixFallback = if (cacheEnabled) cache?.getBestPrefix(processed) else null
        // 委托属性不能智能转换，先取局部快照再判断
        val currentState = parseState
        val parsedState: MarkdownParseState? = when {
            currentState is MarkdownParseState.Success && currentState.content == processed -> currentState
            cachedState != null -> cachedState
            currentState is MarkdownParseState.Success -> null
            else -> currentState
        }

        var lastSuccessState by remember(cacheEnabled, retainState) {
            mutableStateOf<MarkdownParseState.Success?>(if (retainState) prefixFallback else null)
        }
        if (parsedState is MarkdownParseState.Success) {
            if (retainState) lastSuccessState = parsedState
            if (cacheEnabled) cache?.put(parsedState)
        }

        val renderState: MarkdownParseState.Success? = when (parsedState) {
            is MarkdownParseState.Success -> parsedState
            is MarkdownParseState.Loading -> if (retainState) lastSuccessState ?: prefixFallback else null
            is MarkdownParseState.Error -> null
            null -> if (retainState) lastSuccessState ?: prefixFallback else null
        }

        if (renderState != null) {
            // 长文本用 LazyColumn 逐块渲染时，animations（文本尺寸动画等）不适用，属预期。
            // 下面三个对象（successRenderer / mdComponents / mdAnimations）均 remember：不缓存的话
            // 每次重组都新建 lambda 实例，令下游 Markdown 无法 skip。
            val successRenderer: @Composable (
                state: MarkdownParseState.Success,
                components: MarkdownComponents,
                modifier: Modifier
            ) -> Unit = remember(lazyScroll) {
                if (lazyScroll) {
                    { state, components, m ->
                        // 不用库的 LazyMarkdownSuccess：它只按 startOffset 作 item key，内容原地变化时
                        // 同一位置会复用别的节点的内部渲染状态；这里补 key(node) 做节点级隔离。
                        LazyColumn(modifier = m) {
                            items(
                                items = state.node.children,
                                key = { node -> node.startOffset },
                                contentType = { node -> node.type }
                            ) { node ->
                                key(node) {
                                    MarkdownElement(node, components, state.content)
                                }
                            }
                        }
                    }
                } else {
                    { state, components, m ->
                        MarkdownSuccess(state = state, components = components, modifier = m)
                    }
                }
            }

            // 0.35.0 适配：Aharou 的注释说「markdownComponents 是 @Composable（内部自带记忆化），
            // 不能套 remember，直接调用即可」—— 那是 0.41.0 的事实。0.35.0 的它签名里**没有
            // Composer**（javap 证实），是普通工厂函数，每次重组都会新建一份 MarkdownComponents；
            // 实例不稳定会让下游 Markdown 无法 skip，流式期间整棵 md 树反复重组。
            // 故这里**必须**补一层 remember（在 0.41.0 上反而不能套，会报 @Composable 调用位置错误）。
            val mdComponents = remember(highlightsBuilder, cacheEnabled) {
                markdownComponents(
                // 外层 SelectionContainer（MessageBubbles）统一负责选区；超长助手消息已由
                // AIChatPanel 拆成多条有界 item，不存在超长单 item 的选区树/交互失效问题。
                codeFence = {
                    MarkdownCodeFence(
                        content = it.content,
                        node = it.node,
                    ) { code, language, style ->
                        SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader = true, cacheEnabled = cacheEnabled)
                    }
                },
                codeBlock = {
                    MarkdownCodeBlock(
                        content = it.content,
                        node = it.node,
                    ) { code, language, style ->
                        SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader = true, cacheEnabled = cacheEnabled)
                    }
                },
                // 库默认 maxLines=1 + Ellipsis，单元格长文会被截断；这里放开为完整多行显示。
                table = {
                    MarkdownTable(
                        content = it.content,
                        node = it.node,
                        style = it.typography.table,
                        headerBlock = { content, header, tableWidth, style ->
                            MarkdownTableHeader(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                        rowBlock = { content, header, tableWidth, style ->
                            MarkdownTableRow(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                    )
                },
                )
            }

            Markdown(
                state = renderState,
                modifier = modifier,
                colors = mdColors,
                typography = mdTypography,
                padding = mdPadding,
                dimens = mdDimens,
                // 关闭段落文本的 animateContentSize：快速流式更新下它会持续追赶目标高度，反而弹性抖动。
                animations = markdownAnimations(animateTextSize = { this }),
                imageTransformer = mathTransformer,
                components = mdComponents,
                success = successRenderer,
            )
        } else if (loading != null) {
            loading()
        } else {
            PlainMarkdownText(
                text = text,
                color = color,
                modifier = modifier
            )
        }
    }
}

/**
 * 代码块高亮渲染：等价于库的 `MarkdownHighlightedCode`，但在把高亮区间写进 AnnotatedString
 * 之前先校验范围。highlights 引擎对部分内容会给出 start > end 的区间，库原实现直接调用
 * addStyle，AnnotatedString.Range 构造随即抛 "Reversed range is not supported"；该计算跑在
 * 库自己的后台协程里（produceState + Dispatchers.Default），异常直达线程默认处理器，
 * 调用方 try/catch 拦不住，表现为聊天页闪退。越界（end > code.length）同样会导致崩溃，
 * 一并丢弃。
 */
/**
 * 代码高亮结果的进程级缓存。高亮本身跑在 Dispatchers.Default（不卡主线程），但无跨视口
 * 缓存时 item 每次重进视口都会重跑高亮，完成后又触发一次主线程重组（从纯文本切到
 * 高亮文本）。fling 快速滚过大量代码块时这会挤满 Default 线程并制造密集重组。命中缓存
 * 时直接同步拿到结果作为 produceState 初值，既不重算也不闪纯文本。key 含 isDark（主题影响颜色）。
 */
private object CodeHighlightCache {
    private data class Key(val code: String, val language: String?, val dark: Boolean)

    private const val MAX = 128
    private val cache = object : LinkedHashMap<Key, AnnotatedString>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, AnnotatedString>?): Boolean = size > MAX
    }

    fun get(code: String, language: String?, dark: Boolean): AnnotatedString? =
        synchronized(cache) { cache[Key(code, language, dark)] }

    fun put(code: String, language: String?, dark: Boolean, value: AnnotatedString) {
        synchronized(cache) { cache[Key(code, language, dark)] = value }
    }
}

@Composable
private fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
    cacheEnabled: Boolean,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cached = if (cacheEnabled) CodeHighlightCache.get(code, language, isDark) else null
    val highlighted: AnnotatedString by produceState(
        initialValue = cached ?: AnnotatedString(code),
        code,
        language,
        isDark,
        cacheEnabled,
    ) {
        if (cached != null) return@produceState
        value = withContext(Dispatchers.Default) {
            buildHighlightedText(code, language, highlightsBuilder)
        }.also { if (cacheEnabled) CodeHighlightCache.put(code, language, isDark, it) }
    }

    // ---------------------------------------------------------------------------------------
    // 0.35.0 适配：Aharou 依赖 0.41.0 的
    //   MarkdownCodeBackground(color, shape, modifier, showHeader, language, code, content)
    // 由库绘制代码块头部（语言标签 + 复制按钮）。0.35.0 的同类函数签名是
    //   MarkdownCodeBackground(color, modifier, shape, borderStroke, padding, content)
    // —— **没有 showHeader / language / code 三个参数**，库不再提供头部。
    //
    // 为不静默丢功能，这里用 Compose 基础能力把头部重建出来（语言标签 + 复制按钮，
    // 复制用 1.7.6 的 LocalClipboardManager.setText）。
    // 头部放进 MarkdownCodeBackground 的 content 里而不是叠在上面，这样语言标签与复制按钮
    // 与代码同处一块圆角背景内，与 0.41.0 的外观更接近。
    // 注意：0.41.0 头部的确切视觉不可知，这是**功能等价重建**，不是像素级复刻。
    // ---------------------------------------------------------------------------------------
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column {
            if (showHeader) {
                CodeBlockHeader(language = language, code = code)
            }
            MarkdownBasicText(
                text = highlighted,
                style = style,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(LocalMarkdownPadding.current.codeBlock),
            )
        }
    }
}

/**
 * 代码块头部：左侧语言标签，右侧复制按钮。
 *
 * 0.35.0 的 `MarkdownCodeBackground` 不提供头部（见 [SafeMarkdownHighlightedCode] 内的说明），
 * 故在此重建。复制按钮点击后短暂切到对勾图标给出反馈。
 */
@Composable
private fun CodeBlockHeader(language: String?, code: String) {
    var copied by remember(code) { mutableStateOf(false) }
    // 0.35.0 适配：本工作树解析到 Compose UI 1.7.6，只有 LocalClipboardManager；
    // LocalClipboard / ClipEntry 是 1.8 才引入的。1.7.6 的 ClipboardManager.setText 是同步方法，
    // 故不需要 rememberCoroutineScope + launch 包一层。
    val clipboard = LocalClipboardManager.current
    val label =
        language?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.compose_markdown_code_label)

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalMarkdownColors.current.text.copy(alpha = 0.7f),
        )
        Icon(
            // 已复制 / 待复制：两个图标都是项目已在用的 feather 集，不新增依赖
            imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            contentDescription = label,
            tint = LocalMarkdownColors.current.text.copy(alpha = 0.7f),
            modifier = Modifier
                .size(16.dp)
                .clickable {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
        )
    }
}

private fun buildHighlightedText(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    // 高亮引擎本身也可能抛错（语言解析等），失败时退回纯文本，不让它影响消息渲染。
    val highlights = runCatching {
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        highlightsBuilder.code(code)
            .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
            .build()
            .getHighlights()
    }.getOrDefault(emptyList())

    return buildAnnotatedString {
        append(code)
        for (highlight in highlights) {
            val start = highlight.location.start
            val end = highlight.location.end
            if (start < 0 || start >= end || end > code.length) continue
            // Aharou 原文此处写作 `if (spanStyle != null) addStyle(...)`。when 已穷尽
            // ColorHighlight / BoldHighlight 两个子类型，spanStyle 推断为非空，该判空恒真，
            // 会触发 “Condition is always 'true'” 告警。去掉判空，行为不变。
            val spanStyle = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(spanStyle, start, end)
        }
    }
}

@Composable
private fun PlainMarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp)
    )
}
