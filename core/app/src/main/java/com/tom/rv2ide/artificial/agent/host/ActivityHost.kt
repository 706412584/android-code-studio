/*
 * This file is part of AndroidCodeStudio.
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.host

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.artificial.agent.AssistantModelPicker
import com.tom.rv2ide.artificial.agent.AssistantSettings
import com.tom.rv2ide.resources.R.string

/**
 * Activity 宿主：把面板挂在 Activity 的容器上，弹窗用 Activity 的 Context 弹。
 *
 * <p>本实现是「抽象前行为」的等价搬迁——每一处弹窗的 builder 类型、按钮文案、
 * 回调时机都与原先逐字一致，只是从视图里搬到了这里。Activity 下用户观感零变化。
 *
 * @see AssistantHost
 */
class ActivityHost(
    override val context: Context,
    override val lifecycleScope: LifecycleCoroutineScope,
    override val container: ViewGroup,
    /**
     * 「让位」目标：面板打开时收缩它，使编辑器与助手真正并列。
     *
     * <p>可空——只有编辑器宿主会传（`content_editor.xml` 的 `editor_content`）。
     * 主页 / 内联页 / 真全屏没有需要让位的邻居，传 null 时退化为「面板盖在上面」，
     * 与抽象前行为一致。
     */
    private val yieldingView: View? = null,
    /**
     * 面板收起时的额外动作。
     *
     * <p>可空——只有真全屏宿主会传（`AssistantFullscreenActivity`）。
     * 那种宿主里**面板就是页面的全部内容**：收起面板后窗口只剩一个空容器
     * （surface 底色），用户看到一整片白，且没有任何返回入口。
     * 因此它的「关闭」应当结束 Activity，而不是把面板藏起来。
     *
     * <p>其余宿主（主页悬浮 / 内联页 / 编辑器）都有别的内容可看，
     * 收起面板只是回到它们本来的界面，传 null 即维持原行为。
     */
    private val onClosed: (() -> Unit)? = null,
    /**
     * 打开一个文件的出口（会话抽屉文件 Tab）。
     *
     * <p>可空——只有**编辑器**宿主能真正打开文件（`IEditorHandler.openFile`）；
     * 主页/真全屏/内联页没有编辑器可切，传 null 时点击静默忽略
     * （与 [onClosed] 同一套「宿主能力不齐就降级」约定）。
     */
    private val onOpenFile: ((java.io.File) -> Unit)? = null,
) : AssistantHost {

  override fun widthPx(): Int = container.resources.displayMetrics.widthPixels

  override fun onOpenFileRequested(file: java.io.File) {
    onOpenFile?.invoke(file)
  }

  override fun onPanelOpened() = applyYield(yield = true)

  override fun onPanelClosed() {
    applyYield(yield = false)
    onClosed?.invoke()
  }

  /**
   * 收缩/恢复让位目标。
   *
   * <p><b>宽屏左右、窄屏上下</b>——这不是审美偏好，是可用性底线：392dp 的竖屏手机若做
   * 左右分栏，编辑器只剩约 126dp，扣掉行号与左侧文件树抽屉后实际代码区不足 10 个字符，
   * 编辑器直接不可用。而代码编辑器怕丢横向空间、不怕丢纵向空间（滚动即可），
   * 因此窄屏改为上下分栏，编辑器保留全宽。
   *
   * <p>比例与方向都从 [AssistantHost] 读（[isWideScreen] / [yieldFraction]），
   * 因为面板几何要用**同一组值**算自己的尺寸。两处各算一份必然漂移——
   * 已经踩过：编辑器让出下半屏、面板却仍贴右侧满高，右上角直接重叠。
   */
  private fun applyYield(yield: Boolean) {
    val view = yieldingView ?: return
    val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val metrics = container.resources.displayMetrics

    if (isWideScreen()) {
      params.width =
          if (yield) (metrics.widthPixels * yieldFraction()).toInt()
          else ViewGroup.LayoutParams.MATCH_PARENT
      params.height = ViewGroup.LayoutParams.MATCH_PARENT
    } else {
      params.width = ViewGroup.LayoutParams.MATCH_PARENT
      params.height =
          if (yield) (heightPx() * yieldFraction()).toInt()
          else ViewGroup.LayoutParams.MATCH_PARENT
    }
    view.layoutParams = params
  }

  override val dialogs: AssistantDialogs = ActivityDialogs(context)

  override val attachments: AssistantAttachments = ActivityAttachments(context)
}

