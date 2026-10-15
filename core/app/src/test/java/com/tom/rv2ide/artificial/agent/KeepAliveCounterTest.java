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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * 保活引用计数的回归测试（Kotlin 类，从 Java 调用）。
 *
 * <p><b>为什么需要它</b>：计数错一位的后果不对称且难排查——漏发 0→1 会让服务
 * 起不来（息屏后 agent 照旧冻结），漏发 1→0 会让前台服务与 wakelock 永久残留
 * （持续耗电，用户只看到一条停不掉的通知）。而这两个信号都只在**边界**发生，
 * 平时的高频 acquire/release 不会暴露问题，必须由测试钉住边界。
 */
public final class KeepAliveCounterTest {

  @Test
  public void firstAcquireSignalsStart() {
    // 0→1：唯一会触发「拉起前台服务」的信号。
    KeepAliveCounter counter = new KeepAliveCounter();
    assertTrue("首个持有者必须触发启动", counter.acquire());
  }

  @Test
  public void secondAcquireDoesNotSignal() {
    // 已有持有者时再来一个只加计数，不能重复启动服务。
    KeepAliveCounter counter = new KeepAliveCounter();
    counter.acquire();
    assertFalse("非首个持有者不应触发启动", counter.acquire());
    assertEquals(2, counter.current());
  }

  @Test
  public void lastReleaseSignalsStop() {
    // 1→0：唯一会触发「停止前台服务」的信号。
    KeepAliveCounter counter = new KeepAliveCounter();
    counter.acquire();
    assertTrue("最后一个持有者释放必须触发停止", counter.release());
  }

  @Test
  public void innerReleaseDoesNotSignal() {
    // 2→1：还有持有者，服务必须继续跑——这正是「后台任务比运行活得久」的场景。
    KeepAliveCounter counter = new KeepAliveCounter();
    counter.acquire();
    counter.acquire();
    assertFalse("仍有持有者时不应触发停止", counter.release());
    assertEquals(1, counter.current());
  }

  @Test
  public void releaseWithoutAcquireIsRejected() {
    // 多释放一次（重复 release / 未配对 acquire）不能把计数压成负数：
    // 负数会让后续 acquire 永远等不到 0→1，服务再也起不来。
    KeepAliveCounter counter = new KeepAliveCounter();
    assertFalse(counter.release());
    assertEquals("计数不得为负", 0, counter.current());
  }

  @Test
  public void startSignalFiresAgainAfterFullCycle() {
    // 一轮完整开合后，下一次 acquire 必须重新触发启动——否则第二轮运行会失去保活。
    KeepAliveCounter counter = new KeepAliveCounter();
    assertTrue(counter.acquire());
    assertTrue(counter.release());
    assertTrue("归零后的下一次 acquire 应再次触发启动", counter.acquire());
  }

  @Test
  public void concurrentAcquireReleaseKeepsCountConsistent() throws Exception {
    // 并发守恒：agent 循环线程与后台任务线程同时申请/释放。
    //
    // 判据是「启动信号数 == 停止信号数」且终态归零。不能断言各恰好一次——
    // 各线程的 acquire/release 相互独立，计数会在 0 与 N 之间震荡，每次触底
    // 都会合法地产生一对信号；能抓出的错误是「配对数不等」（丢信号）或
    // 「终态非零」（release 丢失）。
    final int threads = 8;
    final int iterations = 500;
    KeepAliveCounter counter = new KeepAliveCounter();
    AtomicInteger starts = new AtomicInteger();
    AtomicInteger stops = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<>();

    for (int i = 0; i < threads; i++) {
      Thread worker =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  go.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int j = 0; j < iterations; j++) {
                  if (counter.acquire()) {
                    starts.incrementAndGet();
                  }
                  if (counter.release()) {
                    stops.incrementAndGet();
                  }
                }
              },
              "keepalive-test-" + i);
      worker.start();
      workers.add(worker);
    }

    assertTrue(ready.await(10, TimeUnit.SECONDS));
    go.countDown();
    for (Thread worker : workers) {
      worker.join(10_000);
    }

    assertEquals("终态必须归零", 0, counter.current());
    assertTrue("至少有线程触发过启动信号", starts.get() >= 1);
    assertEquals("启动与停止信号必须配对出现", starts.get(), stops.get());
  }
}
