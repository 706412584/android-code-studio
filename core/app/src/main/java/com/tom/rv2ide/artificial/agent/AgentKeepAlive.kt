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

package com.tom.rv2ide.artificial.agent

import android.os.Handler
import android.os.Looper
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.services.AgentRunService

/**
 * AI 运行期保活的门面。
 *
 * <p><b>它做什么</b>：把 [KeepAliveCounter] 的计数信号翻译成前台服务的启停——
 * 计数 0→1 时拉起 [AgentRunService]（该服务持 `PARTIAL_WAKE_LOCK` 并以前台服务身份
 * 常驻），1→0 时停止它。息屏后 CPU 不 suspend、进程不被轻易回收，agent 循环得以继续。
 *
 * <p><b>谁调用</b>：
 * <ul>
 *   <li>[AgentOrchestrator.runInConversation] —— 整个运行区间（含前置校验早退）；
 *   <li>[BackgroundShellRunner.start] —— 每个后台 shell 任务，可独立于运行存活。
 * </ul>
 * 两者的区间可以重叠、可以首尾相接，引用计数保证「后结束的一方」才是真正关服务的人。
 *
 * <p><b>为什么所有状态变更都路由到主线程</b>：acquire/release 来自 agent 循环线程
 * 与后台任务线程，若各自直接判断并启停服务，「检查计数 → 启停服务」之间会互相穿插，
 * 出现「计数为 1 但服务已被停掉」的丢保活。统一 post 到主线程后所有变更严格串行，
 * 不存在交错窗口。
 *
 * <p><b>为什么 1→0 不立刻停服务</b>：排队续跑是常见路径——上一次运行在 finally 里
 * 唤醒下一条排队请求（[AgentOrchestrator.drainQueued]），而新线程走到 acquire 之前
 * 还要先读会话、解析工作区。若 1→0 立刻停，几乎每次排队交接都会「停→立刻再启」，
 * 通知栏闪烁一次。延迟 [STOP_DELAY_MS] 后重新确认计数仍为 0 才真正停止。
 *
 * <p><b>为什么 Context 从 BaseApplication 取</b>：调用发生在 agent 循环线程与
 * 后台任务线程，取不到 Activity，也不该持有 Activity（泄漏）。与
 * [AssistantOrchestratorProvider] 同源（applicationContext，与进程同寿）。
 *
 * <p><b>失败降级</b>：保活是增强而非必需——服务启停失败（极少数 ROM 的后台限制）
 * 只记日志，不影响运行本身；只是息屏后可能被冻结。
 */
object AgentKeepAlive {

  private val mainHandler = Handler(Looper.getMainLooper())

  /** 引用计数。只在主线程读写（见类注释）。 */
  private val counter = KeepAliveCounter()

  /**
   * 1→0 后延迟停止的窗口。
   *
   * <p>取值只需覆盖「上一次运行的收尾 + 排队请求走到 acquire」这段（正常在几十毫秒内，
   * 慢盘上也不会超过 1 秒）。过长会让运行结束后的 wakelock 白持，3 秒是安全与浪费的折中。
   */
  private const val STOP_DELAY_MS = 3000L

  /**
   * 延迟停止任务。触发时**重新确认**计数仍为 0 才停服务——
   * 从 postDelayed 到执行之间可能有新的 acquire 进来。
   */
  private val stopRunnable =
      Runnable {
        if (counter.current() == 0) {
          AgentRunService.stop(BaseApplication.getBaseInstance())
        }
      }

  /**
   * 申请保活。首个持有者会拉起前台服务。
   *
   * <p>可在任意线程调用（内部切主线程）。
   */
  @JvmStatic
  fun acquire() {
    mainHandler.post {
      // 取消可能已排队的停止：本次 acquire 说明保活仍被需要。
      mainHandler.removeCallbacks(stopRunnable)
      if (counter.acquire()) {
        AgentRunService.start(BaseApplication.getBaseInstance())
      }
    }
  }

  /**
   * 释放保活。最后一个持有者会（延迟地）停止前台服务。
   *
   * <p>可在任意线程调用（内部切主线程）。必须与 [acquire] 严格配对——多释放一次
   * 不会误停服务（[KeepAliveCounter] 挡在计数之外），但会破坏配对关系，
   * 让真正的最后一个持有者释放时计数提前归零。
   */
  @JvmStatic
  fun release() {
    mainHandler.post {
      if (counter.release()) {
        mainHandler.postDelayed(stopRunnable, STOP_DELAY_MS)
      }
    }
  }
}
