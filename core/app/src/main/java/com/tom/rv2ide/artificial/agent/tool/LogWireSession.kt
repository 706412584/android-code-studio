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

package com.tom.rv2ide.artificial.agent.tool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.mohammedbaqernull.logger.model.LogEntry
import io.github.mohammedbaqernull.logger.service.LogReceiverService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 侧的 LogWire 订阅会话。
 *
 * <p><b>解决什么问题</b>：用户在「开发者选项 → Logsender」开启后，本 IDE 构建安装的
 * 应用会把日志经 AIDL 主动上报到本进程的 [LogReceiverService]。日志面板（AppLogFragment）
 * 能看到，但 AI 的 `logcat_read` 走的是 shell 后端 `logcat -d`——两者互不相通。
 * 本类让 AI 也能消费这条通道：**无需 Shizuku/Termux 权限**，只要应用由本 IDE 构建过。
 *
 * <p><b>为什么自己持有 listener 与缓冲</b>：服务只转发不存历史（见 [LogWireBuffer]
 * 的类注释）。面板有自己的 listener，AI 必须也有自己的——多播正是为此。
 *
 * <p><b>绑定时机</b>：首次查询时懒绑定。绑定成功后持续接收，直到进程退出或显式
 * [unbind]。不做「查询完就解绑」——日志是连续流，解绑会丢掉两次查询之间的内容，
 * 而「启动 app 之后日志去哪了」正是最需要看的那一段。
 *
 * <p><b>与日志面板并存</b>：服务端 `listeners` 是 `CopyOnWriteArrayList`（多播），
 * 两边各收各的，互不干扰。此前 `LogcatReadTool` 注释里「单消费者互斥」的说法针对的是
 * 旧的 `com.tom.rv2ide.services.log.LogReceiverService`（现已无调用点）。
 */
object LogWireSession {

  private val log = org.slf4j.LoggerFactory.getLogger(LogWireSession::class.java)

  /** 日志缓冲。跨查询持续累积。 */
  private val buffer = LogWireBuffer()

  /** 是否已发起绑定（不代表已连上）。 */
  private val bindRequested = AtomicBoolean(false)

  /** 已连接（onServiceConnected 已回调）。 */
  @Volatile private var connected = false

  /** 绑定时的 Context（applicationContext，与进程同寿）。 */
  @Volatile private var appContext: Context? = null

  private val listener =
      object : LogReceiverService.LogListener {
        override fun onLogReceived(log: LogEntry) {
          buffer.add(log)
        }
      }

  private val connection =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
          connected = true
          // 服务端每次 onBind 都返回同一个 binder 实例（单例服务），
          // 重复绑定会重复 addListener——服务端用的是 CopyOnWriteArrayList，
          // 重复添加会让同一条日志被记两次。先移除再添加保证幂等。
          LogReceiverService.getInstance()?.let { svc ->
            svc.removeListener(listener)
            svc.addListener(listener)
          }
          log.info("LogWire 会话已连接，开始接收日志")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
          connected = false
          log.info("LogWire 会话断开")
        }
      }

  /**
   * 确保已发起绑定。重复调用是 no-op。
   *
   * @return 是否处于「已绑定或已连接」状态；false 表示绑定请求本身失败
   *         （服务未在 manifest 声明 / 系统拒绝）
   */
  @JvmStatic
  fun ensureBound(context: Context): Boolean {
    if (bindRequested.get()) {
      return true
    }
    synchronized(this) {
      if (bindRequested.get()) {
        return true
      }
      val ctx = context.applicationContext
      appContext = ctx
      return try {
        val intent = Intent(ctx, LogReceiverService::class.java)
        // 先 startService 再 bindService：与 AppLogFragment 一致。
        // 只 bind 的话，服务未启动时拿不到实例（BIND_AUTO_CREATE 只保证创建，
        // 但某些 ROM 上顺序反了会 bind 失败）。
        ctx.startService(intent)
        val bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bindRequested.set(bound)
        bound
      } catch (t: Throwable) {
        // 绑定失败不是致命错误：logwire_read 会据此给出可操作提示。
        log.warn("绑定 LogWire 服务失败", t)
        false
      }
    }
  }

  /** 查询快照（不自动绑定；调用方应先 [ensureBound]）。 */
  @JvmStatic
  fun snapshot(packageName: String?, limit: Int): Snapshot =
      Snapshot(
          entries = buffer.snapshot(packageName, limit),
          packages = buffer.packages(),
          bound = connected,
      )

  /** 已知包名。 */
  @JvmStatic fun knownPackages(): List<String> = buffer.packages()

  /** 某包当前缓冲条数。 */
  @JvmStatic fun countOf(packageName: String): Int = buffer.count(packageName)

  /** 是否已连接（onServiceConnected 已回调）。 */
  @JvmStatic fun isConnected(): Boolean = connected

  /** 解除绑定（测试/诊断用；正常流程不需要）。 */
  @JvmStatic
  fun unbind(context: Context) {
    synchronized(this) {
      if (!bindRequested.getAndSet(false)) {
        return
      }
      try {
        context.applicationContext.unbindService(connection)
      } catch (t: Throwable) {
        log.warn("解绑 LogWire 服务失败", t)
      }
      connected = false
      LogReceiverService.getInstance()?.removeListener(listener)
    }
  }

  /**
   * 一次查询的结果。
   *
   * @param entries 命中的日志（时间序）
   * @param packages 当前已知的全部包名（用于「没有该包日志」时给出可操作提示）
   * @param bound 是否已连上服务
   */
  data class Snapshot(
      val entries: List<LogEntry>,
      val packages: List<String>,
      val bound: Boolean,
  )
}
