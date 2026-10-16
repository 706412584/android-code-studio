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

import android.app.Dialog
import android.os.Handler
import android.os.Looper

/**
 * 交互弹窗（确认 / 提问）的超时自动关闭。
 *
 * <p><b>解决什么问题</b>：这些弹窗是从 agent 循环线程**阻塞等待**的（调用方建
 * `CountDownLatch`、post 到主线程摆窗、再 `await`）。此前调用方只 await 超时返回，
 * **弹窗本身不会被关掉**——超时后弹窗一直挂在屏幕上：用户对着一个点了没反应的对话框
 * （它的回调早已返回），而后台那轮运行已经当成「用户未确认」继续走了。用户还会以为
 * 是自己没点对。
 *
 * <p><b>为什么按「整次调用」计时而不是每个弹窗各计一次</b>：`showUserQuestions`
 * 可能连续弹多道题（含「其它」的输入框）。若每个弹窗各 5 分钟，N 道题就等 N×5 分钟，
 * 而调用方只 await 一次——必然错配。这里用 [Guard] 覆盖整次调用：超时即收掉
 * **当前**那个弹窗并回调取消。
 *
 * <p><b>为什么超时值集中在这里</b>：三条渲染路径（Activity / 系统悬浮 / Compose）
 * 与调用方（`FloatingAssistantView` 的 await）必须用同一个预算，否则会出现
 * 「弹窗已自动关、调用方还在等」或反过来的错配。改一处全体生效。
 *
 * <p><b>为什么 5 分钟</b>：弹窗是模态的，但人可能不在屏幕前。5 分钟够回到屏幕前
 * 做决定；再长会让一次运行卡死太久——期间该会话的工具调用全在等这个答复。
 */
object DialogTimeouts {

  /**
   * 交互弹窗的等待预算（毫秒）。
   *
   * <p>调用方的 `latch.await` 用 [AWAIT_MS]（比它略长）兜底：正常路径下弹窗会先
   * 超时关闭并回调，调用方随即被唤醒；`await` 的超时只是「连主线程都卡住」时的
   * 最后一道保险，因此必须**大于**本值。
   */
  const val TIMEOUT_MS = 5 * 60 * 1000L

  /** 调用方 `await` 的超时（毫秒）。比 [TIMEOUT_MS] 多 10 秒余量。 */
  const val AWAIT_MS = TIMEOUT_MS + 10_000L

  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * 一次调用的超时守卫。
   *
   * <p>用法：调用开头 [Guard.start]（传入超时后的处理），每次摆出新弹窗时
   * [Guard.attach]，用户作答后 [Guard.cancel]。
   */
  class Guard(private val onTimeout: () -> Unit) {

    /** 当前正显示的弹窗；超时时关它。 */
    private var current: Dialog? = null

    private var finished = false

    private val runnable =
        Runnable {
          if (finished) {
            return@Runnable
          }
          finished = true
          val dialog = current
          current = null
          // 先回调再 dismiss：回调会唤醒 agent 循环线程让它尽快继续；
          // 顺序反了会让「屏幕上的弹窗消失」与「后台认为已取消」之间多一段无谓延迟。
          try {
            onTimeout()
          } finally {
            try {
              dialog?.dismiss()
            } catch (_: Throwable) {
              // 弹窗可能已被用户关掉或宿主已销毁：dismiss 失败不影响超时语义。
            }
          }
        }

    /** 开始计时（每次调用一次）。 */
    fun start(): Guard {
      mainHandler.postDelayed(runnable, TIMEOUT_MS)
      return this
    }

    /** 记录当前弹窗——超时时收掉的就是它。多道题连续弹时每次调用本方法覆盖。 */
    fun attach(dialog: Dialog?) {
      if (!finished) {
        current = dialog
      }
    }

    /**
     * 用户已作答，撤销超时。
     *
     * <p>必须调用：否则 5 分钟后会再触发一次 [onTimeout]，把「拒绝/取消」记两遍
     * （且可能对着用户下一轮已经打开的弹窗误关）。
     */
    fun cancel() {
      if (finished) {
        return
      }
      finished = true
      current = null
      mainHandler.removeCallbacks(runnable)
    }

    /** 是否已结束（超时或已取消）；供宿主判断是否还要摆下一个弹窗。 */
    fun isFinished(): Boolean = finished
  }
}
