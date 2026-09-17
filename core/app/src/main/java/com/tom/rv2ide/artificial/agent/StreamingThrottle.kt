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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

/**
 * 流式增量的刷新节流。
 *
 * <p><b>为什么需要它</b>：模型以 token 为单位吐字，一段几百字的回答会产生上百个
 * [com.tom.rv2ide.ai.agent.AgentEvent.Type.TEXT_DELTA]。若每个增量都触发一次
 * `notifyItemChanged` + Markdown 重解析，一秒钟内会重排上百次列表：滚动位置抖动、
 * 界面掉帧，而人眼根本分辨不出这种粒度。
 *
 * <p>节流策略是**固定间隔 + 首末必刷**：
 * <ul>
 *   <li>首个增量立即刷新——否则用户按下发送后会看到界面毫无反应，以为没生效；
 *   <li>间隔内的增量被合并；
 *   <li>流结束时 {@link #flush()} 补一次——少了它，最后被合并掉的那段文本永远不显示，
 *       看起来像回答被截断了。
 * </ul>
 *
 * <p>时间源通过构造参数注入，便于单测：直接读 {@code System.currentTimeMillis()}
 * 会让测试只能靠 sleep，既慢又不稳定。
 */
class StreamingThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

  /** 是否已经刷新过至少一次。用于区分「首个增量」与「间隔未到」。 */
  private var started = false

  private var lastFlushAt = 0L

  /** 是否有内容尚未显示。 */
  private var pending = false

  /**
   * 收到一个增量，返回是否应当立刻刷新界面。
   *
   * <p>无论返回什么，内部都会记住「有内容待显示」，供 {@link #flush()} 判断。
   */
  fun onDelta(): Boolean {
    pending = true
    val now = clock()
    if (!started || now - lastFlushAt >= intervalMs) {
      started = true
      lastFlushAt = now
      pending = false
      return true
    }
    return false
  }

  /**
   * 流结束时调用：若还有被合并掉的内容则返回 true，表示必须补一次刷新。
   *
   * <p>这是「末尾必刷」的落点——少了它，最后一段增量会丢。
   */
  fun flush(): Boolean {
    if (!pending) {
      return false
    }
    pending = false
    started = true
    lastFlushAt = clock()
    return true
  }

  /** 是否处于「有内容尚未显示」的状态。 */
  fun hasPending(): Boolean = pending

  /** 新一轮输出开始时重置，使新回答的首个增量立即显示。 */
  fun reset() {
    started = false
    lastFlushAt = 0L
    pending = false
  }

  companion object {
    /**
     * 默认间隔 80ms（约 12fps 的文本更新）。
     *
     * <p>取 80ms 而非 16ms：文本更新与动画不同，12fps 的逐字显示已足够连贯，
     * 而把刷新次数压到 1/5 能显著减少 Markdown 重解析与列表重排。
     */
    const val DEFAULT_INTERVAL_MS = 80L
  }
}
