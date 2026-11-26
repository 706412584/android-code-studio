/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.compose.components.independent

/** Stable identity and UTF-16 range for one structured reference in the composer text. */
data class ComposerReference @JvmOverloads constructor(
    val kind: String,
    val id: String,
    val label: String,
    val path: String,
    val start: Int,
    val end: Int,
    /**
     * 消息引用（[kind] == "quote"）的完整引用块文本，形如
     * `> 引用自 助手回复：\n> 选中的文字`。
     *
     * <p>编辑框里只显示紧凑的 [token]（单行），真正的多行引用块存在这里，
     * 发送时由 [expandQuoteReferences] 换回正文。其余 kind 不用此字段。
     *
     * <p>为什么放在引用而不是另建一套缓冲：引用的身份本来就跟着 `[start,end)` 走，
     * 把「显示什么」和「实际是什么」拆成 token / payload 两半，编辑、删除、会话隔离
     * 全部复用既有逻辑，不必再维护一份与文本对齐的旁路状态。
     */
    val payload: String = "",
) {
  /**
   * 写进输入框的可见 token。
   *
   * 消息引用（[kind] == "quote"）不加 `@` 前缀：它表达的是「引用这段对话」而非
   * 「@ 某个实体」，且 [label] 已是「引用 助手回复：…」这类可读短句，再加 `@` 会读成
   * 「@引用…」。其余引用（会话/文件/智能体/技能/插件）保持 `@label`，与 @ 提及一致。
   */
  val token: String
    get() = if (kind == "quote") label else "@$label"
}

/** Text projection plus references whose identity is tied to exact ranges, not matching labels. */
data class ComposerDraft(
    val text: String,
    val references: List<ComposerReference>,
    val cursor: Int,
)

/** Replace [start, end) with a reference token and a trailing separator. */
fun insertReference(
    text: String,
    references: List<ComposerReference>,
    start: Int,
    end: Int,
    reference: ComposerReference,
): ComposerDraft {
  val boundedStart = start.coerceIn(0, text.length)
  val boundedEnd = end.coerceIn(boundedStart, text.length)
  val token = reference.token
  var replacementStart = boundedStart
  var replacementEnd = boundedEnd
  val live = liveReferences(ComposerDraft(text, references, boundedStart))
  for (existing in live) {
    val cursorInside = boundedStart == boundedEnd &&
        boundedStart > existing.start && boundedStart < existing.end
    val overlaps = boundedStart < existing.end && boundedEnd > existing.start
    if (cursorInside || overlaps) {
      replacementStart = minOf(replacementStart, existing.start)
      replacementEnd = maxOf(replacementEnd, existing.end)
    }
  }
  val hasFollowingWhitespace = replacementEnd < text.length && text[replacementEnd].isWhitespace()
  val insertion = token + if (hasFollowingWhitespace) "" else " "
  val updatedText = text.substring(0, replacementStart) + insertion + text.substring(replacementEnd)
  val delta = insertion.length - (replacementEnd - replacementStart)
  // 显式替换已知区间，不能从相同 token 的文本差异反推旧身份是否仍有效。
  val kept = live.mapNotNull {
    when {
      it.end <= replacementStart -> it
      it.start >= replacementEnd -> it.shift(delta)
      else -> null
    }
  }
  val inserted = reference.copy(start = replacementStart, end = replacementStart + token.length)
  return ComposerDraft(updatedText, (kept + inserted).sortedBy { it.start },
      replacementStart + insertion.length)
}

/** Reconcile references after the TextField changed its projected text. */
fun applyTextEdit(draft: ComposerDraft, newText: String): ComposerDraft =
    applyTextEdit(draft, newText, newText.length)

