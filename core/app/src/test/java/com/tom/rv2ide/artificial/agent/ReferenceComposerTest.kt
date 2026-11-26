package com.tom.rv2ide.artificial.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.tom.rv2ide.ai.agent.conversation.ConversationSummary
import com.tom.rv2ide.artificial.agent.compose.components.independent.*
import org.junit.Assert.*
import org.junit.Test

class ReferenceComposerTest {
  private fun reference(id: String = "abc") = ComposerReference("session", id, "会话", "", 0, 0)

  @Test fun 草稿按会话恢复并共享完整选区() {
    val bridge = AssistantInputBarBridge()
    bridge.switchSession("a")
    bridge.text = "开头 后续"
    bridge.edit(TextFieldValue(bridge.text, TextRange(3)))
    bridge.insert(reference())
    assertEquals("开头 @会话 后续", bridge.text)
    bridge.switchSession("b")
    assertEquals("", bridge.text)
    bridge.text = "另一个草稿"
    bridge.switchSession("a")
    assertEquals("abc", bridge.references.single().id)
    assertEquals(7, bridge.fieldValue.selection.end)
  }

  @Test fun 同名手写与粘贴不会自动绑定且退格删除整颗() {
    val bridge = AssistantInputBarBridge()
    bridge.text = "@会话 "
    bridge.insert(reference())
    assertEquals(1, bridge.references.size)
    val ref = bridge.references.single()
    bridge.edit(TextFieldValue(bridge.text.removeRange(ref.end - 1, ref.end), TextRange(ref.end - 1)))
    assertEquals("@会话  ", bridge.text)
    assertTrue(bridge.references.isEmpty())
    assertEquals(4, bridge.fieldValue.selection.end)
  }

  @Test fun IME组合不被替换且光标补全只看光标前文() {
    val bridge = AssistantInputBarBridge()
    bridge.edit(TextFieldValue("前 @ 后", TextRange(3), TextRange(2, 3)))
    bridge.insert(reference())
    assertEquals("前 @ 后", bridge.text)
    assertNotNull(bridge.fieldValue.composition)
    assertNull(referenceTrigger(bridge.fieldValue, bridge.references))
    val at = TextFieldValue("前 @会 后文", TextRange(4))
    assertEquals(TextRange(2, 4), referenceTrigger(at, emptyList()))
    assertNull(referenceTrigger(TextFieldValue("a@b", TextRange(3)), emptyList()))
  }

  @Test fun 胶囊光标与全选替换不保留旧身份() {
    val bridge = AssistantInputBarBridge()
    bridge.insert(reference())
    bridge.edit(TextFieldValue(bridge.text, TextRange(2)))
    assertEquals(0, bridge.fieldValue.selection.end)
    bridge.edit(TextFieldValue("新文字", TextRange(3)))
    assertTrue(bridge.references.isEmpty())
    assertEquals("新文字", bridge.text)
  }

  @Test fun 删除同名胶囊仅解除指定身份并平移后续范围() {
    val bridge = AssistantInputBarBridge()
    bridge.insert(reference("first"))
    bridge.insert(reference("second"))
    bridge.insert(reference("third"))
    bridge.removeReference(bridge.references.first())
    assertEquals(" @会话 @会话 ", bridge.text)
    assertEquals(listOf("second", "third"), bridge.references.map { it.id })
    assertEquals(listOf(1, 5), bridge.references.map { it.start })
    assertEquals(bridge.text.length, bridge.fieldValue.selection.end)
    bridge.removeReference(bridge.references.last())
    assertEquals(" @会话  ", bridge.text)
    assertEquals("second", bridge.references.single().id)
    assertEquals(bridge.text.length, bridge.fieldValue.selection.end)
  }

  @Test fun 删除胶囊保持前方光标和反向选区() {
    val bridge = AssistantInputBarBridge()
    bridge.text = "前文 "
    bridge.insert(reference())
    bridge.edit(TextFieldValue(bridge.text, TextRange(1)))
    bridge.removeReference(bridge.references.single())
    assertEquals("前文  ", bridge.text)
    assertEquals(TextRange(1), bridge.fieldValue.selection)
    bridge.insert(reference())
    bridge.edit(TextFieldValue(bridge.text, TextRange(bridge.text.length, 0)))
    bridge.removeReference(bridge.references.single())
    assertEquals(TextRange(bridge.text.length, 0), bridge.fieldValue.selection)
    assertTrue(bridge.references.isEmpty())
  }

