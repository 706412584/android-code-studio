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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 退避策略测试。
 *
 * <p>Random 全部注入为确定性实现（{@link FixedRandom}），因此这里断言的是精确值而非范围，
 * 同时另有专门的用例用「极值 Random」验证抖动区间的上下界。全部用例不做任何 sleep。</p>
 */
final class BackoffPolicyTest {

    /** 固定返回同一个 double 的 Random：让抖动变成可预测的常数。 */
    private static final class FixedRandom extends Random {
        private final double value;

        FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }

    private static BackoffPolicy policy(long maxDelayMs, double jitterFraction) {
        return new BackoffPolicy(maxDelayMs, new FixedRandom(jitterFraction));
    }

    // ------------------------------------------------------------ 指数序列

    @Test
    void delayGrowsExponentiallyAndStartsAtBaseDelay() {
        // 零抖动下 attempt=1..5 应恰好是 500 / 1000 / 2000 / 4000 / 8000
        BackoffPolicy policy = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 0.0);
        assertEquals(500L, policy.delayMs(1));
        assertEquals(1000L, policy.delayMs(2));
        assertEquals(2000L, policy.delayMs(3));
        assertEquals(4000L, policy.delayMs(4));
        assertEquals(8000L, policy.delayMs(5));
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        // 不封顶的话 attempt=10 会是 256s，用户面对的是分钟级静默
        BackoffPolicy policy = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 0.0);
        assertEquals(16_000L, policy.delayMs(6));
        assertEquals(32_000L, policy.delayMs(7));
        // 到顶之后继续增长请求也不得超过上限
        assertEquals(32_000L, policy.delayMs(8));
        assertEquals(32_000L, policy.delayMs(20));
    }

    @Test
    void customMaxDelayIsHonoured() {
        BackoffPolicy policy = policy(3_000L, 0.0);
        assertEquals(500L, policy.delayMs(1));
        assertEquals(2_000L, policy.delayMs(3));
        assertEquals(3_000L, policy.delayMs(4));
        assertEquals(3_000L, policy.delayMs(5));
        assertEquals(3_000L, policy.maxDelayMs());
    }

    // ---------------------------------------------------------------- 抖动

    @Test
    void jitterIsAdditiveOnlyAndWithinQuarterOfBase() {
        // 上界：抖动比例为 0.25 时恰好 +25%
        BackoffPolicy upper = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 1.0);
        assertEquals(625L, upper.delayMs(1));
        assertEquals(1250L, upper.delayMs(2));

        // 下界：抖动比例为 0 时就是基准值，绝不会低于基准——
        // 负抖动会让退避短于指数退避承诺的下限，服务端 overload 时等于加压
        BackoffPolicy lower = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 0.0);
        assertEquals(500L, lower.delayMs(1));
    }

    @Test
    void jitterStaysWithinRangeForArbitraryRandomValues() {
        BackoffPolicy policy = new BackoffPolicy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, new Random(42L));
        for (int attempt = 1; attempt <= 8; attempt++) {
            long base = Math.min(500L << (attempt - 1), BackoffPolicy.DEFAULT_MAX_DELAY_MS);
            long upperBound = base + (long) (0.25 * base);
            for (int i = 0; i < 200; i++) {
                long delay = policy.delayMs(attempt);
                assertTrue(delay >= base, "attempt=" + attempt + " 延迟低于基准: " + delay);
                assertTrue(delay <= upperBound, "attempt=" + attempt + " 抖动超界: " + delay);
            }
        }
    }

    @Test
    void nullRandomMeansNoJitter() {
        // 传 null 是允许的（调用方可能只想要纯指数退避），不应 NPE
        BackoffPolicy policy = new BackoffPolicy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, null);
        assertEquals(500L, policy.delayMs(1));
        assertEquals(4000L, policy.delayMs(4));
    }

    // ------------------------------------------------------------ Retry-After

    @Test
    void retryAfterTakesPrecedenceOverComputedDelay() {
        BackoffPolicy policy = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 1.0);
        assertEquals(5_000L, policy.delayMs(1, 5_000L));
        assertEquals(2_000L, policy.delayMs(9, 2_000L));
    }

    @Test
    void retryAfterBypassesMaxDelayCap() {
        // 关键行为：服务端说 120s，就必须等 120s。
        // 若被 32s 上限截断，我们会在服务端仍处于限流时提前重试，白费一次尝试。
        BackoffPolicy policy = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 0.0);
        assertEquals(120_000L, policy.delayMs(1, 120_000L));
        assertEquals(300_000L, policy.delayMs(3, 300_000L));
    }

    @Test
    void retryAfterIsNotJittered() {
        // Retry-After 是服务端的明确指令，再加抖动没有语义
        BackoffPolicy policy = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 1.0);
        assertEquals(10_000L, policy.delayMs(2, 10_000L));
    }

    @Test
    void nonPositiveRetryAfterFallsBackToExponential() {
        BackoffPolicy policy = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 0.0);
        assertEquals(1000L, policy.delayMs(2, 0L));
        assertEquals(1000L, policy.delayMs(2, -1L));
    }

    // -------------------------------------------------------------- 边界输入

    @Test
    void attemptBelowOneIsClampedInsteadOfThrowing() {
        // 防御性夹取：调用方把「已重试次数」和「第几次重试」搞混是常见错误，
        // 归一化到 1 比抛异常更容易定位，也不会产生负数延迟
        BackoffPolicy policy = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 0.0);
        assertEquals(500L, policy.delayMs(0));
        assertEquals(500L, policy.delayMs(-5));
    }

    @Test
    void hugeAttemptDoesNotOverflow() {
        // 2^62 次方的 double 运算仍是有限值，封顶逻辑必须吃掉它，不能溢出成负数
        BackoffPolicy policy = policy(BackoffPolicy.DEFAULT_MAX_DELAY_MS, 0.0);
        assertTrue(policy.delayMs(1_000) > 0L);
        assertEquals(32_000L, policy.delayMs(1_000));
    }

    @Test
    void defaultConstructorUsesDocumentedDefaults() {
        BackoffPolicy policy = new BackoffPolicy();
        assertEquals(32_000L, policy.maxDelayMs());
        long delay = policy.delayMs(1);
        assertTrue(delay >= 500L && delay <= 625L, "默认抖动应落在 [500,625]: " + delay);
    }
}
