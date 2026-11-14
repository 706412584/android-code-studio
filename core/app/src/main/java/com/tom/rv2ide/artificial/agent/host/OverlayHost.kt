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

import android.app.Activity
import android.app.Dialog
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.LifecycleCoroutineScope
import com.tom.rv2ide.R
import com.tom.rv2ide.R as AppR
import com.tom.rv2ide.activities.AttachmentPickerActivity
import com.tom.rv2ide.artificial.agent.AssistantModelPicker
import com.tom.rv2ide.artificial.agent.AssistantSettings
import com.tom.rv2ide.artificial.agent.compose.compat.OverlayDialogHandle
import com.tom.rv2ide.artificial.agent.compose.compat.OverlayDialogHost
import com.tom.rv2ide.artificial.agent.compose.compat.createOverlayDialog
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.services.AssistantOverlayService

/**
 * 应用外系统悬浮（overlay）宿主。
 *
 * <p>与 [ActivityHost] 的差别集中在三处「Service 没有 Activity 的 window token」导致的能力缺口：
 *
 * 1. **弹窗**：普通 `Dialog` 默认类型是 `TYPE_APPLICATION`，需要一个 Activity token，
 *    否则 `show()` 抛 `BadTokenException`。这里统一在 `show()` 之前把弹窗 window 的 type
 *    改成 `TYPE_APPLICATION_OVERLAY`（见 [OverlayDialogs.applyOverlayType]）。
 * 2. **模型选择器**：`BottomSheetDialog` 依赖 Activity 的 window 属性，在 overlay 下不可用，
 *    因此降级为普通 `Dialog`（[AssistantModelPicker.show] 的 `asDialog=true`）。
 * 3. **附件选择**：Service 无法注册 `ActivityResultLauncher`，改走
 *    [AttachmentPickerActivity] 蹦床，结果经显式 Intent 回传给 Service（见 [OverlayAttachments]）。
 *
 * <p><b>为什么 [context] 是 ContextThemeWrapper 而不是裸 Service</b>：Material 弹窗与
 * `LayoutInflater` 需要一套 AppCompat/Material 主题属性（`colorSurface`、`alertDialogTheme`
 * 等），而 Service 的默认主题是框架基础主题，缺这些属性。套一层
 * [ContextThemeWrapper] 指向应用主题即可，且不影响 Service 的其它行为。
 *
 * @see AssistantOverlayService 本宿主的持有者与窗口管理方。
 */
