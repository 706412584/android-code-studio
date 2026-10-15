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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agent.AssistantOrchestratorProvider
import com.tom.rv2ide.resources.R as ResourcesR

/**
 * AI 运行期保活的前台服务。
 *
 * <p><b>解决什么问题</b>：agent 循环跑在普通后台线程上，息屏后 CPU 进入 suspend，
 * 循环被冻结；进程也没有前台服务保护，随时可能被 ROM 清掉。本服务在 AI 运行
 * （或后台 shell 任务）期间持 `PARTIAL_WAKE_LOCK` 并以前台服务身份常驻，
 * 让息屏后的长任务能继续跑完。
 *
 * <p><b>为什么是引用计数驱动的</b>：运行与后台任务可以重叠、可以首尾相接
 * （见 [com.tom.rv2ide.artificial.agent.AgentKeepAlive]）。服务本身不判断
 * 「该不该活」，只在计数跨过 0/1 边界时被启动或停止，避免先结束的一方把
 * 仍需保活的另一方一起关掉。
 *
 * <p><b>wakelock 由服务持有</b>：`onCreate` 申请、`onDestroy` 释放。挂在服务上
 * 而不是各调用方，是因为服务的生命周期恰好就是「需要保活」的区间；且进程被系统
 * 杀死时内核会回收该进程持有的所有 wakelock，不存在跨进程泄漏。
 *
 * <p><b>START_NOT_STICKY</b>：进程被杀后不自动重启——此时 agent 循环已不存在，
 * 重启一个空转的前台服务只会白耗电并留下一条用户看不懂的通知。
 *
 * <p><b>通知带「停止」</b>：息屏后用户看不见界面，唯一能终止跑飞的 agent 的入口
 * 就是通知栏。停止动作经 [ACTION_STOP] 走 `startService`（而非 `startForegroundService`：
 * 这是「停止」语义，且本分支会立即 `stopSelf`，不满足 5 秒内必须 startForeground 的约束）。
 */
class AgentRunService : Service() {

  /** 持有中的 wakelock；null 表示未持有。只在主线程读写（服务回调都在主线程）。 */
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    acquireWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 先起前台再谈其它。顺序反了会在 5 秒窗口内被杀（同 AssistantOverlayService 的教训）。
    startForeground(NOTIFICATION_ID, buildNotification())

    if (intent?.action == ACTION_STOP) {
      // 停止两件事：正在跑的 agent 运行，与后台 shell 任务（后者能独立于运行存活）。
      // 任一步骤抛异常都不能挡住 stopSelf——否则通知会永远留在通知栏。
      try {
        val orchestrator = AssistantOrchestratorProvider.get()
        orchestrator.cancelAll()
        orchestrator.stopAllBackgroundTasks()
      } catch (t: Throwable) {
        com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "停止按钮执行失败", t, null)
      }
      // 显式摘掉前台通知：只调 stopSelf 时，部分 ROM（实测黑鲨 Android 10）会把
      // 通知留在通知栏，直到服务彻底销毁才收——用户点了「停止」却仍看到「AI 正在工作」。
      // STOP_FOREGROUND_REMOVE 让通知随本调用立即消失，与用户的预期一致。
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    return START_NOT_STICKY
  }

  override fun onDestroy() {
    // 摘通知放在最前：无论服务因何销毁（正常停止 / 系统回收 / 异常），
    // 都不该在通知栏留下一条指向已死服务的「AI 正在工作」。
    try {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } catch (t: Throwable) {
      // 极少数情况下服务未进入前台就销毁（启动失败），stopForeground 会抛
      // IllegalArgumentException；通知本来就不存在，忽略即可。
    }
    releaseWakeLock()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun acquireWakeLock() {
    if (wakeLock != null) {
      return
    }
    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    // 无超时版本：释放由 onDestroy 保证。超时版本会在长任务中途静默放锁，
    // 表现为「跑了 10 分钟后又被冻住」——比不放锁更难排查。
    wakeLock =
        powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also { it.acquire() }
  }

  private fun releaseWakeLock() {
    val lock = wakeLock ?: return
    wakeLock = null
    try {
      if (lock.isHeld) {
        lock.release()
      }
    } catch (t: Throwable) {
      // 释放失败不影响服务收尾（进程退出时内核会兜底回收）。
      com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "释放 wakelock 失败", t, null)
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }
    val channel =
        NotificationChannel(
            CHANNEL_ID,
            getString(ResourcesR.string.ai_agent_run_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
    channel.description = getString(ResourcesR.string.ai_agent_run_channel_desc)
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
  }

  private fun buildNotification(): Notification {
    createNotificationChannel()

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

    val stopIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, AgentRunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    return Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(ResourcesR.drawable.ic_launcher_notification)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(ResourcesR.string.ai_agent_run_notification))
        .setContentIntent(contentIntent)
        .setOngoing(true)
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(ResourcesR.string.ai_agent_run_stop),
            stopIntent,
        )
        .build()
  }

  companion object {

    private val log = org.slf4j.LoggerFactory.getLogger(AgentRunService::class.java)

    /**
     * 通知渠道 id。与构建/悬浮分开：三者重要级别与用户预期不同，
     * 共用渠道会让「关掉构建通知」把 AI 运行通知也一起关掉。
     */
    private const val CHANNEL_ID = "agent_run"

    /** 通知 id。与 17571（构建）/17572（悬浮）错开，避免互相覆盖。 */
    private const val NOTIFICATION_ID = 17573

    /** wakelock 标签。出现 `dumpsys power` 里时以此识别归属。 */
    private const val WAKE_LOCK_TAG = "com.tom.rv2ide:agent-run"

    const val ACTION_STOP = "com.tom.rv2ide.action.STOP_AGENT_RUN"

    /**
     * 启动保活服务。由 [com.tom.rv2ide.artificial.agent.AgentKeepAlive] 在计数 0→1 时调用。
     *
     * <p>用 `startForegroundService`：targetSdk=28 上从后台（agent 循环线程）启动服务
     * 必须走这个入口；服务内首行 `startForeground` 满足 5 秒约束。
     */
    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AgentRunService::class.java),
        )
      } catch (t: Throwable) {
        // 极少数 ROM 在后台启动前台服务被拒（IllegalStateException / SecurityException）。
        // 保活是增强而非必需：失败时降级为「息屏后可能被冻结」，不影响本次运行本身。
        log.warn("启动保活前台服务失败，本次运行息屏后可能被冻结", t)
      }
    }

    /** 停止保活服务。由 [com.tom.rv2ide.artificial.agent.AgentKeepAlive] 在计数 1→0 时调用。 */
    fun stop(context: Context) {
      try {
        // 用 stopService 而不是发 ACTION_STOP：这里的目的只是收掉服务，
        // 不该顺带取消任何运行（1→0 时已经没有运行了）。
        context.stopService(Intent(context, AgentRunService::class.java))
      } catch (t: Throwable) {
        log.warn("停止保活前台服务失败", t)
      }
    }
  }
}
