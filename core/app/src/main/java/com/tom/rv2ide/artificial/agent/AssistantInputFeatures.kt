/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.databinding.LayoutAiAssistantBinding
import com.tom.rv2ide.resources.R.string
import java.io.File

/**
 * 悬浮助手输入区的三项附加功能：模型槽位切换、上下文附件、推理强度。
 *
 * <p><b>为什么单独拆一个类而不是继续往 [FloatingAssistantView] 里塞</b>：那个类已经 1400 行，
 * 且它的主体职责是「消息列表 + 运行循环」。这三项是纯输入区的局部状态（槽位、待发附件、
 * 推理强度），与消息流无关；拆出来后它们可以独立演进，也让宿主类不必再多背一份
 * ActivityResult 的注册与生命周期处理。
 *
 * <p><b>与宿主的关系</b>：本类不持有 orchestrator，也不发请求。它在发送时被
 * [FloatingAssistantView.sendFromInput] 查询一次，把附件以文本形式拼进用户消息。
 *
 * <p><b>宿主必须是 [FragmentActivity]</b>：文件/图片选择走 `ActivityResultLauncher`，
 * 而它只在 ComponentActivity 及其子类上可用。两个宿主（MainFragment 与 BaseEditorActivity）
 * 都满足；取不到时附件按钮会给出提示而不是崩溃。
 */
