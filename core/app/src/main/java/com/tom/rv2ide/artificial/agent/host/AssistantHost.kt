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
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope

/**
 * 助手视图的宿主能力抽象。
 *
 * <p><b>为什么需要它</b>：[com.tom.rv2ide.artificial.agent.FloatingAssistantView] 把两件
 * 不同性质的事混在一起——「面板长什么样、怎么驱动 agent」（视图自身）与「弹窗弹在哪、
 * 设置怎么跳」（宿主环境）。此前后者直接 `new AlertDialog` / `startActivity`，于是
 * 这个视图只能活在 Activity 里。要新增内联页、真全屏、应用外系统悬浮三种宿主，就必须
 * 先把这层宿主能力抽出来，否则每加一种形态都要在 2200 行的视图里再分叉一次。
 *
 * <p><b>为什么 [container] 必须是真实 ViewGroup</b>：面板的定位与拖拽直接依赖父容器的
 * 具体类型——`applyMode()` 把 layoutParams 强转成 `ConstraintLayout.LayoutParams`、
 * 拖拽用 `FrameLayout.LayoutParams`、安全区取 `getRootWindowInsets(parent)`、
 * 挂载用 `parent.addView/post`。这些都不是「宿主能力」而是「视图契约」，抽成接口
 * 反而会掩盖真实的类型依赖。因此接口只保留对真实 ViewGroup 的引用，唯一的替换点是
 * 宽度查询：[widthPx] 取代原来的 `container.resources.displayMetrics.widthPixels`
 * （应用外悬浮没有可用的 displayMetrics 语义时，宿主需要给出自己的宽度）。
 *
 * @see ActivityHost 现有的 Activity 宿主实现，行为与抽象前完全一致。
 */
interface AssistantHost {

  /** 视图与弹窗共用的 Context。 */
  val context: Context

  /** 宿主生命周期作用域；视图销毁后不应再往控件里写数据。 */
  val lifecycleScope: LifecycleCoroutineScope

  /**
   * 挂载面板的真实父容器。
   *
   * <p>必须是 `ConstraintLayout` 套 `FrameLayout`（见 layout 资源）：面板卡片用
   * ConstraintLayout 约束定位，FAB 用 FrameLayout gravity/margin 定位。
   */
  val container: ViewGroup

  /**
   * 面板宽度预算（像素）。
   *
   * <p>替代原先的 `container.resources.displayMetrics.widthPixels`。形态计算
   * （SIDEBAR/DOCKED 的宽度比例）用它作为基准。Activity 宿主返回屏幕宽度；
   * 应用外悬浮等宿主若窗口宽度与屏幕不同，应返回自己窗口的宽度。
   */
  fun widthPx(): Int

  /**
   * 面板高度预算（像素）。与 [widthPx] 对称，窄屏上下分栏时用它算面板高度。
   *
   * <p>默认取屏幕高度；应用外悬浮等宿主若窗口高度与屏幕不同，应覆写。
   */
  fun heightPx(): Int =
      container.resources.displayMetrics.heightPixels - statusBarAllowancePx()

  /**
   * 状态栏高度补偿（像素）。
   *
   * <p>编辑器内容区从状态栏**下方**开始，而面板从窗口顶部开始量。若让位时按整屏高度算，
   * 面板顶边会比编辑器底边低一截，分界线上会出现一条错位的缝。
   */
  fun statusBarAllowancePx(): Int {
    val id = container.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) container.resources.getDimensionPixelSize(id) else 0
  }

  /**
   * 面板打开/收起时通知宿主，让宿主决定是否「让位」。
   *
   * <p><b>为什么需要这条</b>：编辑器的助手面板若只是盖在编辑器上，用户看到的是
   * 「一张贴边的板子压住了代码」——这正是「割裂感」的来源。真并列要求编辑器**主动让出**
   * 空间，而让位是宿主的布局知识（哪个容器、缩多少），视图层不该知道。
   *
   * <p>默认空实现：内联页本身就是页面、真全屏窗口里没有邻居、应用外悬浮没有可让位的内容。
   * 只有「宿主有并列内容」的形态（编辑器）才需要覆写。
   */
  fun onPanelOpened() {}

  /** 面板收起，宿主恢复原布局。与 [onPanelOpened] 对称。 */
  fun onPanelClosed() {}

  /**
   * 请求宿主打开一个文件（会话抽屉文件 Tab 的「点开文件」）。
   *
   * <p><b>为什么走宿主而不是面板自己开</b>：打开方式是宿主的布局知识——编辑器宿主
   * 应该在**编辑器标签页**里打开，主页/真全屏宿主没有编辑器可切。默认空实现：
   * 宿主不支持打开时点击静默无反应（与 [onPanelOpened] 同一套降级约定）。
   */
  fun onOpenFileRequested(file: java.io.File) {}

  /**
   * 宿主让位后，留给面板的比例。
   *
   * <p><b>为什么放在这里而不是各处自算</b>：让位量由宿主决定（它缩编辑器），
   * 面板尺寸由视图决定（它算卡片几何）。两者必须严格互补，否则面板与编辑器会重叠或留缝
   * ——实测过的 bug：编辑器让出下半屏、面板却仍贴右侧满高，右上角就重叠了。
   * 因此把「让位比例」收敛成这一个来源，宿主与视图都读它。
   *
   * <p>返回 (编辑器保留比例, 方向)：宽屏 `0.42` 表示编辑器保留 42% 宽度、面板占右侧 58%；
   * 窄屏 `0.5` 表示编辑器保留 50% 高度、面板占底部 50%。
   */
  fun yieldFraction(): Float = 0.5f

  /** 让位方向：宽屏左右分栏，窄屏上下分栏。阈值与 `layout-sw600dp` 一致。 */
  fun isWideScreen(): Boolean =
      container.resources.displayMetrics.widthPixels / container.resources.displayMetrics.density >=
          600f

  /** 弹窗与设置跳转能力。 */
  val dialogs: AssistantDialogs

  /**
   * 附件选择能力。
   *
   * <p>放在 [AssistantHost] 而不是 [AssistantDialogs]：它不是「摆一个弹窗」，而是
   * **带异步结果的 Activity 启动**。Activity 宿主走 `registerForActivityResult`；
   * 应用外悬浮（Service 宿主）没有 FragmentActivity，只能改走一个蹦床 Activity 再回传，
   * 因此这条能力必须可被宿主替换。
   */
  val attachments: AssistantAttachments
}