  @Test fun IME组合期间不删除胶囊() {
    val bridge = AssistantInputBarBridge()
    bridge.insert(reference())
    bridge.edit(TextFieldValue(bridge.text + "拼", TextRange(5), TextRange(4, 5)))
    val before = bridge.fieldValue
    bridge.removeReference(bridge.references.single())
    assertEquals(before, bridge.fieldValue)
    assertEquals("abc", bridge.references.single().id)
  }

  @Test fun 当前项目只取两条有内容根会话且排除当前() {
    fun summary(id: String, cwd: String = "/project", count: Int = 1, parent: String = "", time: Long) =
        ConversationSummary(id, id, cwd, 0, time, count, parent, "")
    val candidates = recentReferenceSessions(listOf(
        summary("other", cwd = "/other", time = 10), summary("empty", count = 0, time = 9),
        summary("child", parent = "a", time = 8), summary("current", time = 7),
        summary("a", time = 6), summary("b", time = 5), summary("c", time = 4)), "/project", "current")
    assertEquals(listOf("a", "b"), candidates.map { it.id })
  }

  @Test fun 队列编辑投影恢复引用和未落盘图片() {
    val bridge = AssistantInputBarBridge()
    bridge.insert(reference("session-identity"))
    val (model, refs) = modelReferenceText(
        ComposerDraft(bridge.text, bridge.references, bridge.fieldValue.selection.end))
    val raw = com.tom.rv2ide.ai.protocol.ImageInputPayload.rawInputJson(model, "image/png", "aW1hZ2U=")
    val attachments = listOf(com.tom.rv2ide.ai.protocol.AttachmentMeta("图片.png", "", "image/png", 5, true))
    val requestType = Class.forName("${AgentOrchestrator::class.java.name}\$QueuedRequest")
    val requestConstructor = requestType.declaredConstructors.single { it.parameterCount == 9 }
    requestConstructor.isAccessible = true
    val request = requestConstructor.newInstance(
        "provider", "model", model, bridge.text, null, raw, null, attachments, refs)
    val entryConstructor = AgentOrchestrator.QueuedEntry::class.java.getDeclaredConstructor(requestType)
    entryConstructor.isAccessible = true
    val entry = entryConstructor.newInstance(request)

    assertEquals(bridge.text, entry.text)
    assertFalse(entry.text.contains("session-identity"))
    assertEquals(raw, entry.rawInputJson)
    bridge.text = ""
    bridge.restore(entry.text, entry.references)
    assertEquals("session-identity", bridge.references.single().id)
    val restored = restorePendingAttachments(entry.attachments, entry.rawInputJson).single()
    assertEquals("图片.png", restored.fileName)
    assertEquals("", restored.localPath)
    assertEquals("aW1hZ2U=", restored.image?.base64Data)
    assertEquals("image/png", restored.image?.mimeType)
  }

  @Test fun 消息引用以单行胶囊插入并在发送时展开为引用块() {
    val bridge = AssistantInputBarBridge()
    bridge.text = "问题如下"
    val block = "> 引用自 助手回复：\n> 选中的那一行"
    bridge.insert(ComposerReference(
        kind = "quote", id = "42", label = "引用 助手回复：选中的那一行",
        path = "chat://assistant/42", start = 0, end = 0, payload = block))
    // 编辑框里只有单行 chip：不含多行引用块的换行与 ">" 前缀。
    assertTrue(bridge.text.contains("引用 助手回复：选中的那一行"))
    assertFalse(bridge.text.contains("> 引用自"))
    assertEquals(1, bridge.references.size)
    // 发送后：显示文本保留 chip token（气泡据此渲染卡片），模型文本才展开成完整引用块。
    val draft = ComposerDraft(bridge.text, bridge.references, bridge.fieldValue.selection.end)
    val (display, model, refs) = composeRequestTexts(draft)
    assertFalse(display.contains("> 引用自"))   // 显示文本不展开
    assertTrue(model.contains("> 引用自 助手回复：\n> 选中的那一行")) // 模型文本展开
    // quote 不作为结构化引用指令发给模型（不该让模型去 conversation_read 它）。
    assertFalse(model.contains("conversation_read"))
    // 落盘元数据保留 quote 条目（区间指向显示文本里的 token），供气泡重建卡片。
    assertTrue(refs.any { it.kind == "quote" })
  }