class AssistantInputFeatures(
    private val context: Context,
    private val binding: LayoutAiAssistantBinding,
) {

  // ---- 附件 ----

  /**
   * 待发送的附件。
   *
   * <p>存 URI 而不是 File：用户可能从相册选一张 `content://` 图片，它没有可用的文件系统
   * 路径（或路径在应用沙箱外读不到）。发送时统一转成绝对路径文本；拿不到真实路径的
   * （相册图片）退化为 URI 字符串——模型至少能知道「有一张图」，比整条丢掉好。
   */
  private val attachments = mutableListOf<Attachment>()

  private data class Attachment(val uri: Uri, val name: String, val isImage: Boolean)

  private var filePicker: ActivityResultLauncher<Intent>? = null
  private var imagePicker: ActivityResultLauncher<Intent>? = null

  init {
    registerPickers()
    binding.assistantAttach.setOnClickListener { showAttachSheet() }
    // 默认值必须是「自动」：用户没表态时应该由协议层的策略决定，
    // 而不是我们替他固定成某一档。
    applyReasoningEffort(reasoningPrefs().getString(KEY_REASONING, DEFAULT_REASONING))
  }

  /**
   * 注册两个选择器。
   *
   * <p>必须用 `registerForActivityResult` 而不是直接 `startActivityForResult`：
   * 后者在 Android 10+ 上拿到的是被系统过滤过的结果，且 Fragment 宿主下
   * `onActivityResult` 的回调路径依赖已废弃的 API。
   */
  private fun registerPickers() {
    val activity = context as? FragmentActivity ?: return
    filePicker =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()) { result ->
          collectResult(result, isImage = false)
        }
    imagePicker =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()) { result ->
          collectResult(result, isImage = true)
        }
  }

  /**
   * 收集选择结果。
   *
   * <p>必须同时看 `clipData` 与 `data`：多选时系统只把结果放进 `clipData`，
   * `data` 为 null——只读 `data` 会让「选了两个文件」变成「什么都没选」，
   * 而且不报错，用户只会觉得按钮坏了。
   */
  private fun collectResult(
      result: androidx.activity.result.ActivityResult,
      isImage: Boolean,
  ) {
    if (result.resultCode != AppCompatActivity.RESULT_OK) {
      return
    }
    val data = result.data
    val clip = data?.clipData
    if (clip != null) {
      for (i in 0 until clip.itemCount) {
        clip.getItemAt(i).uri?.let { addAttachment(it, isImage) }
      }
      return
    }
    data?.data?.let { addAttachment(it, isImage) }
  }

  /**
   * 附件类型选择。
   *
   * <p>用对话框二选一而不是让用户先点 `+` 再猜：Android 没有一个能同时表达
   * 「项目内文件」与「相册图片」的系统选择器——`ACTION_OPEN_DOCUMENT` 能选到图片文件，
   * 但相册里的图片在多数设备上不在文档树里；`ACTION_GET_CONTENT` 反之。
   * 所以必须由用户先表明意图。
   */
  private fun showAttachSheet() {
    if (filePicker == null) {
      toastOrTrace(context.getString(string.ai_assistant_attach_no_picker))
      return
    }
    val labels =
        arrayOf(
            context.getString(string.ai_assistant_attach_file),
            context.getString(string.ai_assistant_attach_image),
        )
    MaterialAlertDialogBuilder(context)
        .setTitle(string.ai_assistant_attach_title)
        .setItems(labels) { _, which ->
          if (which == 0) {
            openFilePicker()
          } else {
            openImagePicker()
          }
        }
        .show()
  }

  private fun openFilePicker() {
    // ACTION_OPEN_DOCUMENT 而不是 ACTION_GET_CONTENT：前者给出的 URI 带持久读权限，
    // 且不会因为「选完即失效」导致发送时读不到文件。
    val intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "*/*"
          // 多选：一次挑几个相关文件是常见需求（对比两个实现文件）。
          putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    launchSafely(filePicker, intent, string.ai_assistant_attach_no_picker)
  }

  private fun openImagePicker() {
    val intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "image/*"
          putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    launchSafely(imagePicker, intent, string.ai_assistant_attach_no_picker)
  }

  /** 启动选择器；设备上没有对应 Activity 时给出提示而不是抛 ActivityNotFoundException。 */
  private fun launchSafely(
      launcher: ActivityResultLauncher<Intent>?,
      intent: Intent,
      errorStringRes: Int,
  ) {
    try {
      launcher?.launch(intent)
    } catch (e: android.content.ActivityNotFoundException) {
      toastOrTrace(context.getString(errorStringRes))
    }
  }

  /** 记录选中的 URI 并刷新标签行。名称解析失败不阻断——退化成 URI 尾段即可。 */
  private fun addAttachment(uri: Uri, isImage: Boolean) {
    val name = displayName(uri)
    if (attachments.any { it.uri == uri }) {
      return
    }
    attachments.add(Attachment(uri, name, isImage))
    renderAttachments()
  }

  /** 从 ContentResolver 取显示名；取不到时退回 URI 的最后一段。 */
  private fun displayName(uri: Uri): String {
    try {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
          val value = cursor.getString(index)
          if (!value.isNullOrBlank()) {
            return value
          }
        }
      }
    } catch (e: Exception) {
      // 查询失败按「取不到名字」处理，下面的兜底仍然可用。
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
  }

  /** 重建标签行。条目数是个位数，全量重建比 diff 更简单且不会错。 */
  private fun renderAttachments() {
    val group = binding.assistantAttachList
    group.removeAllViews()
    binding.assistantAttachScroll.isVisible = attachments.isNotEmpty()

    for (attachment in attachments) {
      val chip =
          Chip(context, null, com.google.android.material.R.style.Widget_Material3_Chip_Input).apply {
            text = attachment.name
            isCloseIconVisible = true
            // 显式给关闭图标：Chip 的默认 close icon 来自主题，部分主题下为 null，
            // 此时 isCloseIconVisible=true 会得到一个「点了没反应」的空洞。
            closeIcon = androidx.core.content.ContextCompat.getDrawable(
                context, com.tom.rv2ide.R.drawable.ic_close)
            setOnCloseIconClickListener {
              attachments.remove(attachment)
              renderAttachments()
            }
          }
      group.addView(chip)
    }
  }

  /**
   * 本次请求要附带的上下文文本；没有附件时返回空串。
   *
   * <p><b>为什么是「把路径写进消息」而不是真正的多模态输入</b>：协议层已经有
   * `ImageInputPayload` 与 `ModelMessage.rawInputJson`，OpenAI 兼容与 Anthropic 两条
   * 序列化路径都会把它转成 image_url / image block，但**入口是 AgentSession 内部构造的
   * `new UserModelMessage(userRequest)`**——没有任何参数能让 app 层把 rawInputJson 传进去。
   * 因此图片通道目前只差这一处接线（需要主 agent 在 `AgentSession` / `AgentOrchestrator.run`
   * 上开口子），在那之前这里退化为文本：把绝对路径交给模型，让它用 file 工具自己去读。
   */
  fun attachmentContext(): String {
    if (attachments.isEmpty()) {
      return ""
    }
    return attachments.joinToString(separator = "\n", prefix = "\n\n") { describe(it) }
  }

  /**
   * 把附件描述成一行。
   *
   * <p>优先给**绝对路径**：agent 的 file 工具按工作区内的路径读取，给 URI 它用不了。
   * 相册图片（content://）拿不到真实路径，此时退回 URI 字符串——至少让模型知道
   * 用户附了一张图，而不是完全丢失这个信息。
   */
  private fun describe(attachment: Attachment): String {
    val path = localPathOf(attachment.uri)
    val target = path ?: attachment.uri.toString()
    val marker = context.getString(string.ai_assistant_attachment_marker, target)
    return if (attachment.isImage && path == null) {
      // 读不到路径的图片明确标注，避免模型以为那是一个能直接打开的文件。
      marker + " (image, path unavailable)"
    } else {
      marker
    }
  }

  /**
   * 尝试把 content:// URI 还原成文件系统绝对路径。
   *
   * <p>只在 `file://` 或外部存储的 `content://` 上可能成功；失败返回 null，
   * 由调用方退化为 URI 文本。刻意不在这里读文件内容——那是发送时才需要的事，
   * 提前读会白白占内存（图片可能几 MB）。
   */
  private fun localPathOf(uri: Uri): String? {
    if (uri.scheme == "file") {
      return uri.path
    }
    if (uri.scheme != "content") {
      return null
    }
    val id = uri.lastPathSegment ?: return null
    // 外部存储的文档 id 形如 "primary:Download/a.png"，冒号后才是真实相对路径。
    val relative = id.substringAfter(':', missingDelimiterValue = "")
    if (relative.isEmpty()) {
      return null
    }
    val external = android.os.Environment.getExternalStorageDirectory()
    val candidate = File(external, relative)
    return if (candidate.exists()) candidate.absolutePath else null
  }

  /** 发送后清空待发附件。 */
  fun clearAttachments() {
    if (attachments.isEmpty()) {
      return
    }
    attachments.clear()
    renderAttachments()
  }

  /** 当前是否有待发附件（供宿主判断「空文本 + 有附件」也算有效请求）。 */
  fun hasAttachments(): Boolean = attachments.isNotEmpty()

  /** 附件的展示名，用于把「只附了文件没打字」的请求显示成有意义的消息。 */
  fun attachmentNames(): String = attachments.joinToString(", ") { it.name }

  // ---- 模型槽位 ----

  /**
   * 刷新槽位标签。
   *
   * <p>只在**非空槽位多于一个**时显示：空槽位的语义是「与主模型相同」，
   * 列出来等于把同一个模型重复四遍；只有一个非空槽位时切换不会有任何变化，
   * 显示一个点了没反应的入口比不显示更糟。
   */
  fun refreshSlotLabel() {
    val slots = nonEmptySlots()
    val label = binding.assistantSlotLabel
    if (slots.size <= 1) {
      label.isVisible = false
      return
    }
    label.isVisible = true
    // 显示**当前**槽位，不是列表里的第一个——否则模型在 opus 时标签仍写「均衡」，
    // 用户看到的是错误的当前状态。
    label.text = context.getString(slotLabelRes(currentSlot()))
  }

  /**
   * 当前选中的槽位。请求路径读它来决定用哪个模型。
   *
   * <p>槽位可能已被清空（用户在设置里删了那个模型），此时回退到主模型——
   * 否则请求会带一个空模型名发出去。
   */
  fun currentSlot(): String {
    val saved = reasoningPrefs().getString(KEY_SLOT, null)
    if (saved == null || !ProviderConfig.SLOT_ORDER.contains(saved)) {
      return ProviderConfig.SLOT_MAIN
    }
    val record = ProviderConfigStore(context).find(Agents(context).getProvider())
    if (record == null || record.getSlotModel(saved).isBlank()) {
      return ProviderConfig.SLOT_MAIN
    }
    return saved
  }

  private fun setCurrentSlot(slot: String) {
    reasoningPrefs().edit().putString(KEY_SLOT, slot).apply()
  }

  /**
   * 在非空槽位之间循环切换。
   *
   * <p>用「循环」而不是弹菜单：非空槽位最多 4 个，弹菜单要多一次点击、多一层遮罩，
   * 而循环切换的代价上限是 3 次点击且每次都有即时反馈。槽位名会显示在标签上，
   * 用户始终知道自己在哪一档。
   */
  fun cycleSlot() {
    val slots = nonEmptySlots()
    if (slots.size <= 1) {
      return
    }
    val agents = Agents(context)
    val provider = agents.getProvider()
    val current = agents.getAgent()
    val index = slots.indexOfFirst { it.second == current }
    // 当前模型不在槽位列表里（用户在别处手改过）时 index 为 -1，此时从第一个槽位开始，
    // 而不是停在原地——用户点这个按钮就是要换模型，不响应等于按钮坏了。
    val next = slots[(index + 1).mod(slots.size)]

    agents.setAgent(next.second)
    // setAgent 会按模型名反查服务商并覆写 provider（见 Agents.setAgent 的注释）。
    // 槽位是**当前服务商**的属性，若某个槽位填了别家的模型名，反查会把服务商一起换掉，
    // 于是「切槽位」变成了「切服务商」——所以写完模型后把服务商按回原值。
    agents.setProvider(provider)
    // 记录当前槽位。请求路径读槽位而不是模型名：模型名可能被手改、
    // 也可能两个槽位填了同一个模型，只有槽位能唯一确定用户选的是哪一档。
    setCurrentSlot(next.first)
    refreshSlotLabel()
    toastOrTrace(
        context.getString(
            string.ai_assistant_slot_switched,
            context.getString(slotLabelRes(next.first)),
            next.second,
        ))
  }

  /**
   * 当前服务商的非空槽位（槽位名 → 模型名，已剥离上下文后缀）。
   *
   * <p>用 `getSlotModel` 而不是 `resolveSlot`：后者会把空槽位回退成主模型，
   * 于是「四个槽位全空只剩 main」也会被当成四个非空槽位，循环切换就变成了
   * 四次点击回到原点而模型名始终不变。
   */
  private fun nonEmptySlots(): List<Pair<String, String>> {
    val providerId = Agents(context).getProvider()
    val record = AgentModelConfigs.recordFor(providerId) ?: return emptyList()
    return ProviderConfig.SLOT_ORDER.mapNotNull { slot ->
      val model = record.getSlotModel(slot)
      if (model.isBlank()) null else slot to ContextSizeParser.stripSuffix(model)
    }
  }

  /** 槽位名 → 字符串资源。与「服务商管理」界面用同一批标签，避免两处叫法不一致。 */
  private fun slotLabelRes(slot: String): Int =
      when (slot) {
        ProviderConfig.SLOT_HAIKU -> string.ai_agent_provider_slot_haiku
        ProviderConfig.SLOT_SONNET -> string.ai_agent_provider_slot_sonnet
        ProviderConfig.SLOT_OPUS -> string.ai_agent_provider_slot_opus
        else -> string.ai_agent_provider_slot_main
      }

  // ---- 推理强度 ----

  /**
   * 弹推理强度选择。
   *
   * <p>用单选列表而不是 chip 组：四个选项的中文标签（自动/低/中/高）并排约 200dp，
   * 贴边形态下面板只有 260dp，会挤掉左侧标签；而且当前值已经写在行尾，
   * 不需要靠选中态再表达一遍。
   */
  fun showReasoningPicker() {
    val values =
        arrayOf(
            com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_AUTO,
            com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_LOW,
            com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_MEDIUM,
            com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_HIGH,
        )
    val labels =
        arrayOf(
            context.getString(string.ai_assistant_reasoning_auto),
            context.getString(string.ai_assistant_reasoning_low),
            context.getString(string.ai_assistant_reasoning_medium),
            context.getString(string.ai_assistant_reasoning_high),
        )
    val current = reasoningPrefs().getString(KEY_REASONING, DEFAULT_REASONING)
    val checked = values.indexOf(current).coerceAtLeast(0)

    MaterialAlertDialogBuilder(context)
        .setTitle(string.ai_assistant_reasoning_title)
        .setSingleChoiceItems(labels, checked) { dialog, which ->
          setReasoningEffort(values[which])
          dialog.dismiss()
        }
        .show()
  }

  /**
   * 写入推理强度并刷新行尾文案。
   *
   * <p>写到默认偏好存储里：这是**跨会话的偏好**，不该随一次运行消失，
   * 也不该跟某条消息绑定——用户调过一次「高」之后，下一次提问仍然希望是高。
   *
   * <p><b>协议层支持情况</b>：`ModelRequestOptions` 已有 `reasoningEffort` 字段，
   * 各家的 `ReasoningRequestStrategy`（OpenAI 的 `reasoning_effort`、DeepSeek 的
   * `thinking`、Anthropic 的 thinking budget 等）都会读它。但 `AgentSession.requestModel`
   * 目前硬编码 `new ModelRequestOptions("", false, nativeToolList)`——空串会被
   * `AiBehaviorSettings.normalizeReasoningEffort` 归一成 `medium`。也就是说这个值现在
   * **写进去了但还没被使用**，需要主 agent 在 AgentSession/Orchestrator 上把
   * `reasoningEffort` 透传进去才会真正生效。
   */
  fun setReasoningEffort(effort: String) {
    reasoningPrefs().edit().putString(KEY_REASONING, effort).apply()
    applyReasoningEffort(effort)
  }

  /** 把偏好值渲染到输入区的推理行上。 */
  private fun applyReasoningEffort(effort: String?) {
    val value = effort ?: DEFAULT_REASONING
    binding.assistantReasoningValue.text = context.getString(reasoningLabelRes(value))
    binding.assistantReasoningRow.setOnClickListener { showReasoningPicker() }
  }

  /** 推理强度值 → 字符串资源。 */
  private fun reasoningLabelRes(effort: String): Int =
      when (effort) {
        com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_LOW ->
            string.ai_assistant_reasoning_low
        com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_MEDIUM ->
            string.ai_assistant_reasoning_medium
        com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_HIGH ->
            string.ai_assistant_reasoning_high
        else -> string.ai_assistant_reasoning_auto
      }

  /** 当前推理强度（供宿主在发送时读取，目前仅用于日志/调试）。 */
  fun reasoningEffort(): String =
      reasoningPrefs().getString(KEY_REASONING, DEFAULT_REASONING) ?: DEFAULT_REASONING

  private fun reasoningPrefs() =
      androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

  /**
   * 面板里没有 Toast 的位置，统一走对话流的「过程」消息。
   *
   * <p>接受已渲染好的文本而不是资源 id：槽位切换的提示要带两个参数
   * （槽位名 + 模型名），在调用点用 `getString(res, args)` 渲染比在这里
   * 收 vararg 更清楚。
   */
  private fun toastOrTrace(message: CharSequence) {
    onNotice?.invoke(message.toString())
  }

  /** 提示出口，由宿主注入（写进消息列表的 TRACE 行）。 */
  var onNotice: ((String) -> Unit)? = null

  companion object {
    /** 推理强度的偏好键。默认「自动」，与协议层的中性语义一致。 */
    private const val KEY_REASONING = "ai_assistant_reasoning_effort"

    /** 当前选中的模型槽位。与推理强度存在同一份偏好里，都是输入区的会话级偏好。 */
    private const val KEY_SLOT = "ai_assistant_slot"
    private const val DEFAULT_REASONING =
        com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_AUTO
  }
}
