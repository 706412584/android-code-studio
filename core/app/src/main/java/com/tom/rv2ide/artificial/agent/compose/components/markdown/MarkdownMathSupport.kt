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

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.tom.rv2ide.artificial.agent.compose.compat.FileLogger
import com.tom.rv2ide.artificial.agent.compose.components.style.MarkdownPreprocessor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 数学感知的 [ImageTransformer]：识别 [MarkdownPreprocessor] 生成的数学链接，用 jlatexmath 渲染成位图；
 * 其余（普通图片）链接一律委托给 [delegate]。
 *
 * @param delegate 处理非数学图片链接的下游 transformer（通常是本地图片渲染器或 NoOp）。
 * @param baseTextSizeSp 行内公式的基准字号（sp），与所在文本正文字号对齐。
 */
/**
 * 公式排版尺寸的进程级缓存。measure 需要构建 [JLatexMathDrawable]（解析 TeX + 排版），
 * 是主线程上的重活。LazyColumn 里公式 item 滚出视口会被 dispose，仅靠 Composable 内的
 * remember 缓存，滚回来重新组合就得再算一遍——快速 fling 时反复进出反复排版造成卡顿。
 * 这里按 (latex, textSizePx) 做进程级缓存，同一公式只排版测量一次。
 */
private const val TAG = "MarkdownMath"

private object LatexMeasureCache {
    private data class Key(val latex: String, val textSizePx: Float)

    // 上限取得大：每条只存一个 android.util.Size（两个 int），内存可忽略；而 fling 快速滚过
    // 长历史时，上限太小会把旧公式挤掉、导致滚回去又在主线程重新排版。512 足够盖住常见会话。
    private const val MAX = 512
    private val sizes = object : LinkedHashMap<Key, android.util.Size>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, android.util.Size>?): Boolean = size > MAX
    }

    fun peek(latex: String, textSizePx: Float): android.util.Size? =
        synchronized(sizes) { sizes[Key(latex, textSizePx)] }

    fun put(latex: String, textSizePx: Float, size: android.util.Size) {
        synchronized(sizes) { sizes[Key(latex, textSizePx)] = size }
    }

    fun getOrMeasure(latex: String, textSizePx: Float, measure: () -> android.util.Size): android.util.Size =
        synchronized(sizes) {
            val key = Key(latex, textSizePx)
            // 主线程同步 build drawable 拿尺寸是重活（实测复杂公式单次 20~113ms）。正常情况下缓存已
            // 由后台预热（renderLatex 完成后回写 measure 缓存）填好，这里直接命中；只有预热还没跑到时才同步兜底。
            sizes[key] ?: measure().also { sizes[key] = it }
        }
}

// Aharou 把 @OptIn 只挂在 renderDispatcher（为 limitedParallelism）上；但本项目的
// kotlinx-coroutines 版本里 limitedParallelism 已稳定，反而是 getCompletionExceptionOrNull()
// 仍标着 @ExperimentalCoroutinesApi —— 它用在 getOrStartBitmap 里，Aharou 那处注解覆盖不到，
// 会报 “This declaration needs opt-in”。故把 @OptIn 提到整个 object 上统一覆盖（行为不变）。
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private object LatexBitmapCache {
    private data class Key(
        val link: String,
        val colorArgb: Int,
        val textSizePx: Float,
    )

    // 限制并发：JLatexMathDrawable.build（TeX 排版）很吃 CPU。预热会一次性提交上百个 render，
    // 若直接丢给 Dispatchers.Default（线程数=核数）会把线程池打满：单个 render 实测飙到 250ms+，
    // 而且同跑在 Default 上的 Markdown 解析/代码高亮被饿死，CPU 饱和又拖累主线程/渲染线程掉帧。
    // 限到 2 并发：公式逐步出现，不抢光所有 CPU。
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val scope = CoroutineScope(SupervisorJob() + renderDispatcher)
    private const val MAX = 256
    private val bitmaps = object : LinkedHashMap<Key, Deferred<Bitmap?>>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Deferred<Bitmap?>>?): Boolean = size > MAX
    }
    fun getOrStartBitmap(
        link: String,
        colorArgb: Int,
        textSizePx: Float,
        render: suspend () -> Bitmap?
    ): Deferred<Bitmap?> = synchronized(bitmaps) {
        val key = Key(link, colorArgb, textSizePx)
        bitmaps[key] ?: scope.async { render() }.also { deferred ->
            bitmaps[key] = deferred
            deferred.invokeOnCompletion {
                if (deferred.getCompletionExceptionOrNull() != null) {
                    synchronized(bitmaps) {
                        if (bitmaps[key] === deferred) bitmaps.remove(key)
                    }
                }
            }
        }
    }

    /** 预热：仅启动后台渲染（写入缓存），不等结果。已在缓存则不重复启动。 */
    fun prewarm(link: String, colorArgb: Int, textSizePx: Float, render: suspend () -> Bitmap?) {
        getOrStartBitmap(link, colorArgb, textSizePx, render)
    }
}

