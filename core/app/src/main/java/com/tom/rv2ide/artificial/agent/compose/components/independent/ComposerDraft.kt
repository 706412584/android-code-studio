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
data class ComposerReference(
    val kind: String,
    val id: String,
    val label: String,
    val path: String,
    val start: Int,
    val end: Int,
) {
  val token: String
    get() = "@$label"
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
