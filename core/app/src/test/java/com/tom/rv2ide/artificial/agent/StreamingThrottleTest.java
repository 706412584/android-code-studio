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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 流式节流的回归测试（Kotlin 类，从 Java 调用）。
 *
 * <p><b>为什么需要它</b>：节流最容易出的错是「丢了最后一段」——模型输出结束后的尾部
 * 内容被间隔吃掉，界面上看起来像回答被截断了。这类 bug 在真机上很难注意到
 * （用户会以为模型本来就说这么多），必须有测试钉住。
 */
public final class StreamingThrottleTest {

  /** 可手动推进的时钟。 */
  private static final class FakeClock {
    private long now = 1_000L;

    long now() {
      return now;
    }

    void advance(long ms) {
      now += ms;
    }
  }

  @Test
  public void firstDeltaFlushesImmediately() {
    // 首个增量必须立刻显示，否则用户按下发送后会以为没生效。
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    assertTrue(throttle.onDelta());
  }

  @Test
  public void deltasWithinIntervalAreCoalesced() {
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    throttle.onDelta(); // 首个立即刷新
    clock.advance(10);
    assertFalse("间隔内的增量应被合并", throttle.onDelta());
    clock.advance(10);
    assertFalse(throttle.onDelta());
  }

  @Test
  public void flushesAgainAfterIntervalElapses() {
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    throttle.onDelta();
    clock.advance(79);
    assertFalse(throttle.onDelta());
    clock.advance(1); // 累计 80ms
    assertTrue("达到间隔后应刷新", throttle.onDelta());
  }

  @Test
  public void flushPushesTrailingCoalescedContent() {
    // 核心场景：最后一个增量落在间隔内被合并，流结束时必须补刷，否则尾部内容丢失。
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    throttle.onDelta();
    clock.advance(10);
    assertFalse(throttle.onDelta()); // 被合并 → 有未显示内容

    assertTrue("流结束时必须补刷被合并的内容", throttle.flush());
  }

  @Test
  public void flushReturnsFalseWhenNothingPending() {
    // 已经刷新过且没有新增量时不该重复刷新：多一次刷新就是一次无谓的列表重排。
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    throttle.onDelta();
    assertFalse("没有待显示内容时不应刷新", throttle.flush());
  }

  @Test
  public void flushIsIdempotent() {
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    throttle.onDelta();
    clock.advance(10);
    throttle.onDelta();

    assertTrue(throttle.flush());
    assertFalse("第二次 flush 不应再次刷新", throttle.flush());
  }

  @Test
  public void resetMakesNextDeltaFlushImmediately() {
    // 新一轮回答的首个增量同样要立即显示，否则用户等不到反馈。
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    throttle.onDelta();
    clock.advance(10);
    throttle.onDelta();
    throttle.reset();

    assertTrue("reset 后首个增量应立即刷新", throttle.onDelta());
  }

  @Test
  public void hasPendingTracksUnflushedContent() {
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    assertFalse(throttle.hasPending());
    throttle.onDelta();
    assertFalse("刚刷新过 → 无待显示内容", throttle.hasPending());

    clock.advance(5);
    throttle.onDelta();
    assertTrue("被合并的增量应记录为待显示", throttle.hasPending());

    throttle.flush();
    assertFalse(throttle.hasPending());
  }

  @Test
  public void manyRapidDeltasProduceFarFewerFlushes() {
    // 节流的实际收益：上百个增量只应产生个位数刷新。
    FakeClock clock = new FakeClock();
    StreamingThrottle throttle = new StreamingThrottle(80L, clock::now);

    int flushes = 0;
    for (int i = 0; i < 200; i++) {
      if (throttle.onDelta()) {
        flushes++;
      }
      clock.advance(4); // 200 * 4ms = 800ms 的输出
    }
    if (throttle.flush()) {
      flushes++;
    }

    // 800ms / 80ms ≈ 10 次，允许少量边界偏差
    assertTrue("刷新次数应远小于增量数，实际 " + flushes, flushes <= 13);
    assertTrue("刷新次数过少会导致显示卡顿感，实际 " + flushes, flushes >= 5);
  }

  @Test
  public void defaultIntervalIsUsedWhenNotSpecified() {
    assertTrue(StreamingThrottle.DEFAULT_INTERVAL_MS > 0);
    // 默认间隔应当明显大于一帧，否则节流没有意义
    assertTrue(StreamingThrottle.DEFAULT_INTERVAL_MS >= 16);
  }
}