/**
 * [ActivityHost] 的附件选择实现：走 `registerForActivityResult`，与现有
 * [com.tom.rv2ide.artificial.agent.AssistantInputFeatures] 的 Activity 路径等价。
 *
 * <p>Context 不是 [androidx.fragment.app.FragmentActivity] 时（理论上不会发生——Activity
 * 宿主总是 Activity）静默回调 `null`，调用方按「未选择」处理，不崩溃。
 *
 * <p>Intent 与现有实现逐字一致：
 * - 文件：`ACTION_OPEN_DOCUMENT`（给出的 URI 带持久读权限，不会选完即失效）+ 通配 MIME + 多选；
 * - 图片：`ACTION_GET_CONTENT` + `image` 通配 + 多选。
 *
 * <p>结果收集同时看 `clipData` 与 `data`：多选时系统只把结果放进 `clipData`，只读 `data`
 * 会把「选了两个文件」变成「什么都没选」。
 */
private class ActivityAttachments(private val context: Context) : AssistantAttachments {

  /**
   * 待回传的回调。
   *
   * <p>launcher 在 [init] 注册一次并长期持有，结果到达时通过这里找到「本次发起者」。
   * 同一时刻只可能有一次选择（UI 上 `+` 菜单是模态的），因此单个槽位足够。
   */
  private var pendingFile: ((android.net.Uri?) -> Unit)? = null
  private var pendingImage: ((android.net.Uri?) -> Unit)? = null

  private val fileLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>?
  private val imageLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>?

  /**
   * 注册是否成功。
   *
   * <p>`registerForActivityResult` **必须在 Activity 进入 STARTED 之前**调用，否则抛
   * IllegalStateException。这在本类诞生时不是问题——当时只有两个宿主：真全屏 Activity
   * （`onCreate` 里装配）与内联页（页面创建时装配），都在时机内。
   *
   * <p>2026-10-09 新增侧栏宿主后该前提不再成立：侧栏是**懒创建**的（用户点击标签才
   * `commitNow`，见 `EditorSidebarActions`），那时 Activity 早已 RESUMED。实测直接崩溃：
   * `IllegalStateException: LifecycleOwner ... is attempting to register while current
   * state is RESUMED`。
   *
   * <p>不能为此改侧栏的懒创建——那是框架既有行为，且所有标签页都依赖它（预创建全部
   * Fragment 会拖慢进项目）。也不能把注册推迟到「首次选附件时」——那时同样是 RESUMED。
   * 因此改为：**能注册就注册，不能就降级到蹦床**（[AttachmentPickerActivity] +
   * 进程级槽位，见 [fallbackPicker]）。蹦床走 `startActivityForResult` 语义，
   * 不依赖生命周期时机，是 Service 宿主早就在用的同一条路。
   */
  private val registered: Boolean

  init {
    val activity = context as? androidx.fragment.app.FragmentActivity
    val contract =
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    if (activity == null) {
      fileLauncher = null
      imageLauncher = null
      registered = false
    } else {
      // 用 try/catch 探测而非查询状态：ActivityResultRegistry 没有公开 API 能问
      // 「现在还能不能注册」，抛异常是唯一的判据。异常只可能来自时机，不会掩盖其它错误。
      var file: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
      var image: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
      var ok = false
      try {
        file =
            activity.registerForActivityResult(contract) { result ->
              val cb = pendingFile
              pendingFile = null
              deliver(result, cb)
            }
        image =
            activity.registerForActivityResult(contract) { result ->
              val cb = pendingImage
              pendingImage = null
              deliver(result, cb)
            }
        ok = true
      } catch (e: IllegalStateException) {
        // 时机已过（Activity 已 RESUMED）。已注册的那个 launcher 无法撤销，
        // 但它不会被使用（下面的 fallback 分支接管），泄漏一个未触发的 launcher 无副作用。
        file = null
        image = null
      }
      fileLauncher = file
      imageLauncher = image
      registered = ok
    }
  }

