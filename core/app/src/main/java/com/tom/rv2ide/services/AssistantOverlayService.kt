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

package com.tom.rv2ide.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.coroutineScope
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agent.AssistantOrchestratorProvider
import com.tom.rv2ide.artificial.agent.FloatingAssistantView
import com.tom.rv2ide.artificial.agent.host.OverlayHost
import com.tom.rv2ide.artificial.agent.host.deliverAttachmentResult
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.resources.R as ResourcesR
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用外系统悬浮助手的前台服务。
 *
 * <p><b>与「窗口内悬浮」（[FloatingAssistantView] 挂在 Activity 的 container 上）的区别</b>：
 * 本服务通过 `WindowManager` 把一个 `TYPE_APPLICATION_OVERLAY` 窗口挂到**系统窗口层**，
 * 因此退出 ACS、切到其它应用后它仍在屏幕上。代价是窗口没有 Activity 的 token：
 * 弹窗、输入法、跳转 Activity、附件选择都不能沿用 Activity 里的那套做法，必须逐项适配
 * （见 [OverlayHost] 及其弹窗 / 附件实现）。
 *
 * <p><b>生命周期</b>：`startForeground` 在 `onStartCommand` 里**最先**调用——
 * targetSdk=28 上虽然前台服务限制较宽，但一旦系统在 5 秒内看不到 `startForeground`，
 * 仍可能因 ANR/杀进程把悬浮窗连同服务一起收走。`START_STICKY` 让系统在资源紧张时
 * 重启服务；重启后 [onStartCommand] 收到的是 null intent，此时按「重新显示」处理。
 *
 * <p><b>视图摘除必须自己做</b>：`FloatingAssistantView.dispose()` 只取消进行中的运行
 * （见其实现），**不会**把视图从 WindowManager 摘下来。若在 [onDestroy] 里只调
 * `dispose()`，窗口会泄漏并继续显示在一个已死的服务上——点击它时回调无处可去，
 * 表现为「悬浮窗还在但点了没反应」。因此这里显式 `removeView`。
 */
class AssistantOverlayService : Service(), LifecycleOwner {

  private lateinit var windowManager: WindowManager

  /** 悬浮窗根容器；null 表示当前未显示。 */
  private var rootView: FrameLayout? = null

  /** 助手视图；null 表示未创建。 */
  private var assistant: FloatingAssistantView? = null

  /** 窗口参数；保留引用以便输入法出现/消失时切换高度与焦点 flag。 */
  private var layoutParams: WindowManager.LayoutParams? = null

  /**
   * 拖动句柄（收起态的 FAB）。
   *
   * <p>保留引用只为在摘窗时把触摸监听清掉（[detachOverlay]）：视图从 WindowManager 移除后，
   * 监听闭包仍被 View 持有，不主动置 null 会把它连同 Service 引用一起留住，直到 GC 才发现
   * 无法回收。规模很小，但清掉是零成本的正确做法。
   */
  private var dragHandle: View? = null

  /**
   * 收起态窗口落点（屏幕绝对坐标）。
   *
   * <p>展开态窗口是 `match_parent`、必须归零铺满，收起时再回到这里——否则用户把 FAB 拖到
   * 屏幕中部后打开面板，偏移会让全屏面板整体位移、右边/下边被推出屏幕（见
   * [installOverlaySizeHandling]）。
   */
  private var collapsedX = 0
  private var collapsedY = 0

  /** 当前窗口是否处于展开（面板全屏）态。用于避免尺寸监听重复应用坐标。 */
  private var overlayExpanded = false

  /**
   * 本服务作为 [LifecycleOwner] 的 registry。
   *
   * <p>`FloatingAssistantView` 需要 [LifecycleCoroutineScope] 驱动异步加载（会话回放、
   * 模型目录等）。这里让 Service 实现 [LifecycleOwner] 并持有标准 [LifecycleRegistry]，
   * 再经 ktx 的 `lifecycleScope` 扩展取作用域（见 [serviceScope]）。用标准 registry 而不是
   * 自写实现：`LifecycleCoroutineScopeImpl` 依赖注册观察者来在销毁时取消作用域，
   * 自写 Lifecycle 若忽略 `addObserver` 会导致作用域永不取消、协程泄漏。
   */
  private val lifecycleRegistry = LifecycleRegistry(this)

