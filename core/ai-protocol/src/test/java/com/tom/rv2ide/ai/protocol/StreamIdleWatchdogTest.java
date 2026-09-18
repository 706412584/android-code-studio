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

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.StreamIdleWatchdog.Phase;
import com.tom.rv2ide.ai.protocol.StreamIdleWatchdog.TimeoutType;
import org.junit.jupiter.api.Test;

/**
 * 流卡死看门狗测试。
 *
 * <p>时间全部由用例显式推进（把 {@code nowMs} 当参数传给状态机），
 * 因此边界可以精确到「刚好差 1ms」和「刚好到」，且整个测试类是毫秒级完成的——
 * 这是把时钟做成入参而非读 {@code System.currentTimeMillis()} 的直接收益。</p>
 */
final class StreamIdleWatchdogTest {

    /** 测试用预算：故意取小整数，让边界值一眼可读。 */
    private static final long FIRST_TOKEN_BUDGET = 1_000L;
    private static final long STREAM_IDLE_BUDGET = 200L;

    /** BEFORE_CONTENT 总预算 = 首 token 预算 × 2（与实现一致）。 */
    private static final long CONTENT_BUDGET = FIRST_TOKEN_BUDGET * 2;

    private static StreamIdleWatchdog newWatchdog() {
        return new StreamIdleWatchdog(0L, FIRST_TOKEN_BUDGET, STREAM_IDLE_BUDGET);
    }

    // ------------------------------------------------ 相位 1：BEFORE_FIRST_EVENT

    @Test
    void freshWatchdogIsBeforeFirstEvent() {
        StreamIdleWatchdog watchdog = newWatchdog();
        assertEquals(Phase.BEFORE_FIRST_EVENT, watchdog.phase());
        assertTrue(watchdog.safeToRetry(), "尚未产出任何内容，重发是安全的");
    }

    @Test
    void firstTokenBudgetBoundary() {
        StreamIdleWatchdog watchdog = newWatchdog();
        // 刚好差 1ms：不算超时（用 >= 而非 >，所以 999 必须仍是安全区）
        assertNull(watchdog.checkTimeout(FIRST_TOKEN_BUDGET - 1));
        // 刚好到：超时
        assertEquals(TimeoutType.FIRST_TOKEN_TIMEOUT, watchdog.checkTimeout(FIRST_TOKEN_BUDGET));
        // 超过：仍是同一个结论，不因多次调用而改变
        assertEquals(TimeoutType.FIRST_TOKEN_TIMEOUT, watchdog.checkTimeout(FIRST_TOKEN_BUDGET + 60_000L));
    }

    @Test
    void firstTokenTimeoutIsReportedAsFirstTokenNotIdle() {
        // 区分超时类型是为了让调用方能给出不同的用户提示：
        // 「等待模型响应超时」与「生成中断」对用户的含义完全不同
        StreamIdleWatchdog watchdog = newWatchdog();
        assertEquals(TimeoutType.FIRST_TOKEN_TIMEOUT, watchdog.checkTimeout(5_000L));
    }

    // ----------------------------------------------------- 相位 2：BEFORE_CONTENT

    @Test
    void anyEventMovesToBeforeContentAndResetsIdleClock() {
        StreamIdleWatchdog watchdog = newWatchdog();
        // 在第 900ms 收到一个纯 thinking 事件（还没有正文）
        watchdog.onEvent(900L);
        assertEquals(Phase.BEFORE_CONTENT, watchdog.phase());
        // 首 token 预算不再适用：即使已过 1000ms 也不该报 FIRST_TOKEN_TIMEOUT。
        // 注意此刻已进入流中预算（900+200=1100），所以断言的是「不是首包超时」而非「完全没超时」
        assertEquals(TimeoutType.IDLE_TIMEOUT, watchdog.checkTimeout(1_100L));
        // 改用流中预算，且从事件时刻重新起算
        assertNull(watchdog.checkTimeout(900L + STREAM_IDLE_BUDGET - 1));
        assertEquals(TimeoutType.IDLE_TIMEOUT, watchdog.checkTimeout(900L + STREAM_IDLE_BUDGET));
    }