  @Test fun 消息引用与at提及混排时各自区间都对准显示文本() {
    val bridge = AssistantInputBarBridge()
    bridge.insert(reference("session-identity"))
    val block = "> 引用自 用户消息：\n> 原文很长很长"
    bridge.insert(ComposerReference(
        kind = "quote", id = "7", label = "引用 用户消息：原文",
        path = "chat://user/7", start = 0, end = 0, payload = block))
    val draft = ComposerDraft(bridge.text, bridge.references, bridge.fieldValue.selection.end)
    val (display, _, refs) = composeRequestTexts(draft)
    // 显示文本保留 chip token（不展开），所以 quote 与 @提及 的元数据区间都应落在 display 上。
    val quote = refs.first { it.kind == "quote" }
    assertEquals("引用 用户消息：原文", display.substring(quote.start, quote.end))
    val session = refs.first { it.kind == "session" }
    assertEquals("@会话", display.substring(session.start, session.end))
  }

  @Test fun 气泡按引用区间切分出卡片段与正文段() {
    // "引用 助手回复 这张图讲了什么"：quote token = "引用 助手回复"（index 0..5），其后是正文。
    val content = "引用 助手回复 这张图讲了什么"
    val quote = ComposerReference(
        kind = "quote", id = "1", label = "引用 助手回复", path = "chat://assistant/1",
        start = 0, end = "引用 助手回复".length)
    val segs = splitMessageByQuotes(content, listOf(quote))
    assertEquals(2, segs.size)
    assertTrue(segs[0] is MessageSegment.Quote)
    assertEquals("引用 助手回复", (segs[0] as MessageSegment.Quote).label)
    assertTrue(segs[1] is MessageSegment.Text)
    assertEquals("这张图讲了什么", (segs[1] as MessageSegment.Text).text.trim())
  }

  @Test fun 无引用时整条正文单段且越界引用按文本处理() {
    assertEquals(listOf<MessageSegment>(MessageSegment.Text("整条消息")),
        splitMessageByQuotes("整条消息", emptyList()))
    // 越界区间被忽略，整条按文本
    val bad = ComposerReference("quote", "1", "引用", "", 0, 999)
    assertEquals(listOf<MessageSegment>(MessageSegment.Text("正文")),
        splitMessageByQuotes("正文", listOf(bad)))
  }

  @Test fun 隐藏引用区间后偏移映射可往返且可见长度正确() {
    // "abc引用xyz"：index 3,4 = "引用" 被隐藏，可见文本为 "abcxyz"（长度 6）。
    val text = "abc引用xyz"
    val mapping = HiddenTextMapping(listOf(3 until 5))
    // 区间前的偏移不变
    assertEquals(2, mapping.toVisible(2))
    assertEquals(3, mapping.toVisible(3))          // 紧贴区间起点，落在隐藏处即起点
    assertEquals(3, mapping.toVisible(5))          // 区间后第一个字符 'x' → 可见 index 3
    assertEquals(6, mapping.toVisible(text.length)) // 可见文本长度恰为 6
    // 可见侧往返一致
    for (v in 0..6) {
      assertEquals(v, mapping.toVisible(mapping.toOriginal(v)))
    }
    assertEquals(8, mapping.toOriginal(6))         // 可见末尾 → 原始末尾
  }

  @Test fun 展示文本不含内部指令且模型保留真实ID() {
    val bridge = AssistantInputBarBridge()
    bridge.insert(reference("session-identity"))
    val (model, refs) = modelReferenceText(ComposerDraft(bridge.text, bridge.references, bridge.fieldValue.selection.end))
    assertFalse(bridge.text.contains("session-identity"))
    assertTrue(model.contains("conversation_read"))
    assertTrue(model.contains("session-identity"))
    assertEquals("session-identity", refs.single().id)
    bridge.text = "手写 @会话"
    assertEquals(bridge.text, modelReferenceText(ComposerDraft(bridge.text, bridge.references, bridge.text.length)).first)
  }
}
