/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.protocol;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试退避策略：指数增长 + 单向抖动 + Retry-After 优先。
 *
 * <p>纯计算、无 IO、无时钟依赖——「什么时候真的 sleep」由调用方决定，
 * 这样重试节奏可以在单测里被完整断言，而不需要真的等待。</p>
 *
 * <p><b>为什么抖动只加不减</b>：常见做法是 ±jitter（正负各半），但负抖动会让实际延迟
 * 短于「指数退避承诺的下限」，在服务端已经明确进入 overload 时等于加压。
 * 这里取 {@code [base, 1.25*base)}，保证退避不会比基准更快。</p>
 *
 * <p><b>为什么 Retry-After 绕过 maxDelayMs</b>：上限的用途是防止<em>我们自算的</em>指数
 * 退避把等待时间推到不可接受的量级。而 Retry-After 是服务端主动给出的恢复时间承诺——
 * 截断它只会让我们在服务端仍然限流时提前重试，白白浪费一次尝试并把退避计数推高。
 * 所以服务端说了算，我们原样服从。注意此处<em>不加抖动</em>：Retry-After 是服务端的
 * 明确指令，再抖一下没有语义。</p>
 *
 * <p>与同目录 {@link RetryBackoff} 的关系：{@code RetryBackoff} 是更早一次移植的产物，
 * 语义有三处不同（attempt 从 0 起、Retry-After 被上限截断、jitter 通过静态参数传入）。
 * 本类是新规格下的实现，两者暂并存，避免改动既有调用点。</p>
 */
public final class BackoffPolicy {

    /** 指数退避上限默认值：32s 之后不再增长，避免用户面对分钟级静默。 */
    public static final long DEFAULT_MAX_DELAY_MS = 32_000L;

    /** 退避基数：第 1 次重试的等待基准。 */
    static final long BASE_DELAY_MS = 500L;

    /** 抖动上限比例：实际延迟落在 [base, base * (1 + JITTER_RATIO))。 */
    static final double JITTER_RATIO = 0.25;

    private final long maxDelayMs;
    private final Random random;

    /** 生产用构造：ThreadLocalRandom 避免多请求线程争抢同一个 Random 的 CAS。 */
    public BackoffPolicy() {
        this(DEFAULT_MAX_DELAY_MS, ThreadLocalRandom.current());
    }

    public BackoffPolicy(long maxDelayMs) {
        this(maxDelayMs, ThreadLocalRandom.current());
    }

    /**
     * @param maxDelayMs 指数退避上限，仅约束自算退避；Retry-After 不受其约束
     * @param random     可注入，便于单测取确定值；传 null 等价于零抖动
     */
    public BackoffPolicy(long maxDelayMs, Random random) {
        // 下限取 1ms 而非 0：0 会让调用方陷入「退避后立即重试」的忙循环
        this.maxDelayMs = Math.max(1L, maxDelayMs);
        this.random = random;
    }

    /** 无服务端提示时的退避时长。 */
    public long delayMs(int attempt) {
        return delayMs(attempt, 0L);
    }

    /**
     * @param attempt      第几次重试，<b>从 1 开始</b>；小于 1 会被夹到 1（防御性，不抛异常）
     * @param retryAfterMs 服务端 Retry-After（毫秒）；&gt; 0 时直接采用且绕过上限
     */
    public long delayMs(int attempt, long retryAfterMs) {
        if (retryAfterMs > 0L) {
            return retryAfterMs;
        }
        int safeAttempt = Math.max(1, attempt);
        // 用 (safeAttempt - 1) 作为指数，使 attempt=1 恰好落在基数上，
        // 而不是 2 倍基数——否则「第一次重试等 1 秒」会与直觉和文档都不符
        double base = Math.min(BASE_DELAY_MS * Math.pow(2.0, safeAttempt - 1), (double) maxDelayMs);
        double jitter = random == null ? 0.0 : random.nextDouble() * JITTER_RATIO * base;
        return (long) (base + jitter);
    }

    public long maxDelayMs() {
        return maxDelayMs;
    }
}