  /**
   * 降级选择器：时机已过时用蹦床。
   *
   * <p>复用 `AssistantOverlayService` 的槽位与 `deliverAttachmentResult` 解析——
   * 那条路本就是为「无法用 registerForActivityResult 的宿主」准备的（Service），
   * 解析逻辑（clipData 与 data 都看、多选逐个回调）已在彼处实现且被真机验证过，
   * 这里不重复一份。
   */
  private fun fallbackPicker(imageOnly: Boolean, onResult: (android.net.Uri?) -> Unit) {
    val pickIntent =
        if (imageOnly) {
          android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
          }
        } else {
          android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
          }
        }
    val requestCode =
        com.tom.rv2ide.services.AssistantOverlayService.registerAttachmentRequest(onResult)
    val trampoline =
        com.tom.rv2ide.activities.AttachmentPickerActivity.newIntent(context, pickIntent)
            .putExtra(
                com.tom.rv2ide.activities.AttachmentPickerActivity.EXTRA_REQUEST_CODE,
                requestCode)
    // 从 Activity 上下文启动时不需要 NEW_TASK；这里不无条件加——加了会让选择器
    // 脱离本任务栈，返回时可能回到桌面而不是编辑器。
    try {
      context.startActivity(trampoline)
    } catch (e: android.content.ActivityNotFoundException) {
      com.tom.rv2ide.services.AssistantOverlayService.cancelAttachmentRequest(requestCode)
      onResult(null)
    }
  }

  override fun pickFile(onResult: (android.net.Uri?) -> Unit) {
    // 注册时机已过（如侧栏懒创建的宿主）：走蹦床，见 [fallbackPicker] 的说明。
    if (!registered) {
      fallbackPicker(imageOnly = false, onResult)
      return
    }
    // ACTION_OPEN_DOCUMENT 而不是 ACTION_GET_CONTENT：前者给出的 URI 带持久读权限，
    // 且不会因为「选完即失效」导致发送时读不到文件。
    val intent =
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(android.content.Intent.CATEGORY_OPENABLE)
          type = "*/*"
          // 多选：一次挑几个相关文件是常见需求（对比两个实现文件）。
          putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    launch(fileLauncher, intent, { pendingFile = it }, onResult)
  }

  override fun pickImage(onResult: (android.net.Uri?) -> Unit) {
    // 同上：时机已过时走蹦床。
    if (!registered) {
      fallbackPicker(imageOnly = true, onResult)
      return
    }
    val intent =
        android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
          addCategory(android.content.Intent.CATEGORY_OPENABLE)
          type = "image/*"
          putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    launch(imageLauncher, intent, { pendingImage = it }, onResult)
  }

  /** 启动选择器；设备上没有对应 Activity 时回调 null 而不是抛 ActivityNotFoundException。 */
  private fun launch(
      launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>?,
      intent: android.content.Intent,
      store: ((android.net.Uri?) -> Unit) -> Unit,
      onResult: (android.net.Uri?) -> Unit,
  ) {
    if (launcher == null) {
      onResult(null)
      return
    }
    store(onResult)
    try {
      launcher.launch(intent)
    } catch (e: android.content.ActivityNotFoundException) {
      store({ _ -> })
      onResult(null)
    }
  }

  /** 解析结果并按每个 URI 回调一次；取消/空结果回调一次 null。 */
  private fun deliver(
      result: androidx.activity.result.ActivityResult,
      onResult: ((android.net.Uri?) -> Unit)?,
  ) {
    if (onResult == null) {
      return
    }
    val data = result.data
    if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
      onResult(null)
      return
    }
    // 同时看 clipData 与 data：多选时系统只把结果放进 clipData，data 为 null。
    val clip = data.clipData
    var delivered = false
    if (clip != null) {
      for (i in 0 until clip.itemCount) {
        clip.getItemAt(i).uri?.let {
          delivered = true
          onResult(it)
        }
      }
    } else {
      data.data?.let {
        delivered = true
        onResult(it)
      }
    }
    if (!delivered) {
      onResult(null)
    }
  }
}

/**
 * [ActivityHost] 的弹窗实现：直接使用 Activity 的 Context 弹 Material / AppCompat 对话框。
 *
 * <p>刻意保持与原实现相同的 builder 选择（权限与上下文用 Material，确认与危险工具用
 * AppCompat AlertDialog）：两者在主题下的圆角与配色不同，混用会让用户看到两种观感的弹窗。
 */
private class ActivityDialogs(private val context: Context) : AssistantDialogs {

  override fun showSingleChoice(
      titleRes: Int,
      items: Array<String>,
      checked: Int,
      onChosen: (Int) -> Unit,
  ) {
    // 与原先一致：Material 单选列表，选中后先跑回调（写偏好 + 刷新标签）再关闭。
    MaterialAlertDialogBuilder(context)
        .setTitle(titleRes)
        .setSingleChoiceItems(items, checked) { dialog, which ->
          onChosen(which)
          dialog.dismiss()
        }
        .show()
  }

