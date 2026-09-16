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

/**
 * 模型失败后的重试决策（借鉴 cc-haha shouldRetry + withStreamRetry 组合语义）。
 * 纯函数便于 JVM 测试；编排层据此执行 RETRY / 提交部分内容 / 终止。
 */
public final class ModelRetryDecision {

    public enum Action {
        /** 直接重试（无部分内容或部分内容已单独提交）。 */
        RETRY,
        /** 提交部分内容后重试。 */
        COMMIT_PARTIAL_AND_RETRY,
        /** 提交部分内容后终止（越过工具边界，不可重发）。 */
        COMMIT_PARTIAL_AND_FAIL,
        /** 终止。 */
        FAIL
    }

    private final Action action;
    private final boolean commitPartial;

    private ModelRetryDecision(Action action, boolean commitPartial) {
        this.action = action;
        this.commitPartial = commitPartial;
    }

    public Action action() {
        return action;
    }

    /** 是否需要把 partial 内容提交进会话（RETRY/FAIL 皆可能）。 */
    public boolean commitPartial() {
        return commitPartial;
    }

    /**
     * @param kind                 错误分类
     * @param requestAttempt       已进行的请求尝试（0 起）
     * @param overloadCount        本次 generation 内 529/overloaded 次数
     * @param crossedToolBoundary  响应已出现工具调用（不可重发）
     * @param hasPartial           有已收到的部分文本
     */
    public static ModelRetryDecision evaluate(
            ModelApiError.Kind kind,
            int requestAttempt,
            int overloadCount,
            boolean crossedToolBoundary,
            boolean hasPartial
    ) {
        // 越过副作用边界：任何错误都不可重发（工具可能已被执行）
        if (crossedToolBoundary) {
            return new ModelRetryDecision(
                    hasPartial ? Action.COMMIT_PARTIAL_AND_FAIL : Action.FAIL,
                    hasPartial);
        }
        // 确定性失败：认证/客户端参数错误立即终止
        if (kind == ModelApiError.Kind.AUTH
                || kind == ModelApiError.Kind.CLIENT_ERROR
                || kind == ModelApiError.Kind.CANCELLED
                || kind == ModelApiError.Kind.UNKNOWN) {
            return new ModelRetryDecision(
                    hasPartial ? Action.COMMIT_PARTIAL_AND_FAIL : Action.FAIL,
                    hasPartial);
        }
        // 529/overloaded 专项上限
        if (kind == ModelApiError.Kind.SERVER_OVERLOAD && overloadCount >= RetryBackoff.MAX_OVERLOAD_RETRIES) {
            return new ModelRetryDecision(
                    hasPartial ? Action.COMMIT_PARTIAL_AND_FAIL : Action.FAIL,
                    hasPartial);
        }
        // 请求级总上限
        if (requestAttempt >= RetryBackoff.MAX_REQUEST_RETRIES) {
            return new ModelRetryDecision(
                    hasPartial ? Action.COMMIT_PARTIAL_AND_FAIL : Action.FAIL,
                    hasPartial);
        }
        if (hasPartial) {
            return new ModelRetryDecision(Action.COMMIT_PARTIAL_AND_RETRY, true);
        }
        return new ModelRetryDecision(Action.RETRY, false);
    }
}