    @Test
    void heartbeatOnlyStreamEventuallyTripsIdleTimeout() {
        // 中转稳定吐 thinking 但永不产出正文——本类要抓的核心场景。
        //
        // 关键是「稳定吐」：每个 chunk 间隔都小于 idle 预算，因此纯 idle 判定
        // 永远返回 null。必须有独立于事件间隔的总预算兜底，否则用户面对的
        // 仍是永久转圈（与 readTimeout 的失效模式完全相同）。
        StreamIdleWatchdog watchdog = newWatchdog();
        long now = 0L;
        TimeoutType tripped = null;
        for (int i = 0; i < 500; i++) {
            now += STREAM_IDLE_BUDGET - 1;
            watchdog.onEvent(now);
            TimeoutType type = watchdog.checkTimeout(now);
            if (type != null) {
                tripped = type;
                break;
            }
        }
        assertEquals(
            TimeoutType.FIRST_TOKEN_TIMEOUT,
            tripped,
            "持续心跳但始终没有正文时，必须由总预算兜底判定卡死");
    }

    @Test
    void contentArrivalClearsTheBeforeContentBudget() {
        // 一旦真的收到正文，总预算就不再适用——正文可能很长，不能拿它当上限。
        StreamIdleWatchdog watchdog = newWatchdog();
        long now = 0L;
        watchdog.onEvent(now);
        // 推进到远超 contentBudgetMs，确保「总预算已过期」这个前提成立
        now += CONTENT_BUDGET + STREAM_IDLE_BUDGET;
        watchdog.onContent(now);
        // 进入 MID_STREAM 后只受 idle 约束，总预算不再适用。
        //
        // 用 idle 预算内的偏移验证：此刻距离 streamStartMs 已超过 contentBudgetMs
        // （onEvent 时 now 已经推了很久），若总预算仍在生效就会误报 FIRST_TOKEN_TIMEOUT。
        assertNull(
            watchdog.checkTimeout(now + STREAM_IDLE_BUDGET - 1),
            "收到正文后不该再受 BEFORE_CONTENT 总预算约束");
        assertEquals(
            TimeoutType.IDLE_TIMEOUT,
            watchdog.checkTimeout(now + STREAM_IDLE_BUDGET));
    }