  override fun showMessage(
      titleRes: Int,
      message: CharSequence,
      positiveRes: Int,
      onPositive: () -> Unit,
      negativeRes: Int?,
  ) {
    val builder =
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ -> onPositive() }
    if (negativeRes != null) {
      builder.setNegativeButton(negativeRes, null)
    }
    builder.show()
  }

  override fun showConfirm(
      titleRes: Int,
      message: CharSequence,
      positiveRes: Int,
      onConfirm: () -> Unit,
  ) {
    // 原先这两处（跨项目打开、删除会话）用的就是 AppCompat AlertDialog，保持一致。
    AlertDialog.Builder(context)
        .setTitle(titleRes)
        .setMessage(message)
        .setPositiveButton(positiveRes) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  override fun showDangerousToolConfirm(
      toolName: String,
      args: String?,
      onAllowOnce: () -> Unit,
      onAllowAlways: () -> Unit,
      onDeny: () -> Unit,
  ) {
    AlertDialog.Builder(context)
        .setTitle(string.ai_assistant_dangerous_title)
        .setMessage(
            context.getString(
                string.ai_assistant_dangerous_message,
                toolName,
                args?.take(500) ?: "",
            ))
        .setCancelable(false)
        .setPositiveButton(string.ai_assistant_dangerous_allow_once) { _, _ -> onAllowOnce() }
        .setNeutralButton(string.ai_assistant_dangerous_allow_always) { _, _ -> onAllowAlways() }
        .setNegativeButton(string.ai_assistant_dangerous_deny) { _, _ -> onDeny() }
        .show()
  }

  override fun showUserQuestions(
      questions: List<com.tom.rv2ide.ai.tool.ToolSettingsPort.Question>,
      onAnswer: (List<String>) -> Unit,
      onCancel: () -> Unit,
  ) {
    if (questions.isEmpty()) {
      onCancel()
      return
    }
    // 多道题时逐道询问，收集全部答案后一次性回调。
    val answers = mutableListOf<String>()
    fun askNext(index: Int) {
      if (index >= questions.size) {
        onAnswer(answers)
        return
      }
      val q = questions[index]
      val optionsWithOther = q.options.toMutableList().apply {
        add(context.getString(string.ai_assistant_question_other))
      }
      val optionsArray = optionsWithOther.toTypedArray()

      if (q.multiSelect) {
        val checked = BooleanArray(optionsArray.size)
        MaterialAlertDialogBuilder(context)
            .setTitle(q.header.ifBlank { context.getString(string.ai_assistant_question_title) })
            .setMessage(q.question)
            .setMultiChoiceItems(optionsArray, checked) { _, which, isChecked ->
              checked[which] = isChecked
            }
            .setPositiveButton(string.ai_assistant_question_submit) { _, _ ->
              val selected = optionsArray.filterIndexed { i, _ -> checked[i] }
              answers.add(selected.joinToString(","))
              askNext(index + 1)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
      } else {
        var selectedIndex = 0
        MaterialAlertDialogBuilder(context)
            .setTitle(q.header.ifBlank { context.getString(string.ai_assistant_question_title) })
            .setMessage(q.question)
            .setSingleChoiceItems(optionsArray, selectedIndex) { _, which ->
              selectedIndex = which
            }
            .setPositiveButton(string.ai_assistant_question_submit) { _, _ ->
              if (selectedIndex == optionsArray.size - 1) {
                // 选了「其它」：弹简单输入框
                val input = android.widget.EditText(context).apply {
                  hint = context.getString(string.ai_assistant_question_other_hint)
                }
                MaterialAlertDialogBuilder(context)
                    .setTitle(q.question)
                    .setView(input)
                    .setPositiveButton(string.ai_assistant_question_submit) { _, _ ->
                      answers.add(input.text.toString().trim())
                      askNext(index + 1)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
                    .setOnCancelListener { onCancel() }
                    .show()
              } else {
                answers.add(optionsArray[selectedIndex])
                askNext(index + 1)
              }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
      }
    }
    askNext(0)
  }

  override fun showModelPicker(onChanged: () -> Unit) {
    // Activity 宿主下仍是 BottomSheetDialog；设置入口也交回本宿主，保证落点一致。
    // 必须用**命名参数**：show() 最后一个参数是 configureWindow（在 show() 前调用），
    // 尾随 lambda 会被绑到它上面且类型恰好兼容——曾因此导致每次打开选择器都先跳设置页。
    AssistantModelPicker.show(context, onChanged, onOpenSettings = { openSettings() })
  }

  override fun openSettings() {
    AssistantSettings.open(context)
  }
}
