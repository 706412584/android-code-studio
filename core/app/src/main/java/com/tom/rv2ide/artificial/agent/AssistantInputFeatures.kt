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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

  /** 宿主注入的输入栏桥；XML 退役后附件的 Compose 投影写进它。 */
  internal var owner: FloatingAssistantView? = null

  /**
   * 读图片内容用的作用域。
   *
   * <p>绑在宿主的 lifecycleScope 上：视图销毁时协程随之取消，不会往已 detach 的
   * 控件里写数据。用 IO 调度器——读图是磁盘 + ContentProvider 往返，几 MB 的图
   * 放主线程会卡住输入框。
   */
  private val ioScope: kotlinx.coroutines.CoroutineScope =
      kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
   * @param localPath 图片落盘后的绝对路径（`filesDir/ai/attachments/<name>`）。
   *   相册图片的 URI 拿不到可用路径，而消息气泡的缩略图与全屏查看器都需要一个
   *   能反复读取的来源——所以选图时就把字节写一份到应用私有目录。
   */
  private data class Attachment(
      val uri: Uri,
      val name: String,
      val isImage: Boolean,
      val bytes: ByteArray? = null,
      val localPath: String? = null,
  )

  /**
   * 一条已发送附件（脱离待发状态后的只读投影）。
   *
   * <p>不带 base64，只带落盘路径：气泡缩略图按需从磁盘解码，消息条目因此保持轻量。
   * 落盘失败时 [localPath] 为空串，UI 退化为文件图标而不是破图。
   */
  data class AttachedFile(
      val fileName: String,
      val localPath: String,
      val mimeType: String,
      val sizeBytes: Long,
      val isImage: Boolean,
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
    // XML 的 `+` 按钮退役：附件类型选择（文件/图片）由 Compose 输入栏的
    // AttachmentSheet 承载（ChatInputBar 的 onUploadFile/onUploadImage）。
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
  /** 推理强度 + 模型槽位切换的合并菜单。附件选择已移到 Compose 输入栏的 AttachmentSheet。 */
  fun showAddMenu() {
    val slotSwitchable = hasSwitchableSlots()
    val items = mutableListOf<Pair<String, () -> Unit>>()
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

  /** Compose 输入栏 AttachmentSheet 的「上传文件」入口。 */
  fun pickFileAttachment() = openFilePicker()

  /** Compose 输入栏 AttachmentSheet 的「上传图片 / 拍照」入口。 */
  fun pickImageAttachment() = openImagePicker()

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
    // 非图片（任意类型文件）不需要预读：它们本就以路径形式交给模型用 file 工具读，
    // 走同步路径（名称解析本身也要查 provider，代价小）。
    if (!isImage) {
      val name = displayName(uri)
      if (attachments.any { it.uri == uri }) {
        return
      }
      attachments.add(Attachment(uri, name, isImage = false, bytes = null))
      renderAttachments()
      return
    }

    // 图片：先落一个「无字节」的占位条目让用户立刻看到反馈，再在 IO 线程读内容。
    // 读大图（几 MB）是磁盘 + ContentProvider 往返，放主线程会卡住输入框；
    // 而权限窗口很窄，必须尽早开始读——这里紧接着启动协程，仍在回调期间。
    val name = displayName(uri)
    if (attachments.any { it.uri == uri }) {
      return
    }
    val placeholder = Attachment(uri, name, isImage = true, bytes = null)
    attachments.add(placeholder)
    renderAttachments()

    ioScope.launch {
      val bytes = readImageBytes(uri)
      if (bytes != null) {
        // 落一份到应用私有目录：气泡缩略图与全屏查看器需要一个能反复读取的来源，
        // 而相册 URI 的临时授权随时可能失效（进程重启后必然失效）。
        val localPath = persistAttachmentFile(name, bytes)
        // 替换占位条目。用索引定位而不是 remove(placeholder)：
        // Attachment 是 data class 且带 ByteArray 字段，其生成的 equals 对数组
        // 退化为引用比较——依赖它做集合操作会踩坑（见 m9 的审查意见）。
        val index = attachments.indexOfFirst { it.uri == uri }
        if (index >= 0) {
          attachments[index] = attachments[index].copy(bytes = bytes, localPath = localPath)
        }
      }
      withContext(Dispatchers.Main) { renderAttachments() }
    }
  }

  /**
   * 把图片字节写进 `filesDir/ai/attachments/`，返回绝对路径；失败返回 null。
   *
   * <p>不做去重与清理：同一张图被选两次会落两份（文件名带序号），这是刻意的——
   * 去重需要比对内容摘要，而收益只是省几百 KB；清理则会让历史消息的缩略图
   * 在用户回看时变成破图。这些文件由用户通过「清理缓存」自行回收。
   */
  private fun persistAttachmentFile(name: String, bytes: ByteArray): String? =
      try {
        val dir = File(context.filesDir, ATTACHMENTS_DIR)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
          null
        } else {
          val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120)
          val stem = safeName.substringBeforeLast('.', safeName).ifBlank { "image" }
          val ext = safeName.substringAfterLast('.', "").let { if (it.isEmpty()) "jpg" else it }
          var target = File(dir, "$stem.$ext")
          var index = 1
          while (target.exists()) {
            target = File(dir, "$stem-$index.$ext")
            index++
          }
          target.writeBytes(bytes)
          target.absolutePath
        }
      } catch (e: Exception) {
        null
      }

  /**
   * 立刻读取图片字节；失败或超出体积上限时返回 null。
   *
   * <p>在这里判体积而不是留到发送时：超限的图当场就知道不该进 payload，
   * 缓存它只会白占内存（一张 20MB 的图在附件栏里挂几分钟）。
   *
   * <p><b>先查大小再读内容</b>：`readBytes()` 会把整个文件读进内存后才轮到体积判断
   * ——选一张 50MB 的图会先分配 50MB 再丢弃。这里先问 ContentResolver 要 SIZE，
   * 超限直接返回；SIZE 拿不到（部分 provider 不提供）时才退化为「读后判断」，
   * 并用限量流读，最多只读 MAX+1 字节。
   *
   * <p>调用方（[addAttachment]）在选图回调里同步调用，即主线程——因此这里的
   * 读取量必须有界，不能依赖「用户不会选大图」。
   */
  private fun readImageBytes(uri: Uri): ByteArray? {
    return try {
      // 第一道闸：provider 报告的体积。绝大多数相册/文件 provider 都提供。
      val declaredSize = querySize(uri)
      if (declaredSize > MAX_IMAGE_BYTES) {
        return null
      }
      // 第二道闸：限量读取。SIZE 不可信或缺失时，这里保证最多只分配 MAX+1 字节。
      val limit = MAX_IMAGE_BYTES + 1
      val bytes =
          context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
              val read = input.read(chunk)
              if (read <= 0) break
              total += read
              if (total > limit) return null // 实际比声明的还大，放弃
              buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
          }
      if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) null else bytes
    } catch (e: Exception) {
      // 权限失效、文件被删、流读取异常：都退回「无 bytes」，走文本降级。
      null
    }
  }

  /** 向 provider 查文件体积；查不到返回 -1。 */
  private fun querySize(uri: Uri): Long {
    return try {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
          cursor.getLong(index)
        } else {
          -1L
        }
      } ?: -1L
    } catch (e: Exception) {
      -1L
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

  /** 把待发附件投影到输入栏桥（Compose 侧渲染预览行）。 */
  private fun renderAttachments() {
    val bridge = owner?.inputBar ?: return
    bridge.pendingAttachments.clear()
    for (attachment in attachments) {
      val image =
          if (attachment.isImage && attachment.bytes != null) {
            val mime =
                context.contentResolver.getType(attachment.uri)
                    ?: if (attachment.name.endsWith(".png", ignoreCase = true)) "image/png"
                    else "image/jpeg"
            com.tom.rv2ide.artificial.agent.compose.compat.AgentImage(
                mimeType = mime,
                base64Data =
                    android.util.Base64.encodeToString(
                        attachment.bytes!!, android.util.Base64.NO_WRAP),
            )
          } else null
      val path = attachment.localPath ?: localPathOf(attachment.uri)
      bridge.pendingAttachments.add(
          com.tom.rv2ide.artificial.agent.compose.compat.PendingUploadAttachment(
              fileName = attachment.name,
              containerPath = path ?: attachment.uri.toString(),
              localPath = path ?: "",
              mimeType = if (attachment.isImage) "image/*" else "application/octet-stream",
              sizeBytes = attachment.bytes?.size?.toLong() ?: 0L,
              image = image,
          ))
    }
  }

  /**
   * 构建多模态输入（图片）。
   *
   * <p>协议层已有 {@code ImageInputPayload} 与 {@code ModelMessage.rawInputJson}，
   * OpenAI 兼容与 Anthropic 两条序列化路径都会把它转成 image_url / image block。
   * 这里负责把用户选的图片读成 base64 并打包。
   *
   * <p><b>打包全部可发送的图片</b>，不再只取第一张。此前协议层 payload 只容得下单图，
   * 于是第 2 张起被降级成路径文本——模型实际只看到 1 张，而用户以为自己发了 3 张。
   * 现在 payload 带 {@code images} 数组（旧单图字段仍写第一张，保持线格式兼容）。
   *
   * <p>能不能进 payload 由 [sendableImages] 统一判定，[attachmentContextExcludingImage]
   * 用同一个函数剔除已发送的图——两处若各写一套判据，会出现「既没进 payload、也没在文本里」
   * 的静默丢失。
   *
   * <p><b>一张都发不了时返回 null</b>：让调用方退化为文本路径（把路径交给模型），
   * 而不是发一个空 payload 让服务端报错——后者的错误信息对用户毫无意义。
   */
  fun imageRawInputJson(prompt: String): String? {
    val images = sendableImages()
    if (images.isEmpty()) {
      return null
    }
    val parts =
        images.mapNotNull { image ->
          val bytes = imageBytesFor(image) ?: return@mapNotNull null
          com.tom.rv2ide.ai.protocol.ImageInputPayload.ImagePart(
              mimeTypeFor(image),
              android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
          )
        }
    if (parts.isEmpty()) {
      return null
    }
    return try {
      com.tom.rv2ide.ai.protocol.ImageInputPayload.rawInputJson(prompt, parts)
    } catch (e: Exception) {
      null
    }
  }

  /**
   * 本次请求真正能进多模态 payload 的图片，按用户选择顺序。
   *
   * <p>三道闸：图片、能读到字节、单张不超 [MAX_IMAGE_BYTES]。总数与总体积另设上限
   * ——服务商对请求体有硬限制，而 base64 会让体积再涨 1/3；一次性塞十几张会让整条
   * 请求被拒（错误信息通常只有「400 bad request」，排查成本极高）。超出的部分仍走
   * 文本路径，模型可以自己用 file 工具读。
   */
  private fun sendableImages(): List<Attachment> {
    val out = ArrayList<Attachment>(MAX_IMAGES_PER_MESSAGE)
    var totalBytes = 0L
    for (attachment in attachments) {
      if (!attachment.isImage) continue
      if (out.size >= MAX_IMAGES_PER_MESSAGE) break
      val bytes = imageBytesFor(attachment) ?: continue
      if (bytes.size > MAX_IMAGE_BYTES) continue
      if (totalBytes + bytes.size > MAX_TOTAL_IMAGE_BYTES) continue
      totalBytes += bytes.size
      out.add(attachment)
    }
    return out
  }

  /**
   * 取一张图片的字节。
   *
   * <p>优先用选择时缓存的字节。**不在这里 openInputStream 作为主路径**：Photo Picker
   * 的 URI 权限只在选择回调期间有效，此刻（用户点发送）通常已失效，现场读必然拿到 null
   * ——这正是「AI 说看不到图片」的根因（见 addAttachment 的说明）。
   *
   * <p>缓存为空（读失败/超限/旧版本添加的附件）时退回现场读一次：对
   * `ACTION_OPEN_DOCUMENT` 之类带持久权限的 URI 仍然有效，作为兜底。
   */
  private fun imageBytesFor(attachment: Attachment): ByteArray? {
    val bytes = attachment.bytes ?: readImageBytes(attachment.uri) ?: return null
    return if (bytes.isEmpty()) null else bytes
  }

  private fun mimeTypeFor(attachment: Attachment): String =
      context.contentResolver.getType(attachment.uri)
          ?: if (attachment.name.endsWith(".png", ignoreCase = true)) "image/png"
          else "image/jpeg"

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
   * 同 [attachmentContext]，但排除**已经进多模态 payload 的图片**。
   *
   * <p>不排除的话模型会同时收到「图片内容」与「图片路径」两份信息：可能重复处理，
   * 也可能放着图片不用、改去读文件（而相册图片的路径本来就拿不到）。
   *
   * <p>判据必须与 [imageRawInputJson] 完全一致（同一个 [sendableImages]）：
   * 两边各写一套，就会出现「既没进 payload、也不在文本里」的静默丢失——这正是
   * 修复前「发 3 张只看得到 2 张」之外的另一半问题。
   *
   * <p>没能进 payload 的图片（超限、读不到、超出张数上限）仍以路径形式交给模型，
   * 让它用 file 工具自己读。
   */
  fun attachmentContextExcludingImage(): String {
    val sent = sendableImages().mapTo(HashSet()) { it.uri }
    val rest = attachments.filter { !it.isImage || it.uri !in sent }
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
    // 已落盘的图片优先给那份路径：相册 URI 的临时授权会在进程重启后失效，
    // 交给模型一个打不开的 content:// 等于没给。
    val path = attachment.localPath ?: localPathOf(attachment.uri)
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

  /**
   * 待发附件的快照，供宿主挂到消息气泡上。
   *
   * <p>必须在 [clearAttachments] **之前**调用——清空后就读不到了。返回值里不含
   * base64：气泡缩略图按 [AttachedFile.localPath] 从磁盘解码，比攥着几 MB 字符串
   * 便宜得多，也不会让消息条目在 `snapshot()` 比较时反复复制大对象。
   */
  fun attachmentSnapshot(): List<AttachedFile> =
      attachments.map { attachment ->
        val path = attachment.localPath ?: localPathOf(attachment.uri)
        AttachedFile(
            fileName = attachment.name,
            localPath = path.orEmpty(),
            mimeType = if (attachment.isImage) mimeTypeFor(attachment) else "",
            sizeBytes = attachment.bytes?.size?.toLong() ?: 0L,
            isImage = attachment.isImage,
        )
      }

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
    // XML `+` 按钮退役：推理强度的当前档位由 Compose 输入栏的
    // ReasoningEffortSelector 自行显示（reasoningLabelRes 供选择器用）。
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

    /**
     * 单条消息最多打包进 payload 的图片张数。
     *
     * <p>取 4：多数服务商对单次请求的图片数有隐性上限（实测部分 OpenAI 兼容端点
     * 超过 4 张就开始忽略后面的），而 4 张 1536px 的 JPEG 已接近常见请求体上限。
     * 超出的图片降级成路径文本，模型仍能自己读。
     */
    private const val MAX_IMAGES_PER_MESSAGE = 4

    /** 单条消息所有图片的原始字节合计上限；base64 后约 ×1.33。 */
    private const val MAX_TOTAL_IMAGE_BYTES = 12 * 1024 * 1024

    /** 附件落盘目录（相对 filesDir）。与 AI 的其他持久化数据同处 `files/ai/` 下。 */
    private const val ATTACHMENTS_DIR = "ai/attachments"
    private const val DEFAULT_REASONING =
        com.tom.rv2ide.ai.protocol.AiBehaviorSettings.REASONING_AUTO
  }
}