class OverlayHost(
    private val service: Service,
    override val lifecycleScope: LifecycleCoroutineScope,
    override val container: ViewGroup,
    /**
     * 面板拖动的执行者（由 [AssistantOverlayService] 注入）。
     *
     * <p><b>为什么用回调而不是让宿主自己改窗口</b>：改悬浮窗必须走
     * `WindowManager.updateViewLayout` 并持有当前的 `LayoutParams`——那些状态都在
     * 服务里，宿主持有的是抽象 `Service`。给一个 `(dx, dy) -> Unit` 既让宿主保持
     * 「不知道怎么移窗口」，也免了把整个服务 API 暴露给视图层。
     */
    private val onPanelDrag: ((dx: Int, dy: Int) -> Unit)? = null,
) : AssistantHost {

  /** 带应用主题的 Context；弹窗与视图 inflate 都用它。 */
  override val context: Context = ContextThemeWrapper(service, AppR.style.Theme_AndroidIDE)

  /**
   * 面板宽度预算。
   *
   * <p>取屏幕宽度：overlay 窗口是 MATCH_PARENT，与屏幕等宽，因此 SIDEBAR/DOCKED 的
   * 宽度比例算法与 Activity 宿主一致。
   */
  override fun widthPx(): Int = service.resources.displayMetrics.widthPixels

  override val dialogs: AssistantDialogs = OverlayDialogs(context)

  override val attachments: AssistantAttachments = OverlayAttachments(service)

  /**
   * 面板顶栏拖动 → 移动系统窗口。
   *
   * <p>用「增量」而非「绝对坐标」：Compose 的 `detectDragGestures` 给的就是逐帧增量，
   * 服务侧累加即可，两端都不必追踪起点。回调为 null（构造时没注入）时静默忽略——
   * 拖动只是便利功能，不该影响面板其余行为。
   */
  override fun onOverlayPanelDrag(dx: Int, dy: Int) {
    onPanelDrag?.invoke(dx, dy)
  }

  /**
   * 悬浮层弹窗宿主（见 [OverlayDialogHost]）。
   *
   * <p>Compose 的 `Dialog` 在 overlay 下必崩（`show()` 需要 Activity token），
   * 而这里创建的**原生** `Dialog` 可以在 `show()` 之前把 window type 设成
   * `TYPE_APPLICATION_OVERLAY`——顺序对了就不会崩。
   */
  val overlayDialogs: OverlayDialogHost = OverlayDialogHost { onDismissRequest, content ->
    // 渲染进面板**内部**的 overlay 容器（layout_ai_assistant.xml 的
    // assistantSheetOverlay），而不是建系统窗口。
    //
    // 建窗口（TYPE_APPLICATION_OVERLAY）虽然不崩，但遮罩会铺满整个屏幕、面板被盖住，
    // 用户看到的是「切到了另一个界面」——那不是 sheet 的语义。画在面板视图树里，
    // 遮罩与卡片自然只覆盖面板范围，与 Activity 宿主的观感一致。
    val overlay = container.findViewById<ViewGroup>(R.id.assistantSheetOverlay)
    val compose = container.findViewById<androidx.compose.ui.platform.ComposeView>(
        R.id.assistantSheetCompose)
    if (overlay != null && compose != null) {
      overlay.visibility = View.VISIBLE
      compose.setContent(content)
      return@OverlayDialogHost object : OverlayDialogHandle {
        override fun dismiss() {
          overlay.visibility = View.GONE
          // 清空内容，避免下一层 sheet 打开时短暂闪出上一份内容。
          runCatching { compose.setContent {} }
          onDismissRequest()
        }
      }
    }
    // 兜底：布局里没有容器（旧版 XML）时退回系统窗口方案。
    val dialog =
        createOverlayDialog(
            context = ContextThemeWrapper(service, AppR.style.Theme_AndroidIDE),
            onDismiss = onDismissRequest,
            installViewTreeOwners = { view ->
              val owners = service as? com.tom.rv2ide.services.AssistantOverlayService
              if (owners != null) {
                com.tom.rv2ide.artificial.agent.compose.OverlayViewTreeOwners.install(
                    view, owners, owners, owners)
              }
            },
            bounds =
                com.tom.rv2ide.services.AssistantOverlayService.panelBoundsOnScreen()
                    ?: android.graphics.Rect(
                        0,
                        0,
                        service.resources.displayMetrics.widthPixels,
                        service.resources.displayMetrics.heightPixels),
            content = content)
    dialog.show()
    object : OverlayDialogHandle {
      override fun dismiss() {
        runCatching { dialog.dismiss() }
      }
    }
  }
}

/**
 * overlay 的弹窗实现。
 *
 * <p>每个弹窗在 `show()` 之前都必须把 window type 设成 `TYPE_APPLICATION_OVERLAY`——
 * 这是 overlay 下弹窗与 Activity 下弹窗的**唯一**必要差异。弹窗一律经
 * [com.tom.rv2ide.utils.DialogUtils.newMaterialDialogBuilder] 取主题统一挂载的 M3 overlay，
 * 与 [ActivityHost] 的形态一致（悬浮窗与 Activity 内的同一种弹窗观感相同）。
 */