  override val lifecycle: Lifecycle
    get() = lifecycleRegistry

  /**
   * 宿主生命周期作用域。
   *
   * <p>用 [androidx.lifecycle.coroutineScope] 扩展（`Lifecycle.coroutineScope`，定义在
   * `lifecycle-common` 且为 public）：它内部创建并注册一个随 [lifecycleRegistry] 销毁而
   * 取消的作用域。刻意不用 `lifecycle-runtime-ktx` 的 `lifecycleScope`——那需要该依赖
   * 显式可见（本模块未声明，只有传递依赖，不稳妥）。
   */
  private val serviceScope: LifecycleCoroutineScope
    get() = lifecycleRegistry.coroutineScope

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    // 先置 STARTED，保证取到的作用域从创建起就是活跃的。
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 先起前台，再谈窗口。顺序反了会有被杀的风险（见类注释）。
    startForeground(NOTIFICATION_ID, buildNotification())

    // 蹦床 Activity 回传的附件结果：分发给登记过的回调，**不**走窗口/权限分支。
    if (intent?.action == ACTION_ATTACHMENT_RESULT) {
      handleAttachmentResult(intent)
      // 若窗口已不在（用户在选文件期间关掉了悬浮），处理完结果就收摊，
      // 不要留下一个没有窗口的前台服务空转。
      if (rootView == null) {
        stopSelf()
        return START_NOT_STICKY
      }
      return START_STICKY
    }

