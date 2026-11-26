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