private class OverlayDialogs(
    private val themed: Context,
) : AssistantDialogs {

  /**
   * 把弹窗挂到系统悬浮层。
   *
   * <p>**必须在 `show()` 之前设置**：`show()` 会把 window 真正添加到 WindowManager，
   * 之后改 type 不会生效（已用旧 type 请求过 token）。
   */
  private fun applyOverlayType(dialog: Dialog) {
    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
  }

  override fun showSingleChoice(
      titleRes: Int,
      items: Array<String>,
      checked: Int,
      onChosen: (Int) -> Unit,
  ) {
    val dialog =
        com.tom.rv2ide.utils.DialogUtils.newMaterialDialogBuilder(themed)
            .setTitle(titleRes)
            .setSingleChoiceItems(items, checked) { d, which ->
              onChosen(which)
              d.dismiss()
            }
            .create()
    applyOverlayType(dialog)
    dialog.show()
  }

  override fun showMessage(
      titleRes: Int,
      message: CharSequence,
      positiveRes: Int,
      onPositive: () -> Unit,
      negativeRes: Int?,
  ) {
    val builder =
        com.tom.rv2ide.utils.DialogUtils.newMaterialDialogBuilder(themed)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ -> onPositive() }
    if (negativeRes != null) {
      builder.setNegativeButton(negativeRes, null)
    }
    val dialog = builder.create()
    applyOverlayType(dialog)
    dialog.show()
  }

  override fun showConfirm(
      titleRes: Int,
      message: CharSequence,
      positiveRes: Int,
      onConfirm: () -> Unit,
  ) {
    val dialog =
        com.tom.rv2ide.utils.DialogUtils.newMaterialDialogBuilder(themed)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    applyOverlayType(dialog)
    dialog.show()
  }

  override fun showDangerousToolConfirm(
      toolName: String,
      args: String?,
      onAllowOnce: () -> Unit,
      onAllowAlways: () -> Unit,
      onDeny: () -> Unit,
  ) {
    // 超时守卫：5 分钟无应答即视为拒绝并关窗（与 ActivityHost 同一预算）。
    val guard = DialogTimeouts.Guard { onDeny() }.start()
    val dialog =
        com.tom.rv2ide.utils.DialogUtils.newMaterialDialogBuilder(themed)
            .setTitle(string.ai_assistant_dangerous_title)
            .setMessage(
                themed.getString(
                    string.ai_assistant_dangerous_message,
                    toolName,
                    args?.take(500) ?: "",
                ))
            .setCancelable(false)
            .setPositiveButton(string.ai_assistant_dangerous_allow_once) { _, _ ->
              guard.cancel()
              onAllowOnce()
            }
            .setNeutralButton(string.ai_assistant_dangerous_allow_always) { _, _ ->
              guard.cancel()
              onAllowAlways()
            }
            .setNegativeButton(string.ai_assistant_dangerous_deny) { _, _ ->
              guard.cancel()
              onDeny()
            }
            .create()
    guard.attach(dialog)
    applyOverlayType(dialog)
    dialog.show()
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
    // 超时守卫覆盖整次调用（与 ActivityHost 同一预算：5 分钟，逐题各计会导致错配）。
    val guard = DialogTimeouts.Guard { onCancel() }.start()
    val answers = mutableListOf<String>()
    fun askNext(index: Int) {
      if (guard.isFinished()) {
        // 已超时：不要再摆下一道题。
        return
      }
      if (index >= questions.size) {
        guard.cancel()
        onAnswer(answers)
        return
      }
      val q = questions[index]
      val optionsWithOther = q.options.toMutableList().apply {
        add(themed.getString(string.ai_assistant_question_other))
      }
      val optionsArray = optionsWithOther.toTypedArray()

      if (q.multiSelect) {
        val checked = BooleanArray(optionsArray.size)
        val dialog =
            com.tom.rv2ide.utils.DialogUtils.newMaterialDialogBuilder(themed)
                .setTitle(q.header.ifBlank { themed.getString(string.ai_assistant_question_title) })
                .setMessage(q.question)
                .setMultiChoiceItems(optionsArray, checked) { _, which, isChecked ->
                  checked[which] = isChecked
                }
                .setPositiveButton(string.ai_assistant_question_submit) { _, _ ->
                  val selected = optionsArray.filterIndexed { i, _ -> checked[i] }
                  answers.add(selected.joinToString(","))
                  askNext(index + 1)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                  guard.cancel()
                  onCancel()
                }
                .setOnCancelListener {
                  guard.cancel()
                  onCancel()
                }
                .create()
        guard.attach(dialog)
        applyOverlayType(dialog)
        dialog.show()
      } else {
        var selectedIndex = 0
        val dialog =
            com.tom.rv2ide.utils.DialogUtils.newMaterialDialogBuilder(themed)
                .setTitle(q.header.ifBlank { themed.getString(string.ai_assistant_question_title) })
                .setMessage(q.question)
                .setSingleChoiceItems(optionsArray, selectedIndex) { _, which ->
                  selectedIndex = which
                }
                .setPositiveButton(string.ai_assistant_question_submit) { _, _ ->
                  if (selectedIndex == optionsArray.size - 1) {
                    val input =
                        com.tom.rv2ide.preferences.dialogEditTextField(
                            themed,
                            themed.getString(string.ai_assistant_question_other_hint),
                        )
                    val inputDialog =
                        com.tom.rv2ide.utils.DialogUtils.newMaterialDialogBuilder(themed)
                            .setTitle(q.question)
                            .setView(
                                com.tom.rv2ide.preferences.dialogFieldColumn(themed, input))
                            .setPositiveButton(string.ai_assistant_question_submit) { _, _ ->
                              answers.add(input.editText?.text?.toString()?.trim().orEmpty())
                              askNext(index + 1)
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ ->
                              guard.cancel()
                              onCancel()
                            }
                            .setOnCancelListener {
                              guard.cancel()
                              onCancel()
                            }
                            .create()
                    guard.attach(inputDialog)
                    applyOverlayType(inputDialog)
                    inputDialog.show()
                  } else {
                    answers.add(optionsArray[selectedIndex])
                    askNext(index + 1)
                  }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                  guard.cancel()
                  onCancel()
                }
                .setOnCancelListener {
                  guard.cancel()
                  onCancel()
                }
                .create()
        guard.attach(dialog)
        applyOverlayType(dialog)
        dialog.show()
      }
    }
    askNext(0)
  }

  override fun showModelPicker(onChanged: () -> Unit) {
    // overlay 下 BottomSheetDialog 不可用，降级为普通 Dialog；window type 由
    // configureWindow 钩子在 show() 前设置。设置入口仍交回本宿主，保证落点一致。
    AssistantModelPicker.show(
        themed,
        onChanged,
        onOpenSettings = { openSettings() },
        asDialog = true,
        configureWindow = { it.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) },
    )
  }

  override fun openSettings() {
    // themed 不是 Activity，AssistantSettings.open 会自动补 FLAG_ACTIVITY_NEW_TASK。
    AssistantSettings.open(themed)
  }
}

