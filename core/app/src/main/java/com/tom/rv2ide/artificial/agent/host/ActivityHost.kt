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
) : AssistantHost {

  override fun widthPx(): Int = container.resources.displayMetrics.widthPixels

  override fun onPanelOpened() = applyYield(yield = true)

  override fun onPanelClosed() = applyYield(yield = false)

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

  init {
    // 必须**在 Activity 进入 STARTED 之前**注册（否则 registerForActivityResult 抛
    // IllegalStateException）。ActivityHost 在视图装配期构造（onCreate / onViewCreated），
    // 时机与现有 AssistantInputFeatures.registerPickers() 完全一致。
    val activity = context as? androidx.fragment.app.FragmentActivity
    val contract =
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    if (activity == null) {
      fileLauncher = null
      imageLauncher = null
    } else {
      fileLauncher =
          activity.registerForActivityResult(contract) { result ->
            val cb = pendingFile
            pendingFile = null
            deliver(result, cb)
          }
      imageLauncher =
          activity.registerForActivityResult(contract) { result ->
            val cb = pendingImage
            pendingImage = null
            deliver(result, cb)
          }
    }
  }

  override fun pickFile(onResult: (android.net.Uri?) -> Unit) {
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
