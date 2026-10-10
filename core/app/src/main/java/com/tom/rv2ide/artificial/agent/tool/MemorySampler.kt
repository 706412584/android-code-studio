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

package com.tom.rv2ide.artificial.agent.tool

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.tom.rv2ide.ai.tool.IdeLogSource
import com.tom.rv2ide.logging.IdeLogBufferHolder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 周期性内存采样器：把进程内存画像写进 `IdeLogBuffer`，让卡顿/内存问题**事后可从日志回看**。
 *
 * <p><b>为什么需要持续采样</b>：`memory_analysis` 工具给的是「此刻」的快照——用户报告
 * 「有时候卡顿」时，卡顿早已过去，快照看不到「什么时候开始涨、涨得多快」。采样把内存
 * 轨迹变成日志行，`ide_log_read` 一查就能看到趋势（例：连续几条 `java 420MB → 480MB
 * → 510MB` 就是明确的内存泄漏/OOM 前兆）。
 *
 * <p><b>级别策略</b>：常规采样用 INFO（默认 `ide_log_read` 只回 WARN 及以上，不打扰），
 * **超阈值时升级为 WARN**——堆 ≥75%、native ≥256MB、消息数 ≥500 时，默认查询就能看到。
 * 这是「卡顿时直接看日志」的落点。
 *
 * <p><b>成本</b>：每 [INTERVAL_SECONDS] 秒一次，读几个 int/long + 拼一行字符串，
 * 单次微秒级；日志缓冲是 1000 条环形，60s 间隔可回看约 16 小时。
 *
 * <p>采样线程是守护线程（进程退出即止），不持有 Context（只用 applicationContext
 * 拿 ActivityManager，且每次采样即取即弃）。
 */
object MemorySampler {

  /** 采样间隔（秒）。60s 在「趋势可见」与「日志不刷屏」之间取平衡。 */
  private const val INTERVAL_SECONDS = 60L

  /** Java 堆占比告警阈值（%）。与 MemoryAnalysisTool 的判定一致。 */
  private const val JAVA_HEAP_WARN_PERCENT = 75L

  /** Native 堆告警阈值（字节）。 */
  private const val NATIVE_WARN_BYTES = 256L * 1024 * 1024

  /** 消息条目告警阈值。 */
  private const val MESSAGE_COUNT_WARN = 500

  private const val LOGGER_NAME = "ai[memory]"

  private var started = false

  /** 启动采样。重复调用幂等（IDEApplication 只有一个进程，但防未来多入口）。 */
  @JvmStatic
  @Synchronized
  fun start(context: Context) {
    if (started) {
      return
    }
    started = true
    val appContext = context.applicationContext
    val executor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
          Thread(runnable, "acs-mem-sampler").apply { isDaemon = true }
        }
    executor.scheduleWithFixedDelay(
        {
          try {
            sample(appContext)
          } catch (t: Throwable) {
            // 采样是旁路诊断，任何失败都不得影响主流程；静默跳过本轮。
          }
        },
        INTERVAL_SECONDS,
        INTERVAL_SECONDS,
        TimeUnit.SECONDS,
    )
  }

  /** 立即采样一次并写入日志缓冲（供工具/测试触发，不必等定时器）。 */
  @JvmStatic
  fun sampleNow(context: Context) {
    try {
      sample(context.applicationContext)
    } catch (t: Throwable) {
      // 同上：旁路。
    }
  }

  private fun sample(context: Context) {
    val runtime = Runtime.getRuntime()
    val javaMax = runtime.maxMemory()
    val javaUsed = runtime.totalMemory() - runtime.freeMemory()
    val nativeAllocated = Debug.getNativeHeapAllocatedSize()

    val views = MemoryStatsRegistry.snapshotAll()
    var totalMessages = 0
    var totalTools = 0
    for (v in views) {
      totalMessages += v.messageCount
      totalTools += v.toolCardCount
    }

    val javaPercent = if (javaMax > 0) javaUsed * 100L / javaMax else 0L
    val warn =
        javaPercent >= JAVA_HEAP_WARN_PERCENT ||
            nativeAllocated >= NATIVE_WARN_BYTES ||
            totalMessages >= MESSAGE_COUNT_WARN

    val line =
        String.format(
            Locale.US,
            "java %.1f/%.1fMB (%d%%) · native %.1fMB · 视图 %d · 消息 %d · 工具卡 %d%s",
            javaUsed / 1048576.0,
            javaMax / 1048576.0,
            javaPercent,
            nativeAllocated / 1048576.0,
            views.size,
            totalMessages,
            totalTools,
            if (warn) " ⚠ 接近上限，卡顿/OOM 风险" else "",
        )

    IdeLogBufferHolder.get().append(
        System.currentTimeMillis(),
        if (warn) IdeLogSource.LEVEL_WARN else IdeLogSource.LEVEL_INFO,
        LOGGER_NAME,
        line,
        "",
    )
  }
}
