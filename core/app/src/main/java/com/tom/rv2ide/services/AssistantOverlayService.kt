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
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.coroutineScope
import com.tom.rv2ide.R
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
    val view = FloatingAssistantView(host, FloatingAssistantView.Mode.SIDEBAR)
    view.attach()
    view.setWorkspace(currentWorkspace())
    assistant = view
    isShowing = true

    // 输入框获得/失去焦点时切换窗口的焦点 flag，使输入法能正常弹出。
    installImeHandling()
  }

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