    when (intent?.action) {
      ACTION_HIDE -> {
        detachOverlay()
        stopSelf()
        return START_NOT_STICKY
      }
      // 重启后 intent 为 null：按「重新显示」处理。
      else -> {
        if (!Settings.canDrawOverlays(this)) {
          // 没有悬浮权限就没有任何可做的：不起窗口、不常驻。
          // 权限申请由入口处（设置/悬浮开关）负责，这里只做最后一道防线，
          // 否则会留下一个永远显示不出窗口的前台服务。
          stopSelf()
          return START_NOT_STICKY
        }
        attachOverlay()
      }
    }
    return START_STICKY
  }

  /**
   * 窗口参数。
   *
   * <p><b>为什么是 `TYPE_APPLICATION_OVERLAY`</b>：Android 8.0 (API 26) 起
   * `TYPE_PHONE` / `TYPE_SYSTEM_ALERT` 等旧类型对普通应用一律被系统拒绝（抛
   * `BadTokenException`），`TYPE_APPLICATION_OVERLAY` 是 O+ 上唯一可用的应用悬浮窗类型。
   * 本仓库 minSdk=26，因此无需按版本分支。
   *
   * <p><b>为什么尺寸是 WRAP_CONTENT 而不是 MATCH_PARENT（评审严重项）</b>：全屏窗口
   * **会吞掉落在其范围内、但没有子 View 承接的触摸**——Android 的输入分发把事件投给该
   * 坐标最顶层的窗口，窗口内的 View 树不消费时事件被丢弃，**不会**下传给底下的窗口。
   * 若窗口铺满全屏，收起态（只剩 FAB）时用户点其它应用的任何位置都会被本窗口吃掉，
   * 等于毁掉「应用外悬浮」。把窗口收缩到内容大小后，透明区域落在**窗口之外**，配合
   * `FLAG_NOT_TOUCH_MODAL`，这些区域的触摸就透传给底层应用。
   *
   * <p>收缩后窗口尺寸随内容变化：面板收起时布局里只有 FAB（`assistantOverlay` 为 GONE），
   * 窗口自动缩到 FAB 大小；面板展开时 `assistantOverlay` 是 `match_parent`，窗口随之
   * 长到全屏——展开态本就是「用户正在用面板」，此时占据全屏是可接受的。
   *
   * <p><b>FLAG_NOT_TOUCH_MODAL</b>：允许窗口**之外**的触摸下传。与 WRAP_CONTENT 配合
   * 才生效（全屏窗口没有「之外」）。它不影响 `FLAG_NOT_FOCUSABLE`，也不影响输入法。
   *
   * <p><b>FLAG_NOT_FOCUSABLE 的取舍</b>：悬浮窗默认不能获取焦点，否则会抢走其它应用的
   * 输入。但助手的输入框需要打字——[installImeHandling] 在输入框获得焦点时**临时**
   * 清掉这个 flag、并切到 `SOFT_INPUT_ADJUST_PAN`，失焦后恢复。
   */
  private fun buildLayoutParams(): WindowManager.LayoutParams =
      WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
          // 初始不可聚焦；输入时由 installImeHandling 临时清掉。
          // NOT_TOUCH_MODAL 让窗口之外的触摸透传（配合 WRAP_CONTENT 才有意义）。
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
          PixelFormat.TRANSLUCENT,
      ).apply {
        // 收起态窗口很小，BOTTOM|END 让 FAB 默认落在右下角（与全屏形态下的默认落点一致），
        // 而不是贴在左上角。展开态窗口长到全屏，该 gravity 退化为无效果。
        gravity = Gravity.BOTTOM or Gravity.END
        // 布局时避开刘海/挖孔，避免 FAB 落到被遮挡的区域。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          layoutInDisplayCutoutMode =
              WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
      }

  /**
   * 挂载悬浮窗。
   *
   * <p>用 [FrameLayout] 作根：面板卡片与 FAB 都用 gravity/margin 定位，与 Activity 宿主
   * 的容器类型一致（见 `layout_ai_assistant.xml` 的契约）。容器不设固定尺寸——窗口按内容
   * 测量（见 [buildLayoutParams]），收起时缩到 FAB、展开时长到全屏。
   */
  private fun attachOverlay() {
    if (rootView != null) {
      return
    }
    val container = FrameLayout(this)
    val params = buildLayoutParams()
    try {
      windowManager.addView(container, params)
    } catch (e: WindowManager.BadTokenException) {
      // 权限在检查与 addView 之间被撤销，或窗口类型被系统拒绝。
      // 不崩：收起服务，由入口处重新引导授权。
      stopSelf()
      return
    }
    rootView = container
    layoutParams = params

    val host = OverlayHost(this, serviceScope, container)
    // 注入进程级共享 orchestrator（AssistantOrchestratorProvider）：应用外悬浮跑在 Service 里、
    // **没有 Activity**，取不到 MainViewModel；若在这里自建实例，会话列表、正在跑的任务、
    // 上下文用量、工作区会与应用内的悬浮助手/内联页/真全屏各说各话——这正是「三者割裂」的
    // 根因之一。provider 用 application context 构造，与进程同寿，不依赖任何 Activity。
    val view =
        FloatingAssistantView(
            host,
            FloatingAssistantView.Mode.SIDEBAR,
            AssistantOrchestratorProvider.get(),
        )
    view.attach()
    view.setWorkspace(currentWorkspace())
    assistant = view
    isShowing = true

    // 输入框获得/失去焦点时切换窗口的焦点 flag，使输入法能正常弹出。
    installImeHandling()
    // 收起态下拖动 FAB 移动整个窗口；未拖动的抬手打开面板。见 [installDrag]。
    installDrag()
    // 展开/收起时在「归零铺满」与「回到拖动落点」之间切换窗口坐标。见该方法。
    installOverlaySizeHandling()
  }

  /**
   * 面板展开/收起时切换窗口坐标。
   *
   * <p><b>为什么必须做</b>：窗口尺寸随内容变化（收起态缩到 FAB、展开态长到全屏，见
   * [buildLayoutParams]）。收起态需要 `params.x/y` = 用户拖动的落点；展开态是全屏，
   * 必须归零，否则偏移会把整块面板推出屏幕。用一个尺寸监听在两种状态间切换，
   * 比在每个打开/关闭调用点手动改坐标更稳——面板的收起/展开有多条路径（FAB、最小化、
   * 关闭、返回键），漏掉任何一条都会留下错位的窗口。
   */
  private fun installOverlaySizeHandling() {
    val container = rootView ?: return
    // 用**面板是否可见**判定展开态，而不是比较容器宽度：overlay 窗口可能被系统栏 insets
    // 收窄，`container.width >= 屏幕宽` 会判 false，导致展开后不归零、面板被偏移推出屏幕。
    // 面板可见性就是「展开」的定义，不受任何测量差异影响。
    val panel = container.findViewById<View>(R.id.assistantOverlay) ?: return
    container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
      val lp = layoutParams ?: return@addOnLayoutChangeListener
      val expanded = panel.isVisible
      if (expanded == overlayExpanded) {
        return@addOnLayoutChangeListener
      }
      overlayExpanded = expanded
      if (expanded) {
        // 展开：**窗口自己长到全屏**。
        //
        // 不能只让卡片 match_parent：窗口是 WRAP_CONTENT（见 [buildLayoutParams]，
        // 为避免全屏吞触摸），窗口按内容测量，卡片在 wrap 窗口里的 match_parent
        // 会退化成 wrap —— 面板永远只有内容大小，标题栏那个「全屏」键点了毫无反应。
        // 实测：收起态窗口 220×220（FAB），点开后只有 994×968 挤在右下角。
        //
        // 因此展开态必须把窗口尺寸也一起改掉，卡片才真正有铺满的空间。
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
      } else {
        // 收起：窗口缩回 FAB 大小，并回到用户拖动的落点。
        // 用 WRAP_CONTENT 让窗口自然贴合 FAB，而不是写死 56dp——不同主题/字号下
        // FAB 尺寸可能不同，写死会露出透明边或裁掉圆角。
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.START or Gravity.TOP
        lp.x = collapsedX
        lp.y = collapsedY
      }
      try {
        windowManager.updateViewLayout(container, lp)
      } catch (e: IllegalArgumentException) {
        // 视图已被移除。忽略。
      }
    }
  }

  /**
   * 收起态拖动：按住 FAB 移动整个系统窗口，落点持久化。
   *
   * <p><b>为什么挂在 FAB 上而不是整个容器</b>：容器在展开态是 `match_parent` 全屏，挂它会
   * 让面板内的一切触摸（滚动消息、点输入框）都被当成拖动。FAB 只在收起态可见，正是「可以
   * 挪动悬浮入口」的唯一时机，因此把它当作拖动手柄。
   *
   * <p><b>为什么不抢焦点</b>：拖动只调 `WindowManager.updateViewLayout` 改窗口坐标，
   * **完全不动 `layoutParams.flags`**。窗口仍带 `FLAG_NOT_FOCUSABLE`（见
   * [buildLayoutParams]），因此拖动不会把输入焦点从底层应用抢过来；输入框需要焦点时仍由
   * [installImeHandling] 临时清 flag 处理。两条路径互不干扰。
   *
   * <p><b>拖动与点击的区分</b>：与 [FloatingAssistantView.setUpDragging] 同一约定——按下先
   * 不判定，位移超过 touchSlop 才进入拖动；否则抬手仍算点击、打开面板。若用 ACTION_DOWN
   * 直接启动拖动，轻点就变成「拖动 0 像素」，面板再也打不开。
   *
   * <p><b>坐标系</b>：默认 gravity 是 `BOTTOM or END`，`params.x/y` 是**从右下角**量的偏移，
   * 与拖动需要的「从左上角量的绝对坐标」相反。因此先把窗口就地换算成 `START or TOP`
   * （用 [View.getLocationOnScreen] 取当前位置，视觉上不动），后续拖动数学才一致。
   *
   * <p><b>与视图自带监听的关系</b>：[FloatingAssistantView.attach] 也会给 FAB 装一个
   * `OnTouchListener`（在宿主父容器内拖动 FAB + 抬手打开面板）。本方法在其后调用、
   * **覆盖**它，这是刻意的：应用外悬浮的窗口尺寸是 WRAP_CONTENT、恰好等于 FAB，
   * 视图那套「在父容器内挪 FAB」在这里拖不动任何东西（父容器就是窗口本身）；真正要动的是
   * **窗口**，只有宿主（本服务）能做。覆盖后仍需保留「点击打开面板」——由本监听的
   * ACTION_UP 分支补上（[openPanel]），因此行为与改动前一致。
   */
  private fun installDrag() {
    val container = rootView ?: return
    val fab = container.findViewById<View>(R.id.assistantFab) ?: return
    dragHandle = fab
    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
    var downRawX = 0f
    var downRawY = 0f
    var startX = 0
    var startY = 0
    var dragging = false

    restoreOverlayPosition(container)
    normalizeOverlayGravity(container)

    fab.setOnTouchListener { v, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          val lp = layoutParams ?: return@setOnTouchListener false
          downRawX = event.rawX
          downRawY = event.rawY
          startX = lp.x
          startY = lp.y
          dragging = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - downRawX).toInt()
          val dy = (event.rawY - downRawY).toInt()
          if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
            dragging = true
          }
          if (dragging) {
            moveOverlayTo(container, startX + dx, startY + dy)
          }
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (dragging) {
            // 拖动结束不回弹，记住落点——用户摆哪儿就是哪儿。
            persistOverlayPosition()
          } else {
            // 未进入拖动 = 点击：打开面板（行为与改动前一致）。
            // 先 performClick 让无障碍服务收到一次标准点击事件。
            v.performClick()
            openPanel()
          }
          dragging = false
          true
        }
        else -> false
      }
    }
  }

  /**
   * 把窗口移到屏幕坐标 (x, y)，越界则钳制到合法范围。
   *
   * <p>窗口是 WRAP_CONTENT、收起态尺寸就是 FAB（见 [buildLayoutParams]），因此用
   * `container.width/height` 即可算出右/下边界。四周留 [OVERLAY_EDGE_MARGIN_DP] 边距：
   * 贴边后圆角与屏幕边缘相切、很难再按住拖回来。
   *
   * <p>坐标写进 `params.x/y` 后**必须** `updateViewLayout` 才生效；窗口在更新前被系统移除
   * （权限撤销等）会抛 [IllegalArgumentException]，忽略即可——服务正在收尾，拖动已无意义。
   */
  private fun moveOverlayTo(container: View, x: Int, y: Int) {
    val lp = layoutParams ?: return
    val clamped = clampToScreen(container, x, y)
    // 钳制后的落点同时记进 collapsedX/Y：这是收起态的目标位置，展开后再收起要回到这里。
    collapsedX = clamped[0]
    collapsedY = clamped[1]
    lp.gravity = Gravity.START or Gravity.TOP
    lp.x = collapsedX
    lp.y = collapsedY
    try {
      windowManager.updateViewLayout(container, lp)
    } catch (e: IllegalArgumentException) {
      // 视图已被移除（服务正在收尾）。忽略。
    }
  }

  /**
   * 把屏幕坐标 (x, y) 钳制到窗口可见范围内，返回 `[x, y]`。
   *
   * <p>四周留 [OVERLAY_EDGE_MARGIN_DP]：贴边后圆角与屏幕边缘相切、很难再按住拖回来。
   *
   * <p>容器尚未布局（`width/height == 0`）时按 FAB 的已知尺寸 56dp 兜底——恢复保存的落点
   * 发生在 `post` 里、通常已布局，但屏幕旋转/首次挂载的时序不保证；不兜底会算出
   * `maxX = 屏幕宽 - margin`，把窗口错误地钳到最右。
   *
   * <p>恢复路径也要过这里（评审 M4）：保存的坐标可能来自更宽的屏幕（横竖屏切换、
   * 分辨率变化），不钳制会把 FAB 恢复到屏幕外，用户既看不见也点不到。
   */
  private fun clampToScreen(container: View, x: Int, y: Int): IntArray {
    val margin = dp(OVERLAY_EDGE_MARGIN_DP)
    val metrics = resources.displayMetrics
    val w = if (container.width > 0) container.width else dp(56)
    val h = if (container.height > 0) container.height else dp(56)
    val maxX = (metrics.widthPixels - w - margin).coerceAtLeast(margin)
    val maxY = (metrics.heightPixels - h - margin).coerceAtLeast(margin)
    return intArrayOf(x.coerceIn(margin, maxX), y.coerceIn(margin, maxY))
  }

  /**
   * 把默认的 `BOTTOM or END` gravity 就地换算为 `START or TOP`，使 [moveOverlayTo] 的拖动
   * 数学成立。用 [View.getLocationOnScreen] 取窗口当前屏幕坐标，视觉位置不变。
   *
   * <p>在 `post` 里做：必须等窗口完成首次布局，`getLocationOnScreen` 才返回真实值。
   */
  private fun normalizeOverlayGravity(container: View) {
    container.post {
      val lp = layoutParams ?: return@post
      if (lp.gravity == (Gravity.START or Gravity.TOP)) {
        return@post
      }
      val location = IntArray(2)
      container.getLocationOnScreen(location)
      lp.gravity = Gravity.START or Gravity.TOP
      lp.x = location[0]
      lp.y = location[1]
      // 没有已保存落点时，默认位置就是「当前 FAB 所在处」，记下来供展开后收起回到这里。
      collapsedX = location[0]
      collapsedY = location[1]
      try {
        windowManager.updateViewLayout(container, lp)
      } catch (e: IllegalArgumentException) {
        // 视图已被移除。忽略。
      }
    }
  }

  /**
   * 恢复上次拖动的落点（重启保持）。
   *
   * <p>用独立的键 `ai_assistant_overlay_x/y`，**不与**窗口内 FAB 的
   * `ai_assistant_fab_x/y` 共用：两者的坐标系不同（本服务是屏幕绝对坐标，窗口内 FAB 是
   * 相对父容器的偏移），共用会把一个位置写坏另一个。偏好文件仍走默认 SharedPreferences。
   */
  private fun restoreOverlayPosition(container: View) {
    val prefs =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
    if (!prefs.contains(PREF_OVERLAY_X) || !prefs.contains(PREF_OVERLAY_Y)) {
      return
    }
    val x = prefs.getInt(PREF_OVERLAY_X, 0)
    val y = prefs.getInt(PREF_OVERLAY_Y, 0)
    container.post {
      val lp = layoutParams ?: return@post
      // 必须钳制（评审 M4）：保存的坐标可能来自更宽的屏幕（横竖屏切换、分辨率变化），
      // 直接套用会把 FAB 恢复到屏幕外，用户既看不见也点不到。
      val clamped = clampToScreen(container, x, y)
      lp.gravity = Gravity.START or Gravity.TOP
      lp.x = clamped[0]
      lp.y = clamped[1]
      collapsedX = clamped[0]
      collapsedY = clamped[1]
      try {
        windowManager.updateViewLayout(container, lp)
      } catch (e: IllegalArgumentException) {
        // 视图已被移除。忽略。
      }
    }
  }

  /** 保存当前落点（屏幕绝对坐标）。 */
  private fun persistOverlayPosition() {
    val lp = layoutParams ?: return
    androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        .edit()
        .putInt(PREF_OVERLAY_X, lp.x)
        .putInt(PREF_OVERLAY_Y, lp.y)
        .apply()
  }

  /** 打开助手面板（点击 FAB 时）。已展开则不重复打开，避免重置当前会话回放。 */
  private fun openPanel() {
    val view = assistant ?: return
    if (!view.isOpen) {
      view.open()
    }
  }

  /** dp → px。 */
  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  /**
   * 输入法适配。
   *
   * <p><b>为什么必须做</b>：overlay 窗口默认带 `FLAG_NOT_FOCUSABLE`（否则会抢走其它应用的
   * 输入焦点，这是系统对悬浮窗的默认约束）。但助手的输入框需要打字——带这个 flag 时点击
   * 输入框不会唤起输入法。做法是：输入框获得焦点时**临时**清掉该 flag，失焦后恢复。
   *
   * <p><b>为什么用 `ADJUST_PAN` 而不是 `ADJUST_RESIZE`</b>：窗口尺寸是 WRAP_CONTENT
   * （见 [buildLayoutParams]，为避免全屏吞触摸）。`ADJUST_RESIZE` 的语义是「缩小窗口
   * 高度给输入法让位」，但它对 wrap_content 窗口不可靠——窗口高度由内容决定、没有可压缩
   * 的余量，系统可能什么也不做，输入框被输入法盖住。`ADJUST_PAN` 改为「平移整个窗口让
   * 焦点控件可见」，对 wrap_content 窗口有效。代价是窗口整体上移、FAB 可能被推到屏幕外，
   * 但输入态下面板本就在最前、FAB 不可见，可接受。
   *
   * <p>输入框 id 从 layout 资源取；拿不到时跳过（不影响其它功能）。
   */
  private fun installImeHandling() {
    val container = rootView ?: return
    val input = container.findViewById<View>(R.id.assistantInput) ?: return
    input.setOnFocusChangeListener { _, hasFocus ->
      val params = layoutParams ?: return@setOnFocusChangeListener
      if (hasFocus) {
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
      } else {
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
      }
      try {
        windowManager.updateViewLayout(container, params)
      } catch (e: IllegalArgumentException) {
        // 视图已被移除（服务正在收尾）。忽略。
      }
      if (hasFocus) {
        // 有时清 flag 后需要主动请求一次输入法才会弹出。
        input.post {
          val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
          imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
      }
    }
  }

  /** 摘除悬浮窗。必须在 [onDestroy] 里调用，见类注释。 */
  private fun detachOverlay() {
    assistant?.dispose()
    assistant = null
    // 清掉拖动监听，断开「View → 监听闭包 → Service」的引用链（见 dragHandle）。
    dragHandle?.setOnTouchListener(null)
    dragHandle = null
    layoutParams = null
    rootView?.let { view ->
      try {
        windowManager.removeView(view)
      } catch (e: IllegalArgumentException) {
        // 视图已被系统移除（例如权限被撤销时系统清理了窗口）。忽略。
      }
    }
    rootView = null
    isShowing = false
  }

  override fun onDestroy() {
    detachOverlay()
    // 置为 DESTROYED：lifecycleScope 随之取消全部子协程，避免摘窗后仍有协程写控件。
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    pendingAttachments.clear()
    super.onDestroy()
  }

  /**
   * 当前工作区。
   *
   * <p>悬浮形态下没有 Activity 提供项目上下文，只能从「最近打开的项目」推得——与
   * 主屏助手（`MainFragment.currentWorkspace()`）同源，保证两种形态看到的是同一个项目。
   */
  private fun currentWorkspace(): File? {
    val path = GeneralPreferences.lastOpenedProject
    if (path.isEmpty() || path == GeneralPreferences.NO_OPENED_PROJECT) {
      return null
    }
    val dir = File(path)
    return if (dir.exists() && dir.isDirectory) dir else null
  }

  override fun onBind(intent: Intent?): IBinder? = null

  // ---- 附件结果回传 ----

  /**
   * 处理蹦床 Activity 回传的附件结果。
   *
   * <p>按请求编号取出登记的回调并分发。`deliverAttachmentResult` 负责解析
   * `clipData`/`data` 并按契约回调（每个 URI 一次，取消/失败一次 null）。
   */
  private fun handleAttachmentResult(intent: Intent) {
    val requestCode = intent.getIntExtra(EXTRA_ATTACHMENT_REQUEST_CODE, -1)
    val callback = pendingAttachments.remove(requestCode) ?: return
    val resultCode = intent.getIntExtra(EXTRA_ATTACHMENT_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
    @Suppress("DEPRECATION")
    val data = intent.getParcelableExtra<Intent>(EXTRA_ATTACHMENT_DATA)
    deliverAttachmentResult(resultCode, data, callback)
  }

  // ---- 通知 ----

  private fun createNotificationChannel() {
    val channel =
        NotificationChannel(
            CHANNEL_ID,
            getString(ResourcesR.string.title_assistant_overlay_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
    NotificationManagerCompat.from(this).createNotificationChannel(channel)
  }

  /**
   * 常驻通知。
   *
   * <p>点它回到应用：悬浮窗本身没有任务栈入口，用户想回到完整界面时只能靠这个通知，
   * 否则「悬浮窗开着但找不到主界面」。
   */
  private fun buildNotification(): Notification {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent =
        launch?.let {
          PendingIntent.getActivity(
              this,
              0,
              it,
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
        }
    return Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(ResourcesR.drawable.ic_launcher_notification)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(ResourcesR.string.title_assistant_overlay_notification))
        .setContentIntent(contentIntent)
        .setOngoing(true)
        .build()
  }

  companion object {

    /**
     * 通知渠道 id。与 Gradle 构建服务分开：两者的重要级别与用户预期不同，
     * 共用渠道会让「关掉构建通知」把悬浮通知也一起关掉。
     */
    private const val CHANNEL_ID = "assistant_overlay"

    /** 通知 id。与 Gradle 服务的通知 id 错开，避免互相覆盖。 */
    private const val NOTIFICATION_ID = 17572

    /**
     * 悬浮窗拖动落点（屏幕绝对坐标，像素）。
     *
     * <p>键名与窗口内 FAB 的 `ai_assistant_fab_x/y` 区分开：坐标系不同（见
     * [restoreOverlayPosition]），共用会互相写坏。
     */
    private const val PREF_OVERLAY_X = "ai_assistant_overlay_x"
    private const val PREF_OVERLAY_Y = "ai_assistant_overlay_y"

    /** 拖动时窗口四周保留的最小边距（dp）。 */
    private const val OVERLAY_EDGE_MARGIN_DP = 8

    const val ACTION_SHOW = "com.tom.rv2ide.action.SHOW_ASSISTANT_OVERLAY"
    const val ACTION_HIDE = "com.tom.rv2ide.action.HIDE_ASSISTANT_OVERLAY"

    /**
     * 悬浮窗当前是否显示。
     *
     * <p>供设置入口显示「悬浮到系统」/「关闭悬浮」。`Settings.canDrawOverlays` 只反映
     * **权限**，不反映**服务是否在跑**——两者必须分开判断，否则「有权限但没开」会被
     * 误显示成「已开启」。
     *
     * <p>`@Volatile`：写在主线程（`onStartCommand`/`onDestroy`），读在设置页（主线程），
     * 本无并发；标注只是防御将来有人在别的线程读取。
     */
    @Volatile var isShowing: Boolean = false
      private set

    /** 蹦床 Activity 回传附件结果的 action 与 extra 键。 */
    const val ACTION_ATTACHMENT_RESULT = "com.tom.rv2ide.action.ASSISTANT_ATTACHMENT_RESULT"
    const val EXTRA_ATTACHMENT_REQUEST_CODE = "com.tom.rv2ide.extra.ATTACHMENT_REQUEST_CODE"
    const val EXTRA_ATTACHMENT_RESULT_CODE = "com.tom.rv2ide.extra.ATTACHMENT_RESULT_CODE"
    const val EXTRA_ATTACHMENT_DATA = "com.tom.rv2ide.extra.ATTACHMENT_DATA"

    /**
     * 待回传的附件回调，按请求编号索引。
     *
     * <p>用 [ConcurrentHashMap] 而非普通 Map：登记发生在主线程（用户点按钮），回传经
     * `onStartCommand` 也在主线程，但用并发容器可避免将来有人在别的线程发起时的隐患，
     * 且成本可忽略。同一时刻通常只有一条待回传记录。
     */
    private val pendingAttachments = ConcurrentHashMap<Int, (android.net.Uri?) -> Unit>()

    private val requestCounter = AtomicInteger(1)

    /** 登记一个附件回调，返回请求编号（随蹦床 Intent 传递，结果按它路由）。 */
    fun registerAttachmentRequest(onResult: (android.net.Uri?) -> Unit): Int {
      val code = requestCounter.getAndIncrement()
      pendingAttachments[code] = onResult
      return code
    }

    /** 撤销登记（拉起蹦床失败时调用），避免回调槽泄漏。 */
    fun cancelAttachmentRequest(requestCode: Int) {
      pendingAttachments.remove(requestCode)
    }

    /**
     * 拉起悬浮服务。
     *
     * <p>调用方应先确认已获得 `SYSTEM_ALERT_WINDOW` 权限（见
     * `PermissionUtils.checkDisplayOverOtherAppsPermission`）；本服务在
     * [onStartCommand] 里也会再查一次作为兜底。
     */
    fun show(context: Context) {
      val intent = Intent(context, AssistantOverlayService::class.java).setAction(ACTION_SHOW)
      ContextCompat.startForegroundService(context, intent)
    }

    /** 关闭悬浮服务（摘窗 + 停前台）。 */
    fun hide(context: Context) {
      val intent = Intent(context, AssistantOverlayService::class.java).setAction(ACTION_HIDE)
      // 用 startService 而不是 startForegroundService：这是一个「停止」动作，
      // 走 startForegroundService 会要求调用方在 5 秒内 startForeground，
      // 而本分支恰恰不打算继续常驻。
    context.startService(intent)
    }
  }
}