    @Test
    void repeatedEventsKeepPostponingTimeout() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.onEvent(10L);
        assertNull(watchdog.checkTimeout(150L));
        watchdog.onEvent(150L);
        assertNull(watchdog.checkTimeout(300L));
        watchdog.onEvent(300L);
        assertEquals(TimeoutType.IDLE_TIMEOUT, watchdog.checkTimeout(500L));
    }

    // --------------------------------------------------------- 相位 3：MID_STREAM

    @Test
    void contentMovesToMidStreamAndBlocksRetry() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.onContent(500L);
        assertEquals(Phase.MID_STREAM, watchdog.phase());
        // 已经吐出正文，重发会让用户看到重复内容
        assertFalse(watchdog.safeToRetry());
    }

    @Test
    void midStreamIdleBoundary() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.onContent(500L);
        assertNull(watchdog.checkTimeout(500L + STREAM_IDLE_BUDGET - 1));
        assertEquals(TimeoutType.IDLE_TIMEOUT, watchdog.checkTimeout(500L + STREAM_IDLE_BUDGET));
    }

    @Test
    void eventsAfterContentDoNotDowngradePhase() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.onContent(100L);
        // 后续事件（例如一段 thinking）不能把相位退回 BEFORE_CONTENT，
        // 否则 safeToRetry 会重新变 true，正文已经交付的事实就丢了
        watchdog.onEvent(200L);
        assertEquals(Phase.MID_STREAM, watchdog.phase());
        assertFalse(watchdog.safeToRetry());
    }

    @Test
    void contentResetsIdleClockEvenIfAlreadyMidStream() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.onContent(0L);
        watchdog.onContent(150L);
        assertNull(watchdog.checkTimeout(300L));
        assertEquals(TimeoutType.IDLE_TIMEOUT, watchdog.checkTimeout(350L));
    }

    // ------------------------------------------------ 副作用边界（最重要）

    @Test
    void toolUseCompletionPermanentlyForbidsRetry() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.onEvent(100L);
        assertTrue(watchdog.safeToRetry(), "工具块未完成前仍可重发");

        watchdog.markToolUseCompleted();
        assertTrue(watchdog.toolUseCompleted());
        // 无论相位如何推进，都必须永久为 false
        assertFalse(watchdog.safeToRetry());
        watchdog.onEvent(200L);
        assertFalse(watchdog.safeToRetry());
        watchdog.onContent(300L);
        assertFalse(watchdog.safeToRetry());
        watchdog.onEvent(400L);
        assertFalse(watchdog.safeToRetry());
    }

    @Test
    void toolBoundaryIsIrreversibleEvenInEarlyPhase() {
        // 最危险的组合：工具块已收完（工具即将/已被执行），但正文尚未开始。
        // 若只看相位会误判为「可以安全重发」，从而重复执行工具。
        StreamIdleWatchdog watchdog = newWatchdog();
        assertEquals(Phase.BEFORE_FIRST_EVENT, watchdog.phase());
        watchdog.markToolUseCompleted();
        assertFalse(watchdog.safeToRetry());
        assertFalse(watchdog.safeToRetry());
    }

    @Test
    void crossingBoundaryIsIdempotent() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.markToolUseCompleted();
        watchdog.markToolUseCompleted();
        watchdog.markToolBoundaryCrossed();
        assertTrue(watchdog.toolUseCompleted());
        assertFalse(watchdog.safeToRetry());
    }

    @Test
    void boundaryCrossedAliasHasSameEffect() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.markToolBoundaryCrossed();
        assertTrue(watchdog.toolUseCompleted());
        assertFalse(watchdog.safeToRetry());
    }

    @Test
    void retryStaysAllowedBeforeAnyContentWhenNoToolUse() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.onEvent(50L);
        watchdog.onEvent(60L);
        assertTrue(watchdog.safeToRetry());
        // 即使超时判定已经触发，只要没越过工具边界且没有正文，就允许重发
        watchdog.checkTimeout(100_000L);
        assertTrue(watchdog.safeToRetry());
    }

    // ------------------------------------------------------------- 配置与工具

    @Test
    void defaultBudgetsMatchAndroidTuning() {
        StreamIdleWatchdog watchdog = new StreamIdleWatchdog(0L);
        // 首 token 保持 cc-haha 的 90s；流中空闲收紧到 30s（见类注释的取舍理由）
        assertEquals(90_000L, watchdog.firstTokenBudgetMs());
        assertEquals(30_000L, watchdog.streamIdleBudgetMs());
        assertEquals(90_000L, StreamIdleWatchdog.DEFAULT_FIRST_TOKEN_BUDGET_MS);
        assertEquals(30_000L, StreamIdleWatchdog.DEFAULT_STREAM_IDLE_BUDGET_MS);
    }

    @Test
    void defaultBudgetsApplyAtTheirBoundaries() {
        StreamIdleWatchdog watchdog = new StreamIdleWatchdog(0L);
        assertNull(watchdog.checkTimeout(89_999L));
        assertEquals(TimeoutType.FIRST_TOKEN_TIMEOUT, watchdog.checkTimeout(90_000L));
    }

    @Test
    void nonPositiveBudgetsAreClampedToAvoidInstantTimeout() {
        // 预算为 0/负数会把配置笔误变成「所有流都秒断」，必须夹到 >= 1
        StreamIdleWatchdog watchdog = new StreamIdleWatchdog(0L, 0L, -100L);
        assertEquals(1L, watchdog.firstTokenBudgetMs());
        assertEquals(1L, watchdog.streamIdleBudgetMs());
        assertNull(watchdog.checkTimeout(0L));
        assertEquals(TimeoutType.FIRST_TOKEN_TIMEOUT, watchdog.checkTimeout(1L));
    }

    @Test
    void idleMsTracksTimeSinceLastActivity() {
        StreamIdleWatchdog watchdog = newWatchdog();
        watchdog.onEvent(100L);
        assertEquals(150L, watchdog.idleMs(250L));
        watchdog.onContent(400L);
        assertEquals(0L, watchdog.idleMs(400L));
    }

    @Test
    void watchdogIsUsableFromNonZeroStartTime() {
        // 调用方通常传 System.currentTimeMillis()，起点不是 0
        long start = 1_700_000_000_000L;
        StreamIdleWatchdog watchdog = new StreamIdleWatchdog(start);
        assertNull(watchdog.checkTimeout(start + 89_999L));
        assertEquals(TimeoutType.FIRST_TOKEN_TIMEOUT,
                watchdog.checkTimeout(start + StreamIdleWatchdog.DEFAULT_FIRST_TOKEN_BUDGET_MS));
    }
}
