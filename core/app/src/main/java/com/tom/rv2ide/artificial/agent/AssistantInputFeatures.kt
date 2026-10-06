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
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.artificial.agent.host.AssistantHost
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
 * <p><b>宿主能力由 [AssistantHost.attachments] 提供</b>：附件选择需要「发起一个系统
 * 选择器并异步拿回结果」，这在 Activity 与 Service（应用外悬浮）两种宿主下机制不同
 * ——Activity 走 `registerForActivityResult`，Service 走蹦床 Activity。此前本类直接
 * `context as? FragmentActivity`，在 Service 下取不到便**静默失效**（按钮点了没反应、
 * 还不报错）；改为从 host 取后，两种宿主都能工作，失败也有明确的 `null` 回调。
 */
class AssistantInputFeatures(
    private val host: AssistantHost,
    private val binding: LayoutAiAssistantBinding,
) {

  /** 视图与选择器共用的 Context。从 host 取，保证与宿主一致。 */
  private val context: Context = host.context

  // ---- 附件 ----

  /**
   * 待发送的附件。
   *
   * <p>存 URI 而不是 File：用户可能从相册选一张 `content://` 图片，它没有可用的文件系统
   * 路径（或路径在应用沙箱外读不到）。发送时统一转成绝对路径文本；拿不到真实路径的
   * （相册图片）退化为 URI 字符串——模型至少能知道「有一张图」，比整条丢掉好。
   */
  private val attachments = mutableListOf<Attachment>()

  /**
   * 一个待发附件。
   *
   * @param bytes 选中时**当场读出**的内容（仅图片）。见 [addAttachment] 的说明——
   *   Photo Picker 的 URI 权限只在选择回调期间有效，等到发送时再读会拿到 null。
   */
  private data class Attachment(
      val uri: Uri,
      val name: String,
      val isImage: Boolean,
      val bytes: ByteArray? = null,
  )

  /**
   * 附件按钮是否可用。
   *
   * <p>取 `+` 菜单里那一项是否出现。任何宿主都能提供 [AssistantHost.attachments]
   * （Activity 与 Service 各有实现），因此这里恒为 true；保留这个判断是为了让
   * 「菜单项存在」与「能发起选择」两件事在语义上仍然分离——将来若有宿主明确不支持
   * 附件，改这里即可，不必动菜单装配代码。
   */
  private val attachmentsAvailable: Boolean = true

  init {
    // 默认值必须是「自动」：用户没表态时应该由协议层的策略决定，
    // 而不是我们替他固定成某一档。
    applyReasoningEffort(reasoningPrefs().getString(KEY_REASONING, DEFAULT_REASONING))
    binding.assistantToolbarAdd.setOnClickListener { showAddMenu() }
  }

  /**
   * `+` 菜单：附件与推理强度。
   *
   * <p><b>为什么附件二选一而不是让用户先点 `+` 再猜</b>：Android 没有一个能同时表达
   * 「项目内文件」与「相册图片」的系统选择器——`ACTION_OPEN_DOCUMENT` 能选到图片文件，
   * 但相册里的图片在多数设备上不在文档树里；`ACTION_GET_CONTENT` 反之。
   * 所以必须由用户先表明意图。
   *
   * <p><b>为什么推理强度也收在这里</b>：工具条上放不下它。四个中文标签（自动/低/中/高）
   * 并排约 200dp，而工具条在贴边形态下整行只有约 236dp 可用——它和模型标签、圆环、
   * 发送按钮是互斥的。原先它独占输入区上方一整行，也是同样的空间问题：
   * 一个一周改一次的设置占着每天都要看的输入框上方 32dp。
   */
  private fun showAddMenu() {
    // 槽位切换只在有得切时才列出来。菜单项本身要稳定可预期，但一个点了什么都不做的
    // 项比少一项更糟——用户会反复点它确认自己没看错。
    val slotSwitchable = hasSwitchableSlots()
    val items = mutableListOf<Pair<String, () -> Unit>>()
    if (attachmentsAvailable) {
      items.add(context.getString(string.ai_assistant_toolbar_attach) to { showAttachSheet() })
    }
    items.add(
        context.getString(
            string.ai_assistant_toolbar_reasoning_value,
            context.getString(
                reasoningLabelRes(
                    reasoningPrefs().getString(KEY_REASONING, DEFAULT_REASONING)
                        ?: DEFAULT_REASONING)),
        ) to { showReasoningPicker() })
    if (slotSwitchable) {
      items.add(
          context.getString(
              string.ai_assistant_toolbar_slot,
              currentSlotLabel(),
              Agents(context).getAgent(),
          ) to { cycleSlot() })
    }

    MaterialAlertDialogBuilder(context)
        .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
        .show()
  }

  /**
   * 附件类型选择（文件 / 图片）。
   *
   * <p>从 `+` 菜单进来，所以这里不再套一层标题——两层同样标题的对话框会让用户
   * 以为自己点重复了。
   */
  private fun showAttachSheet() {
    val labels =
        arrayOf(
            context.getString(string.ai_assistant_attach_file),
            context.getString(string.ai_assistant_attach_image),
        )
    MaterialAlertDialogBuilder(context)
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
    // 真正的 Intent 构造交给宿主（Activity 与 Service 走不同机制）。这里只负责
    // 「选完之后把结果并入附件」——契约是每个 URI 回调一次、取消/失败回调一次 null。
    host.attachments.pickFile { uri -> addAttachment(uri, isImage = false) }
  }

  private fun openImagePicker() {
    host.attachments.pickImage { uri -> addAttachment(uri, isImage = true) }
  }

  /**
   * 记录选中的 URI 并刷新标签行。名称解析失败不阻断——退化成 URI 尾段即可。
   *
   * <p>[uri] 为 null 表示用户取消、没选任何项、设备上没有可处理该 Intent 的 Activity，
   * 或宿主无法解析结果（见 `AssistantAttachments` 契约）。此时**什么都不做**：
   * 静默忽略是「取消」的正确表现，弹提示反而像出错了。
   *
   * <p><b>图片必须在这里当场读成字节</b>：Android 13+ 把 `ACTION_GET_CONTENT` 重定向到
   * 系统 Photo Picker，返回 `content://media/picker_get_content/...`，其读权限**只在本次
   * 选择回调期间有效**。若只记住 URI、等用户点「发送」时才读，权限已失效 →
   * `openInputStream` 返回 null → 图片降级成一行文本路径，模型看不到图
   * （实测：模型回复「I cannot see the images / path unavailable」）。
   *
   * <p>这与参考项目 cc-haha 的做法一致：它的 `LocalAttachment` 在下载/选择时就保留
   * `buffer`，由调用方决定后续用 base64 还是路径——而不是把「能不能读」推迟到使用时刻。
   *
   * <p>读失败或超限时不缓存 bytes（保留 URI 以便走文本降级路径），不阻断附件添加：
   * 用户至少还能看到「附了这张图」这个事实。
   */
  private fun addAttachment(uri: Uri?, isImage: Boolean) {
    if (uri == null) {
      return
    }
    val name = displayName(uri)
    if (attachments.any { it.uri == uri }) {
      return
    }
    // 非图片（任意类型文件）不需要预读：它们本就以路径形式交给模型用 file 工具读。
    val bytes = if (isImage) readImageBytes(uri) else null
    attachments.add(Attachment(uri, name, isImage, bytes))
    renderAttachments()
  }

  /**
   * 立刻读取图片字节；失败或超出体积上限时返回 null。
   *
   * <p>在这里判体积而不是留到发送时：超限的图当场就知道不该进 payload，
   * 缓存它只会白占内存（一张 20MB 的图在附件栏里挂几分钟）。
   */
  private fun readImageBytes(uri: Uri): ByteArray? {
    return try {
      val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) null else bytes
    } catch (e: Exception) {
      // 权限失效、文件被删、流读取异常：都退回「无 bytes」，走文本降级。
      null
    }
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
   * 构建多模态输入（图片）。
   *
   * <p>协议层已有 {@code ImageInputPayload} 与 {@code ModelMessage.rawInputJson}，
   * OpenAI 兼容与 Anthropic 两条序列化路径都会把它转成 image_url / image block。
   * 这里负责把用户选的图片读成 base64 并打包。
   *
   * <p><b>只取第一张图</b>：协议层的 payload 是**单个**图片的格式（一个 mime_type +
   * 一份 base64）。多图需要协议层支持数组，那是独立改动。当前取第一张并如实告知其余
   * 图片走文本路径，而不是静默丢弃。
   *
   * <p><b>读不到内容时返回 null</b>：让调用方退化为文本路径（把路径交给模型），
   * 而不是发一个空 payload 让服务端报错——后者的错误信息对用户毫无意义。
   */
  fun imageRawInputJson(prompt: String): String? {
    val image = attachments.firstOrNull { it.isImage } ?: return null
    // 优先用选择时缓存的字节。**不再在这里 openInputStream**：Photo Picker 的 URI
    // 权限只在选择回调期间有效，此刻（用户点发送）通常已失效，现场读必然拿到 null
    // ——这正是「AI 说看不到图片」的根因（见 addAttachment 的说明）。
    //
    // 缓存为空（读失败/超限/旧版本添加的附件）时退回现场读一次：对 `ACTION_OPEN_DOCUMENT`
    // 之类带持久权限的 URI 仍然有效，作为兜底而不是主路径。
    val bytes = image.bytes ?: readImageBytes(image.uri) ?: return null
    if (bytes.isEmpty()) {
      return null
    }
    // 图片可能有几 MB，超过上限就退化为文本路径——base64 会让体积再涨 1/3，
    // 而多数服务商对单次请求体有硬限制，超限时报错比降级更难排查。
    if (bytes.size > MAX_IMAGE_BYTES) {
      return null
    }
    val mime =
        context.contentResolver.getType(image.uri)
            ?: if (image.name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
    return try {
      com.tom.rv2ide.ai.protocol.ImageInputPayload.rawInputJson(
          prompt,
          mime,
          android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
      )
    } catch (e: Exception) {
      null
    }
  }

  /** 是否有可用的图片附件（决定发送时是否走多模态路径）。 */
  fun hasImageAttachment(): Boolean = attachments.any { it.isImage }

  /**
   * 本次请求要附带的上下文文本；没有附件时返回空串。
   *
   * <p>图片走 {@link #imageRawInputJson} 的多模态通道；这里只处理**非图片**附件，
   * 以及那些无法作为图片发送的情况（读不到内容、超出体积上限、多图里的第 2 张起）——
   * 把它们以路径形式交给模型，让它用 file 工具自己读，总好过静默丢弃。
   */
  fun attachmentContext(): String {
    if (attachments.isEmpty()) {
      return ""
    }
    return attachments.joinToString(separator = "\n", prefix = "\n\n") { describe(it) }
  }

  /**
   * 同 [attachmentContext]，但排除**第一张图片**——它已由多模态 payload 承载。
   *
   * <p>不排除的话模型会同时收到「图片内容」与「图片路径」两份信息：可能重复处理，
   * 也可能放着图片不用、改去读文件（而相册图片的路径本来就拿不到）。
   *
   * <p>只排除第一张：[imageRawInputJson] 也只打包第一张，两者必须一致，
   * 否则剩下的图片既不在 payload 里也不在文本里，等于静默丢失。
   */
  fun attachmentContextExcludingImage(): String {
    val rest = mutableListOf<Attachment>()
    rest.addAll(attachments.filter { !it.isImage })
    // 第 2 张起的图片仍走文本：payload 只装得下一张，丢掉它们不如让模型知道它们存在。
    rest.addAll(attachments.filter { it.isImage }.drop(1))
    if (rest.isEmpty()) {
      return ""
    }
    return rest.joinToString(separator = "\n", prefix = "\n\n") { describe(it) }
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
   * 是否有可切换的槽位（非空槽位多于一个）。
   *
   * <p>空槽位的语义是「与主模型相同」，列出来等于把同一个模型重复四遍；
   * 只有一个非空槽位时切换不会有任何变化，显示一个点了没反应的入口比不显示更糟。
   * 因此 `+` 菜单里那一项只在为 true 时出现。
   */
  fun hasSwitchableSlots(): Boolean = nonEmptySlots().size > 1

  /** 当前槽位的显示名（供 `+` 菜单文案）。 */
  fun currentSlotLabel(): String = context.getString(slotLabelRes(currentSlot()))

  /**
   * 切到下一个非空槽位。
   *
   * <p>由 `+` 菜单里那一项调用（见 [showAddMenu]）。原先它是工具条上一个独立小标签，
   * 但工具条在贴边形态下整行只有约 236dp，放不下它；而槽位切换是低频操作
   * （配好四个槽位后偶尔换一档），收进菜单的代价可以接受。
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
    // 模型名变了，工具条上的标签要跟着走。
    onModelChanged?.invoke()
    toastOrTrace(
        context.getString(
            string.ai_assistant_slot_switched,
            context.getString(slotLabelRes(next.first)),
            next.second,
        ))
  }

  /**
   * 模型（或服务商）变化后的回调。
   *
   * <p>由宿主注入，用来刷新工具条上的模型标签。槽位切换发生在**本类内部**，
   * 而标签由宿主渲染——没有这个回调，切完槽位标签会停在上一个模型名上。
   */
  var onModelChanged: (() -> Unit)? = null

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

  /**
   * 把偏好值落到 `+` 菜单的文案上。
   *
   * <p>当前值不再单独显示——它只出现在菜单里那一行「推理：高」。工具条没有位置放它，
   * 而推理强度是**低频**设置（一次运行通常不改），把常驻空间让给模型名与上下文圆环
   * 这两个每轮都要看的信息。
   */
  private fun applyReasoningEffort(effort: String?) {
    val value = effort ?: DEFAULT_REASONING
    binding.assistantToolbarAdd.contentDescription =
        context.getString(
            string.ai_assistant_toolbar_reasoning_value,
            context.getString(reasoningLabelRes(value)),
        )
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

    /**
     * 单张图片的体积上限。超过就退化为文本路径。
     *
     * <p>取 4MB：base64 后约 5.3MB，多数服务商的单请求上限在 5-10MB 之间，
     * 留出提示词与历史的余量。
     */
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val DEFAULT_REASONING =
        com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_AUTO
  }
}