/** Reconcile references after the TextField changed its projected text and selection. */
fun applyTextEdit(draft: ComposerDraft, newText: String, cursor: Int): ComposerDraft {
  if (draft.text == newText) {
    return draft.copy(cursor = cursor.coerceIn(0, newText.length))
  }

  var prefix = 0
  val prefixLimit = minOf(draft.text.length, newText.length)
  while (prefix < prefixLimit && draft.text[prefix] == newText[prefix]) prefix++
  if (splitsSurrogatePair(draft.text, prefix) || splitsSurrogatePair(newText, prefix)) prefix--
  prefix = prefix.coerceAtLeast(0)

  var suffix = 0
  val suffixLimit = minOf(draft.text.length - prefix, newText.length - prefix)
  while (
      suffix < suffixLimit &&
          draft.text[draft.text.length - 1 - suffix] == newText[newText.length - 1 - suffix]
  ) {
    suffix++
  }
  if (splitsSurrogatePair(draft.text, draft.text.length - suffix) ||
      splitsSurrogatePair(newText, newText.length - suffix)) {
    suffix--
  }
  suffix = suffix.coerceAtLeast(0)

  val oldStart = prefix
  val oldEnd = draft.text.length - suffix
  val newEnd = newText.length - suffix
  val delta = newText.length - draft.text.length
  val live = ArrayList<ComposerReference>(draft.references.size)
  val removeRanges = ArrayList<IntRange>()

  for (reference in draft.references) {
    val insertionInsideReference = oldStart == oldEnd && oldStart > reference.start && oldStart < reference.end
    val replacedReference = oldStart < oldEnd && oldStart < reference.end && oldEnd > reference.start
    when {
      !insertionInsideReference && !replacedReference && reference.end <= oldStart -> live.add(reference)
      !insertionInsideReference && !replacedReference && reference.start >= oldEnd ->
        live.add(reference.shift(delta))
      else -> {
        // Keep user-inserted text, but remove every surviving fragment of the broken token.
        if (reference.start < oldStart) {
          removeRanges.add(reference.start until minOf(reference.end, oldStart))
        }
        if (reference.end > oldEnd) {
          val rightStart = newEnd
          val rightEnd = newEnd + (reference.end - maxOf(reference.start, oldEnd))
          if (rightEnd > rightStart) removeRanges.add(rightStart until rightEnd)
        }
      }
    }
  }

  var resultText = newText
  var resultCursor = cursor
  for (range in removeRanges.sortedByDescending { it.first }) {
    val removedEnd = (range.last + 1).coerceAtMost(resultText.length)
    resultText = resultText.removeRange(range.first, removedEnd)
    if (resultCursor > range.first) resultCursor -= minOf(resultCursor, removedEnd) - range.first
    for (index in live.indices.reversed()) {
      val item = live[index]
      when {
        range.last + 1 <= item.start ->
            live[index] = item.shift(-(range.last + 1 - range.first))
        range.first < item.end && range.last + 1 > item.start -> live.removeAt(index)
      }
    }
  }

  return ComposerDraft(resultText, live, resultCursor.coerceIn(0, resultText.length))
}

/**
 * 把文本里的消息引用 chip 展开成完整引用块，其余引用 token 原样保留。
 *
 * <p>编辑框只显示单行 chip（[ComposerReference.token]），发送时要换回多行引用块
 * （[ComposerReference.payload]）——气泡显示、落盘历史与发给模型的文本都靠它。
 *
 * <p>只处理 [liveReferences] 认得的引用（token 仍完整占据原区间）：用户手改过 chip
 * 的那条已不算有效引用，其残留字面量按普通文字留在正文里，与粘贴标记的处理一致。
 */
fun expandQuoteReferences(draft: ComposerDraft): String =
    expandQuoteReferencesWithRanges(draft).first

/**
 * 展开消息引用 chip，并返回重映射到展开后文本的**其余**引用。
 *
 * <p>为什么要重映射：一个 quote chip 的 token（单行短标签）展开成多行引用块后长度大不同，
 * 排在它后面的 @ 提及等引用的 `[start,end)` 会整体后移。若不重映射，气泡里给 @ 提及
 * 加粗的高亮会落到错位的字符上。返回的引用里**不含 quote 本身**：引文是上下文块，
 * 不是行内提及，气泡不需要在它上面做字符级高亮。
 */
fun expandQuoteReferencesWithRanges(
    draft: ComposerDraft,
): Pair<String, List<ComposerReference>> {
  val live = liveReferences(draft)
  val quotes = live.filter { it.kind == "quote" && it.payload.isNotEmpty() }.sortedBy { it.start }
  if (quotes.isEmpty()) return draft.text to live
  val sb = StringBuilder(draft.text.length)
  var cursor = 0
  // 记录每段展开对累计位移的贡献（payload 比 token 多出的长度）。
  val expansions = ArrayList<Triple<Int, Int, Int>>() // start, end, delta
  for (q in quotes) {
    sb.append(draft.text, cursor, q.start)
    sb.append(q.payload)
    expansions += Triple(q.start, q.end, q.payload.length - (q.end - q.start))
    cursor = q.end
  }
  sb.append(draft.text, cursor, draft.text.length)
  fun shift(pos: Int): Int {
    var delta = 0
    for ((start, end, d) in expansions) {
      when {
        pos <= start -> return pos + delta
        pos >= end -> delta += d
        else -> return start + delta // 落在被替换区间内部：夹到展开后的起点
      }
    }
    return pos + delta
  }
  val remapped = live
      .filter { it.kind != "quote" }
      .map { it.copy(start = shift(it.start), end = shift(it.end)) }
  return sb.toString() to remapped
}

