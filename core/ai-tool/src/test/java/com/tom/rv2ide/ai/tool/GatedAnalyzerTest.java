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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** {@link GatedAnalyzer} 的硬超时：超时必须真的在预算内返回，而不是等任务跑完。 */
class GatedAnalyzerTest {

  @Test
  void returnsTaskResultOnSuccess() {
    Object result = GatedAnalyzer.run(() -> "done", 5_000);
    assertEquals("done", result);
  }

  @Test
  void returnsNullWhenTaskThrows() {
    Object result =
        GatedAnalyzer.run(
            () -> {
              throw new IllegalStateException("boom");
            },
            5_000);
    assertNull(result, "任务抛异常应返回 null，区别于超时标记");
  }

  @Test
  @Timeout(10)
  void timesOutWithoutWaitingForBlockingTask() {
    long start = System.nanoTime();
    // 任务睡 3s，预算 200ms：调用方应立刻拿到 TIMED_OUT 而不是等 3s。
    Object result =
        GatedAnalyzer.run(
            () -> {
              Thread.sleep(3_000);
              return "late";
            },
            200);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertSame(GatedAnalyzer.TIMED_OUT, result);
    assertTrue(elapsedMs < 1_500, "硬超时应远早于任务原定结束时间返回，实际 " + elapsedMs + "ms");
  }

  @Test
  void nonPositiveBudgetTimesOutImmediately() {
    Object result = GatedAnalyzer.run(() -> "never", 0);
    assertSame(GatedAnalyzer.TIMED_OUT, result);
  }

  @Test
  void nullTaskReturnsNull() {
    assertNull(GatedAnalyzer.run(null, 1_000));
  }

  @Test
  void interruptedWaiterReturnsTimeoutAndRestoresFlag() throws Exception {
    // 调用方线程在等待中被中断：必须恢复中断位并返回 TIMED_OUT（协作式取消的契约）。
    java.util.concurrent.atomic.AtomicReference<Object> result =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean flagRestored =
        new java.util.concurrent.atomic.AtomicBoolean();
    Thread caller =
        new Thread(
            () -> {
              result.set(
                  GatedAnalyzer.run(
                      () -> {
                        Thread.sleep(5_000);
                        return "late";
                      },
                      10_000));
              flagRestored.set(Thread.currentThread().isInterrupted());
            });
    caller.start();
    Thread.sleep(200);
    caller.interrupt();
    caller.join(3_000);
    assertSame(GatedAnalyzer.TIMED_OUT, result.get(), "被中断的等待应返回 TIMED_OUT");
    assertTrue(flagRestored.get(), "中断位应被恢复而不是被吞掉");
  }

  @Test
  void timedOutMarkerToStringIsStable() {
    // 标记的 toString 会出现在日志/断言里——稳定的名字便于排查。
    assertEquals("GatedAnalyzer.TIMED_OUT", GatedAnalyzer.TIMED_OUT.toString());
  }
}
