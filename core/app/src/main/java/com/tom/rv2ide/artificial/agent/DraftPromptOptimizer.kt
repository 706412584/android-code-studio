package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.ai.agent.conversation.*
import com.tom.rv2ide.ai.protocol.*
import com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerDraft
import com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerReference
import com.tom.rv2ide.artificial.agent.compose.components.independent.PLACEHOLDER_REGEX
import com.tom.rv2ide.artificial.agent.compose.components.independent.liveReferences
import org.json.JSONArray
import org.json.JSONObject

/** 用临时编号保护每颗卡片。按名字在改写后的正文里搜索，会把手写的同名文字误绑成卡片。 */
internal class RewriteProjection private constructor(
    val prompt: String,
    private val prefix: String,
    private val pieces: List<Piece>,
) {
  private data class Piece(val start: Int, val end: Int, val text: String, val ref: ComposerReference?)

  fun decode(answer: String): ComposerDraft? {
    val rewritten = answer.trim()
    if (rewritten.isEmpty() || rewritten.length > maxOf(4096, prompt.length * 3)) return null
    val seen = mutableSetOf<Int>()
    val refs = mutableListOf<ComposerReference>()
    val out = StringBuilder()
    var cursor = 0
    while (true) {
      val start = rewritten.indexOf(prefix, cursor)
      if (start < 0) break
      val end = rewritten.indexOf("]]", start + prefix.length)
      if (end < 0) return null
      val id = rewritten.substring(start + prefix.length, end).toIntOrNull() ?: return null
      val piece = pieces.getOrNull(id) ?: return null
      if (!seen.add(id)) return null
      out.append(rewritten, cursor, start)
      piece.ref?.let { refs += it.copy(start = out.length, end = out.length + piece.text.length) }
      out.append(piece.text)
      cursor = end + 2
    }
    if (seen.size != pieces.size) return null
    out.append(rewritten, cursor, rewritten.length)
    return ComposerDraft(out.toString(), refs, out.length)
  }

  companion object {
    fun from(text: String, refs: List<ComposerReference>): RewriteProjection? {
      if (text.isBlank() || text.trimStart().startsWith('/')) return null
      val protectedRefs = liveReferences(ComposerDraft(text, refs, 0))
      val pieces = protectedRefs.map { Piece(it.start, it.end, it.token, it) }.toMutableList()
      PLACEHOLDER_REGEX.findAll(text).forEach { m ->
        if (protectedRefs.none { it.start <= m.range.last && it.end > m.range.first }) {
          pieces += Piece(m.range.first, m.range.last + 1, m.value, null)
        }
      }
      pieces.sortBy { it.start }
      var prefix = "[[ACS_REF_"
      while (text.contains(prefix)) prefix += '_'
      var cursor = 0
      var editable = false
      val masked = buildString {
        pieces.forEachIndexed { index, piece ->
          append(text, cursor, piece.start)
          if (!editable) editable = (cursor until piece.start).any { !text[it].isWhitespace() }
          append(prefix).append(index).append("]]")
          cursor = piece.end
        }
        append(text, cursor, text.length)
      }
      if (!editable && (cursor until text.length).none { !text[it].isWhitespace() }) return null
      return RewriteProjection(masked, prefix, pieces)
    }
  }
}

/** 一轮独立的文字请求，不创建会话、不注册工具，也不向主会话广播或写入条目。 */
internal object DraftPromptOptimizer {
  private const val INSTRUCTION = """你是输入框的提示词编辑器。输入 JSON 中 history 是当前会话已发生的对话，仅作参考；draft 是用户尚未发送的草稿。只输出改写后的草稿，不回答、不执行、不调用工具。结合直接相关的历史，把“它”“刚才那个”等指代替换成已经明确的对象；历史不充分时不要猜。保持草稿的语言、意图、范围和限制，保留文件路径、代码、报错和每个 [[ACS_REF_…]] 标记原样且恰好一次。不要凭空增加需求或实现细节。简单要求一句话说清；已足够清楚则原样返回。不要加标题、前言、外层引号或代码围栏。"""

  fun optimize(config: ModelConfig, store: ConversationStore, session: String?, draft: String,
               token: ModelCancellationToken): String {
    val messages = messages(store, session, draft, token, config.contextSize)
    val result = ModelClient().stream(config, messages, null, token,
        ModelRequestOptions(AiBehaviorSettings.REASONING_AUTO, false))
    check(!result.hasToolCalls() && result.text.isNotBlank())
    return result.text
  }

  internal fun messages(store: ConversationStore, session: String?, draft: String,
                        token: ModelCancellationToken, contextSize: Int): List<ModelMessage> {
    // 按限制倒读当前会话，不为一次文字改写水合图片或遍历整个 JSONL。
    val recent = mutableListOf<JSONObject>()
    var remaining = if (contextSize > 0) (contextSize - draft.length - 512).coerceIn(0, 8000) else 8000
    var before: String? = null
    if (!session.isNullOrBlank()) {
      pages@ for (pageIndex in 0 until 6) {
        if (remaining <= 0 || token.isCancelled) break
        val page = store.readPage(session, before)
        for (entry in page.entries.asReversed()) {
          val (role, content) = when (entry) {
            is CompactionEntry -> "summary" to entry.summary
            is UserMessageEntry -> if (!entry.isMeta) "user" to entry.content else continue
            is AssistantMessageEntry -> if (!entry.isRefused) "assistant" to entry.content else continue
            is ToolResultEntry -> "tool" to entry.content
            else -> continue
          }
          val limit = minOf(remaining, if (role == "tool") 500 else 2000)
          if (content.isNotBlank()) {
            val excerpt = if (content.length <= limit) content else content.take(limit / 2) + "…" + content.takeLast(limit / 2)
            recent += JSONObject().put("role", role).put("text", excerpt)
            remaining -= excerpt.length
          }
          if (remaining <= 0 || entry is CompactionEntry) break@pages
        }
        if (!page.hasMore() || page.entries.isEmpty()) break
        before = page.entries.first().uuid
      }
    }
    val input = JSONObject().put("history", JSONArray(recent.asReversed())).put("draft", draft)
    return listOf(SystemModelMessage(INSTRUCTION), UserModelMessage(input.toString()))
  }
}