/**
 * 隐藏若干字符区间后的偏移映射，供输入框的 `VisualTransformation` 使用。
 *
 * <p><b>为什么需要它</b>：消息引用（kind=quote）在正文里只应靠上方胶囊表达，正文中的
 * token 只是「发送时展开成完整引用块」的载体。直接把 token 从显示里删掉会改变可见文本
 * 长度，而光标、选区、其余 @ 提及的高亮全都记在**原始坐标**上——必须经过本映射才能落到
 * 正确的可见位置。底层文本保持不变，因此范围跟踪、删除、发送展开等逻辑一律照旧。
 *
 * <p>区间以 [start, end) 给出，内部按起点升序保证 `toVisible` / `toOriginal` 单调、
 * 且与 `OffsetMapping` 的约定一致（`toVisible(text.length)` 恰等于可见文本长度）。
 * 纯 Kotlin，可在普通 JVM 单测里直接验证。
 */
class HiddenTextMapping(ranges: List<IntRange>) {
  private val hidden = ranges.sortedBy { it.first }

  /** 原始偏移 → 可见偏移。落在隐藏区间内部的偏移夹到区间起点。 */
  fun toVisible(original: Int): Int {
    var removed = 0
    for (r in hidden) {
      val start = r.first
      val end = r.last + 1
      when {
        original <= start -> return original - removed
        original >= end -> removed += end - start
        else -> return start - removed
      }
    }
    return original - removed
  }

  /** 可见偏移 → 原始偏移。 */
  fun toOriginal(visible: Int): Int {
    var removed = 0
    for (r in hidden) {
      val start = r.first
      val end = r.last + 1
      val visibleStart = start - removed
      if (visible <= visibleStart) return visible + removed
      removed += end - start
    }
    return visible + removed
  }
}

/**
 * 气泡展示用：把消息正文按消息引用（kind=quote）切成「正文段 + 引用段」交错序列。
 *
 * <p>输入是落盘后的 displayText（quote 以单行 token 形式存在）与它对得上的引用元数据。
 * 输出每段要么是普通文本，要么是引用（携带 label 与原文 payload 供卡片渲染）。
 * 不在 [references] 里、或区间越界的 token 按普通文本处理——用户可能在别处手打过
 * 相同字面量，不能凭字符串猜。
 *
 * <p>纯 Kotlin（结构化数据 + 字符串切片），可在 JVM 单测里直接验证分段正确性。
 */
sealed interface MessageSegment {
  data class Text(val text: String) : MessageSegment
  data class Quote(val token: String, val label: String, val path: String) : MessageSegment
}

fun splitMessageByQuotes(
    content: String,
    quotes: List<ComposerReference>,
): List<MessageSegment> {
  if (quotes.isEmpty()) return if (content.isEmpty()) emptyList() else listOf(MessageSegment.Text(content))
  val valid = quotes
      .filter { it.kind == "quote" && it.start >= 0 && it.end <= content.length && it.start < it.end }
      .sortedBy { it.start }
  if (valid.isEmpty()) return if (content.isEmpty()) emptyList() else listOf(MessageSegment.Text(content))
  val out = ArrayList<MessageSegment>(valid.size * 2 + 1)
  var cursor = 0
  for (q in valid) {
    if (q.start < cursor) continue // 重叠/乱序：跳过，避免错位
    if (q.start > cursor) out += MessageSegment.Text(content.substring(cursor, q.start))
    out += MessageSegment.Quote(
        token = content.substring(q.start, q.end), label = q.label, path = q.path)
    cursor = q.end
  }
  if (cursor < content.length) out += MessageSegment.Text(content.substring(cursor))
  return out
}

/** Return only identities whose exact token still occupies its recorded range. */
fun liveReferences(draft: ComposerDraft): List<ComposerReference> {
  val result = ArrayList<ComposerReference>(draft.references.size)
  var previousEnd = -1
  for (reference in draft.references.sortedBy { it.start }) {
    if (reference.start < previousEnd || reference.start < 0 || reference.end <= reference.start || reference.end > draft.text.length) continue
    if (draft.text.substring(reference.start, reference.end) != reference.token) continue
    result.add(reference)
    previousEnd = reference.end
  }
  return result
}

private fun ComposerReference.shift(amount: Int): ComposerReference =
    copy(start = start + amount, end = end + amount)

private fun splitsSurrogatePair(text: String, boundary: Int): Boolean =
    boundary > 0 && boundary < text.length &&
        Character.isHighSurrogate(text[boundary - 1]) && Character.isLowSurrogate(text[boundary])