/**
 * 发起文件 / 图片选择，并在选择完成后异步回传结果。
 *
 * <p><b>结果契约</b>：选到文件时按**每个** URI 回调一次（保持多选语义——用户一次挑几个
 * 相关文件是常见需求）；用户取消、未选中任何项、设备上没有可处理该 Intent 的 Activity，
 * 或结果无法解析时，回调一次 `null`。调用方应把 `null` 当作「什么都没选」忽略。
 */
interface AssistantAttachments {

  /** 选择任意文件（多选）。 */
  fun pickFile(onResult: (android.net.Uri?) -> Unit)

  /** 选择图片（多选）。 */
  fun pickImage(onResult: (android.net.Uri?) -> Unit)
}

/**
 * 宿主提供的弹窗 / 设置跳转能力。
 *
 * <p>把这几处从视图里抽出来，是因为它们是**唯一**需要离开视图自身控件树才能完成的动作：
 * 其余一切（消息列表、工具条、抽屉）都只操作 binding 里的控件。抽出来之后，非 Activity
 * 宿主可以给出自己的实现（例如应用外悬浮可能需要走系统窗口权限，而不是 Activity 的
 * `startActivity`）。
 *
 * <p><b>为什么方法粒度是「一个业务弹窗」而不是通用的 `showDialog(builder)`</b>：宿主
 * 需要能替换的是**语义**（权限选择、危险工具授权…），而不是「怎么显示一个 AlertDialog」。
 * 若暴露通用 builder，宿主仍然要懂每处弹窗的文案与回调契约，抽象等于没抽。
 */
interface AssistantDialogs {

  /**
   * 单选列表弹窗。选中后回调下标，随后由实现关闭弹窗。
   *
   * <p>用于权限模式三档选择。原实现里回调内先写偏好、刷新标签，最后 `dialog.dismiss()`；
   * 现在由实现统一在回调返回后关闭，调用方无需再持有 dialog 句柄。
   */
  fun showSingleChoice(
      titleRes: Int,
      items: Array<String>,
      checked: Int,
      onChosen: (Int) -> Unit,
  )

  /**
   * 标题 + 正文 + 单个确认按钮的消息弹窗。
   *
   * @param negativeRes 取消按钮文案；为 null 时不显示取消按钮。
   */
  fun showMessage(
      titleRes: Int,
      message: CharSequence,
      positiveRes: Int,
      onPositive: () -> Unit,
      negativeRes: Int? = null,
  )

  /** 确认弹窗（确认 + 取消）。确认后执行 [onConfirm]。 */
  fun showConfirm(
      titleRes: Int,
      message: CharSequence,
      positiveRes: Int,
      onConfirm: () -> Unit,
  )

  /**
   * 危险工具授权弹窗：允许一次 / 始终允许 / 拒绝，且不可取消（点外部或返回键都不关闭）。
   *
   * <p>三个回调对应原实现里的三个按钮动作。**阻塞语义由调用方保留**：调用方在 agent
   * 循环线程上建 latch、post 到主线程调用本方法、再 await——本方法只负责「把弹窗摆出来
   * 并把用户选择回调回去」。
   */
  fun showDangerousToolConfirm(
      toolName: String,
      args: String?,
      onAllowOnce: () -> Unit,
      onAllowAlways: () -> Unit,
      onDeny: () -> Unit,
  )

  /**
   * 向用户展示多选/单选题弹窗，收集答案后回调。
   *
   * <p>阻塞语义由调用方保留：调用方在 agent 循环线程上建 latch、post 到主线程调用本方法、
   * 再 await——本方法只负责「把弹窗摆出来并在用户选择后回调答案」。
   *
   * @param questions 待回答的问题列表
   * @param onAnswer 用户完成作答的回调，参数为与 questions 等长的答案列表
   * @param onCancel 用户取消作答的回调
   */
  fun showUserQuestions(
      questions: List<com.tom.rv2ide.ai.tool.ToolSettingsPort.Question>,
      onAnswer: (List<String>) -> Unit,
      onCancel: () -> Unit,
  )

  /** 服务商 / 模型选择面板。Activity 宿主下仍是 BottomSheetDialog。 */
  fun showModelPicker(onChanged: () -> Unit)

  /** 打开 AI 助手设置屏。 */
  fun openSettings()
}
