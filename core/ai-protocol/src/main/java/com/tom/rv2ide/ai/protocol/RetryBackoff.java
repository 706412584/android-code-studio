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

/**
 * 重试退避计算（借鉴 cc-haha getRetryDelay）：500ms 基数指数退避、上限 32s、
 * +[0,25%) jitter；服务器下发 Retry-After 时优先服从（同样 cap 32s）。
 */
public final class RetryBackoff {

    public static final int MAX_REQUEST_RETRIES = 10;
    public static final int MAX_OVERLOAD_RETRIES = 3;
    public static final int MAX_STREAM_RETRIES = 4;
    public static final long STREAM_RETRY_BUDGET_MS = 60_000L;

    static final long BASE_DELAY_MS = 500L;
    static final long MAX_DELAY_MS = 32_000L;

    private RetryBackoff() {
    }

    /** attempt 从 0 计：第 1 次重试约 500-625ms，之后翻倍，cap 32s。 */
    public static long delayMs(int attempt, ModelApiError error, Random random) {
        long retryAfter = error == null ? 0 : error.retryAfterMs();
        if (retryAfter > 0) {
            return Math.min(retryAfter, MAX_DELAY_MS);
        }
        int safeAttempt = Math.max(0, attempt);
        double base = Math.min(BASE_DELAY_MS * Math.pow(2, safeAttempt), MAX_DELAY_MS);
        double jitterRatio = random == null ? 0 : random.nextDouble() * 0.25;
        return (long) (base * (1.0 + jitterRatio));
    }
}