/**
 * overlay 的附件选择实现。
 *
 * <p><b>为什么要蹦床 Activity</b>：Service 不是 [androidx.fragment.app.FragmentActivity]，
 * 无法注册 `ActivityResultLauncher`；而系统选择器的结果只能回到一个 Activity。
 * [AttachmentPickerActivity] 作为透明蹦床代为发起选择，拿到结果后经**显式 Intent**
 * （action + Service 组件，非全局广播）回传给 [AssistantOverlayService]。
 *
 * <p><b>为什么 Service 能拉起 Activity</b>：Android 10 起后台启动 Activity 受限，
 * 但持有 `SYSTEM_ALERT_WINDOW` 且正在显示悬浮窗的应用属于豁免之列；且这里是用户点击
 * 悬浮窗内附件按钮触发的，属用户主动行为。
 *
 * <p><b>回调时机**：与 Activity 宿主一致——每个 URI 回调一次，取消/失败回调一次 null，
 * 均在主线程（[Service.onStartCommand] 在主线程执行）。见 `AssistantAttachments` 契约。
 */
private class OverlayAttachments(private val service: Service) : AssistantAttachments {

  override fun pickFile(onResult: (Uri?) -> Unit) {
    pick(onResult, imageOnly = false)
  }

  override fun pickImage(onResult: (Uri?) -> Unit) {
    pick(onResult, imageOnly = true)
  }

  private fun pick(onResult: (Uri?) -> Unit, imageOnly: Boolean) {
    // ACTION_OPEN_DOCUMENT 给持久读权限；图片用 ACTION_GET_CONTENT（相册图多不在文档树）。
    val pickIntent =
        if (imageOnly) {
          Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
          }
        } else {
          Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
          }
        }

    // 先登记回调，再拉起蹦床——否则结果可能在登记之前就回来（理论窗口很小，但不留这个缝）。
    val requestCode = AssistantOverlayService.registerAttachmentRequest(onResult)
    val trampoline =
        AttachmentPickerActivity.newIntent(service, pickIntent)
            .putExtra(AttachmentPickerActivity.EXTRA_REQUEST_CODE, requestCode)
            // Service 上下文启动 Activity 必须带 NEW_TASK，否则抛
            // "Calling startActivity() from outside of an Activity context requires FLAG_ACTIVITY_NEW_TASK"。
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // **先把悬浮窗收起来**再拉起选择器：展开态的面板是整块系统悬浮窗，会盖在
    // 系统文件选择器之上（`TYPE_APPLICATION_OVERLAY` 层级高于普通 Activity），
    // 用户根本点不到选择器里的条目。收起后只剩 FAB，不挡内容。
    //
    // 结果回来时（[AssistantOverlayService.handleAttachmentResult]）自动展开回面板，
    // 因此用户看到的仍是「点附件 → 选完回来」的连续流程。
    AssistantOverlayService.collapseForAttachmentRequest()

    try {
      service.startActivity(trampoline)
    } catch (e: ActivityNotFoundException) {
      AssistantOverlayService.expandAfterAttachmentRequest()
      // 连蹦床都拉不起来（理论上不会，它是本应用组件）。撤销登记并回 null。
      AssistantOverlayService.cancelAttachmentRequest(requestCode)
      onResult(null)
    }
  }
}

/**
 * 解析蹦床回传的结果，按契约回调。
 *
 * <p>与 [ActivityHost] 的 `deliver` 逻辑一致：同时看 `clipData` 与 `data`
 * （多选时系统只填 `clipData`），每个 URI 回调一次，无有效 URI 时回调一次 null。
 */
internal fun deliverAttachmentResult(
    resultCode: Int,
    data: Intent?,
    onResult: (Uri?) -> Unit,
) {
  if (resultCode != Activity.RESULT_OK || data == null) {
    onResult(null)
    return
  }
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
