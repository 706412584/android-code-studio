package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.ai.protocol.ReferenceMeta
import com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerDraft
import com.tom.rv2ide.artificial.agent.compose.components.independent.expandQuoteReferences
import com.tom.rv2ide.artificial.agent.compose.components.independent.liveReferences
import com.tom.rv2ide.adapters.AssistantMessageAdapter

/** 发送、排队及队列编辑共同使用的不可变投影；不读取变化中的输入框。 */
internal data class AssistantRequestSnapshot(
    val displayText: String,
    val modelText: String,
    val references: List<ReferenceMeta>,
    val rawInputJson: String?,
    val attachments: List<AssistantMessageAdapter.MessageAttachment>,
)

/** 图片直接取队列原始载荷；没有落盘路径的附件也必须保留。 */
internal fun restorePendingAttachments(
    values: List<com.tom.rv2ide.ai.protocol.AttachmentMeta>,
    rawInputJson: String?,
): List<com.tom.rv2ide.artificial.agent.compose.compat.PendingUploadAttachment> {
  val payload = com.tom.rv2ide.ai.protocol.ImageInputPayload.fromRawInputJson(rawInputJson)
  val array = if (payload != null) org.json.JSONObject(rawInputJson!!).optJSONArray("images") else null
  val imageIndices = values.indices.filter { values[it].isImage }
  val images = mutableMapOf<Int, com.tom.rv2ide.artificial.agent.compose.compat.AgentImage>()
  payload?.images?.forEachIndexed { index, part ->
    val item = array?.optJSONObject(index)
    val target = if (item?.has("attachment_index") == true) item.optInt("attachment_index", -1)
        else if (payload.images.size == imageIndices.size) imageIndices[index] else -1
    if (target in values.indices && values[target].isImage) {
      images[target] = com.tom.rv2ide.artificial.agent.compose.compat.AgentImage(
          part.mimeType, part.dataBase64, values[target].localPath)
    }
  }
  return values.mapIndexed { index, value ->
    com.tom.rv2ide.artificial.agent.compose.compat.PendingUploadAttachment(
        value.fileName, value.localPath.ifBlank { value.fileName }, value.localPath,
        value.mimeType.ifBlank { if (value.isImage) "image/*" else "application/octet-stream" },
        value.sizeBytes, images[index])
  }
}

/**
 * 结合 [modelReferenceText] 的结果给出**显示文本**与引用元数据。
 *
 * <p>返回给调用方的三段：
 * - displayText：气泡要显示的正文，**保留 chip token**（形如「引用 助手回复」）而不展开成
 *   多行 `> 引用自 …`。气泡据此把 quote 区间渲染成卡片（见消息气泡的渲染），只留简短摘要。
 * - modelText：发给模型的文本。这里才把 chip 展开成完整引用块——模型需要看到被引用原文，
 *   用户界面则需要紧凑。
 * - references：落盘/回放用的引用元数据，区间对齐到 **displayText**，含 quote 条目，
 *   供气泡重建卡片。
 */
internal fun composeRequestTexts(
    draft: ComposerDraft,
): Triple<String, String, List<ReferenceMeta>> {
  val live = liveReferences(draft)
  // 显示文本就是输入框里的原文：quote 以单行 token 存在，气泡会把它的区间换成卡片。
  val displayText = draft.text
  val references = live.map {
    ReferenceMeta(it.kind, it.id, it.label, it.path, it.start, it.end)
  }
  // 结构化引用指令只认「让模型去调某工具」的意图（会话/文件/智能体/技能/插件）。
  // quote 是引文上下文本身，不放结构化指令。
  val structured = live.filter { it.kind != "quote" }
  // 模型文本展开 quote chip 成完整 `> 引用自 …` 块。
  val modelBase = expandQuoteReferences(draft)
  if (structured.isEmpty()) return Triple(displayText, modelBase, references)
  val instructions = structured.joinToString("\n") {
    val target = org.json.JSONObject.quote(it.id)
    when (it.kind) {
      "session" -> "会话 ${org.json.JSONObject.quote(it.label)}：用 conversation_read 只读会话 ID=$target；不要自动启动或发送消息。"
      "file" -> "项目文件：用文件工具按需读取 path=${org.json.JSONObject.quote(it.path)}。"
      "agent" -> "用户选择了智能体工具 $target；需要派遣时使用该真实工具名，不另建同名代理。"
      "skill" -> "先用 skill 工具加载 name=$target 的技能，再处理本次任务。"
      "plugin" -> "用户选择了插件 ${org.json.JSONObject.quote(it.label)}（配置 ID=$target）；用 tool_search 查找其可用工具，不安装或更改配置。"
      else -> ""
    }
  }
  val modelText = modelBase + "\n\n[用户明确选择的结构化引用]\n" + instructions
  return Triple(displayText, modelText, references)
}

internal fun modelReferenceText(draft: ComposerDraft): Pair<String, List<ReferenceMeta>> {
  val (_, modelText, references) = composeRequestTexts(draft)
  return modelText to references
}
