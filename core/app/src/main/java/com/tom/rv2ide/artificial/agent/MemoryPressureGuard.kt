/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.logging.IdeLogBufferHolder
import com.tom.rv2ide.ai.tool.IdeLogSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存压力守卫：在堆接近上限时主动释放可回收缓存，而不是等 OOM 把进程打死。
 *
 * <p><b>为什么需要它</b>：真机（PJA110，512MB 堆）跑影子屏自动化时，连续截图 +
 * 14 个文件的 diff 汇总把堆推到 492MB，下一次 22MB 分配直接
 * `OutOfMemoryError: Failed to allocate a 22055512 byte allocation with 19MB until OOM`。
 * 那个时刻已经太晚——回收要在**还有余量**时做。
 *
 * <p><b>三级响应</b>（按堆占用率）：
 * <ul>
 *   <li>≥ [WARN_PERCENT]（75%）— 记录 WARN 日志，不动作（留给采样器观察趋势）
 *   <li>≥ [RELEASE_PERCENT]（85%）— 调用已注册的释放器，丢掉纯缓存（Markdown 解析结果、
 *       图片预览等），不影响任何用户可见状态
 *   <li>≥ [CRITICAL_PERCENT]（92%）— 再调一次释放器并额外提示：这是 OOM 前最后一道闸
 * </ul>
 *
 * <p><b>为什么是「注册释放器」而不是直接持有各缓存</b>：缓存分散在 Compose 层
 * （MarkdownRenderCache、图片缓存）与数据层（diff 缓存），本类在 app 层但不该反向依赖
 * 它们的实现细节。各模块启动时注册一个 lambda 即可，顺序与幂等由它们自己保证。
 *
 * <p><b>线程</b>：释放器可能在任意线程被调用（采样器线程 / agent 循环线程），
 * 实现方必须自行切到正确线程。本类只用 `CopyOnWriteArrayList`，无锁读。
 */
object MemoryPressureGuard {

  /** 记录 WARN 的阈值（%）。 */
  const val WARN_PERCENT = 75L

  /** 开始释放缓存的阈值（%）。 */
  const val RELEASE_PERCENT = 85L

  /** 危急阈值（%）：OOM 前最后一道闸。 */
  const val CRITICAL_PERCENT = 92L

  /** 两次自动释放之间的最小间隔，避免高频调用把释放器本身变成负担。 */
  private const val MIN_RELEASE_INTERVAL_MS = 5_000L

  private const val LOGGER_NAME = "ai[memory]"

  private val releasers = CopyOnWriteArrayList<Pair<String, () -> Unit>>()

  private val lastReleaseAt = AtomicLong(0L)

  /** 本进程累计自动释放次数（诊断用）。 */
  private val releaseCount = AtomicLong(0L)

  /**
   * 注册一个缓存释放器。
   *
   * @param name 用于日志与排查的来源名
   * @param releaser 释放动作；**必须幂等、不得抛异常、不得阻塞主线程**
   */
  @JvmStatic
  fun register(name: String, releaser: () -> Unit) {
    if (name.isBlank() || releasers.any { it.first == name }) {
      return
    }
    releasers += name to releaser
  }

  /** 当前堆占用率（%）；取不到时返回 0。 */
  @JvmStatic
  fun heapPercent(): Long {
    val runtime = Runtime.getRuntime()
    val max = runtime.maxMemory()
    if (max <= 0) {
      return 0
    }
    val used = runtime.totalMemory() - runtime.freeMemory()
    return used * 100L / max
  }

  /** 剩余可用堆字节数（已分配区之外的部分）。 */
  @JvmStatic
  fun headroomBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
  }

  /**
   * 按当前压力做一次响应。可在任意线程调用（不阻塞、不抛异常）。
   *
   * @return 本次采取的动作，供调用方决定是否还要做额外处理
   */
  @JvmStatic
  fun check(): Action {
    val percent = heapPercent()
    if (percent < WARN_PERCENT) {
      return Action.NONE
    }
    if (percent < RELEASE_PERCENT) {
      log(percent, "接近上限")
      return Action.WARNED
    }

    val now = System.currentTimeMillis()
    val last = lastReleaseAt.get()
    if (now - last < MIN_RELEASE_INTERVAL_MS) {
      // 刚释放过：不再重复，但危急时仍要提示。
      if (percent >= CRITICAL_PERCENT) {
        log(percent, "危急（刚释放过，等待回收生效）")
        return Action.CRITICAL
      }
      return Action.WARNED
    }
    if (!lastReleaseAt.compareAndSet(last, now)) {
      return Action.WARNED // 另一个线程正在释放
    }

    releaseAll(percent)
    if (percent >= CRITICAL_PERCENT) {
      log(percent, "危急，已释放缓存")
      return Action.CRITICAL
    }
    log(percent, "已释放缓存")
    return Action.RELEASED
  }

  /**
   * 立即释放全部已注册缓存（不检查阈值）。供 OOM 兜底与用户手动「清理内存」使用。
   *
   * @return 成功执行的释放器数量
   */
  @JvmStatic
  fun releaseNow(): Int = releaseAll(heapPercent())

  private fun releaseAll(percent: Long): Int {
    var ok = 0
    for ((name, releaser) in releasers) {
      try {
        releaser()
        ok++
      } catch (t: Throwable) {
        // 释放缓存是尽力而为：某个释放器失败不该阻止其它释放器运行，
        // 更不该在 OOM 边缘再抛一次异常。
        log(percent, "释放器 $name 失败: ${t.javaClass.simpleName}")
      }
    }
    if (ok > 0) {
      releaseCount.incrementAndGet()
    }
    return ok
  }

  private fun log(percent: Long, message: String) {
    try {
      IdeLogBufferHolder.get().append(
          System.currentTimeMillis(),
          if (percent >= CRITICAL_PERCENT) IdeLogSource.LEVEL_WARN else IdeLogSource.LEVEL_INFO,
          LOGGER_NAME,
          "java $percent% · $message · 已自动释放 ${releaseCount.get()} 次",
          "",
      )
    } catch (t: Throwable) {
      // 日志设施不可用时静默——这是诊断，不是主流程。
    }
  }

  /** 一次检查的结果。 */
  enum class Action {
    /** 内存充裕，未动作。 */
    NONE,
    /** 仅记录告警。 */
    WARNED,
    /** 已释放缓存。 */
    RELEASED,
    /** 危急：已释放缓存，调用方应提示用户或中止高内存操作。 */
    CRITICAL,
  }
}