internal class MathImageTransformer(
    private val delegate: ImageTransformer,
    private val baseTextSizeSp: Float,
) : ImageTransformer {

    // -----------------------------------------------------------------------------------------
    // 0.35.0 适配：Aharou 在 0.41.0 上重写了 placeholderConfig，用于给公式返回精确占位尺寸。
    //
    // 0.41.0 的签名是：
    //   placeholderConfig(link, density, containerSize, imageWidth: ImageWidth, imageSize, imageSizeChanged)
    // 0.35.0 的签名是：
    //   placeholderConfig(density, containerSize, imageSize)        —— 没有 link，也没有 ImageWidth
    //
    // 没有 `link` 就无法判断「当前被测量的是不是公式」，因此**无法**在 0.35.0 上按链接区分，
    // 强行重写只能给所有图片套公式尺寸（错误）。故此处不重写，交回库默认实现。
    //
    // 这**不是功能缺失**：Aharou 重写 placeholderConfig 的目的是消除「占位 200dp 方块 → 真实
    // 高度约 50dp」的骤降抖动。0.35.0 侧改由下面两条保证同一效果：
    //   1) transform() 同步返回 ImageData（公式尺寸已由 LatexMeasureCache 算好），不走异步占位；
    //   2) 返回的 FixedLatexPainter 从第一帧起 intrinsicSize 就等于最终尺寸，而 0.35.0 的
    //      ImageTransformer.intrinsicSize 默认实现**就是** `painter.intrinsicSize`（已核验字节码），
    //      因此占位尺寸第一帧即等于最终高度，上滑不再触发 LazyColumn 顶部锚点补偿。
    //
    // TODO(upgrade): 若将来能升到 0.41.0+，可按 Aharou 原样补回 placeholderConfig 重写。
    // -----------------------------------------------------------------------------------------

    @Composable
    override fun transform(link: String): ImageData? {
        val spec = MarkdownPreprocessor.decodeMathLink(link) ?: return delegate.transform(link)
        val (latex, block) = spec

        val density = LocalDensity.current
        val colorArgb = LocalContentColor.current.toArgb()
        // 块级公式与正文使用同一字号，避免块级公式无必要地放大。
        val textSizePx = with(density) { baseTextSizeSp.sp.toPx() }
        // 首次组合时同步取得真实尺寸，避免用字符长度估算造成公式忽大忽小、宽度过大时
        // 从中间开始显示。尺寸一旦确定就不再异步替换，因此不会改写 LazyColumn 锚点。
        val layoutSize = remember(link, textSizePx) {
            LatexMeasureCache.getOrMeasure(latex, textSizePx) { measureLatex(latex, textSizePx) }
        }
        val renderTask = remember(link, colorArgb, textSizePx) {
            LatexBitmapCache.getOrStartBitmap(link, colorArgb, textSizePx) {
                renderLatex(latex, textSizePx, colorArgb)
            }
        }
        val bitmap by produceState<Bitmap?>(initialValue = null, renderTask) {
            value = renderTask.await()
        }
        // 位图完成前后始终使用同一个布局尺寸。否则 Markdown 的 inline placeholder 会
        // 从 0 高度变为公式高度，快速 fling 时每个公式完成都会改写 LazyColumn 的锚点。
        val painter = remember(bitmap, layoutSize) {
            FixedLatexPainter(bitmap?.asImageBitmap(), layoutSize.width, layoutSize.height)
        }
        val widthDp = with(density) { layoutSize.width.toDp() }
        val heightDp = with(density) { layoutSize.height.toDp() }

        val base = if (block) {
            Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())
        } else {
            Modifier
        }
        return ImageData(
            painter = painter,
            modifier = base.then(Modifier.size(widthDp, heightDp)),
            contentScale = ContentScale.Fit,
        )
    }

    private fun measureLatex(latex: String, textSizePx: Float): android.util.Size {
        return try {
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .padding(2)
                .build()
            android.util.Size(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1)
            )
        } catch (e: Exception) {
            android.util.Size(textSizePx.roundToInt().coerceAtLeast(1), textSizePx.roundToInt().coerceAtLeast(1))
        }
    }
    private class FixedLatexPainter(
        private val bitmap: androidx.compose.ui.graphics.ImageBitmap?,
        widthPx: Int,
        heightPx: Int,
    ) : Painter() {
        override val intrinsicSize: Size = Size(widthPx.toFloat(), heightPx.toFloat())

        override fun DrawScope.onDraw() {
            bitmap?.let { image ->
                val scale = minOf(
                    size.width / image.width,
                    size.height / image.height,
                )
                val drawWidth = (image.width * scale).roundToInt().coerceAtLeast(1)
                val drawHeight = (image.height * scale).roundToInt().coerceAtLeast(1)
                drawImage(
                    image = image,
                    dstSize = IntSize(drawWidth, drawHeight),
                    dstOffset = IntOffset(
                        ((size.width - drawWidth) / 2f).roundToInt(),
                        ((size.height - drawHeight) / 2f).roundToInt(),
                    ),
                )
            }
        }
    }

    companion object {
        /**
         * 在后台预热一整段 processed markdown 里的所有 LaTeX 公式：解析出数学链接、逐个启动
         * 后台渲染（写入 bitmap 缓存）。渲染完成后会回写 measure 缓存，于是主线程后续的
         * getOrMeasure 会直接命中、不再同步 build drawable——这是消除 fling 抽搐（主线程单次
         * measure 实测高达 113ms）的关键。缓存已存则不重复启动。
         *
         * 在 IO/Default 线程调用，不阻塞组合。
         */
        fun prewarm(processedMarkdown: String, textSizePx: Float, colorArgb: Int) {
            if (!processedMarkdown.contains("aicode-math-")) return
            // 从 Markdown 图片链接 ![](aicode-math-xxx://enc) 中抽取 link
            for (match in MATH_LINK_REGEX.findAll(processedMarkdown)) {
                val link = match.groupValues[1]
                val spec = MarkdownPreprocessor.decodeMathLink(link) ?: continue
                val latex = spec.first
                if (LatexMeasureCache.peek(latex, textSizePx) != null) continue
                LatexBitmapCache.prewarm(link, colorArgb, textSizePx) {
                    renderLatex(latex, textSizePx, colorArgb)
                }
            }
        }

        private val MATH_LINK_REGEX = Regex("""!\[]\((aicode-math-(?:block|inline)://[^)]+)\)""")

        private fun renderLatex(latex: String, textSizePx: Float, colorArgb: Int): Bitmap? {
            return try {
                val drawable = JLatexMathDrawable.builder(latex)
                    .textSize(textSizePx)
                    .color(colorArgb)
                    .padding(2)
                    .build()
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                // 回写 measure 缓存：后台 build 既已得到真实尺寸，主线程就不必再 build 一次。
                LatexMeasureCache.put(latex, textSizePx, android.util.Size(w, h))
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bitmap
            } catch (e: Exception) {
                FileLogger.w(TAG, "LaTeX 渲染失败: $latex", e)
                null
            }
        }
    }
}
