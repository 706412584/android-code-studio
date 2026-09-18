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

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * 可重试判定 + 错误分类。
 *
 * <p>纯判定逻辑，不持有任何连接状态；「重试几次、退避多久」分别由
 * {@link #maxRetries()} 与 {@link BackoffPolicy} 决定，这里只回答
 * <b>「这次失败值不值得再发一次请求」</b>。</p>
 *
 * <p><b>为什么 408 和 409 要重试</b>：408 是服务端等我们的请求体等超时了，重发一次
 * 通常就好；409 在中转/网关层常被用来表示「上游锁超时」，同样是一次性的。
 * 这两个都不是请求本身有问题，和 400/422 那种「报文错了，重发一百次也还是错」有本质区别。</p>
 *
 * <p><b>为什么 400/401/403/404 坚决不重试</b>：这些是确定性失败——报文格式错、密钥无效、
 * 无权访问、路径不存在。重试只会把同一个错误再撞 10 次，既浪费用户配额也拖长失败反馈。
 * 尤其 401：某些网关在密钥失效时会返回 401 并附带限流计数，盲目重试可能把账号打到封禁。</p>
 *
 * <p><b>为什么网络异常默认视为可重试，但保留「明确不可重试」的出口</b>：
 * 连接层异常绝大多数是瞬时的（DNS 抖动、切网、连接被 RST）。但有一类必须排除——
 * 协议层的致命错误（如 SSE 报文无法解析、证书校验失败）同样是 IOException 子类，
 * 重试只会稳定复现。判断依据是异常类型而非 message 文本：解析 message 极易被
 * 服务器回显的任意文本带偏。</p>
 */
public final class RetryPolicy {

    /** 请求级重试上限默认值（与 cc-haha 一致）。 */
    public static final int DEFAULT_MAX_RETRIES = 10;

    /**
     * 错误分类。既用于「是否重试」的判定，也用于 UI 差异化提示——
     * 用户需要看到「限流，稍后自动重试」而不是笼统的「请求失败」。
     */
    public enum ErrorCategory {
        /** 连接失败/被拒/被重置：还没建立起可用会话。 */
        CONNECTION,
        /** 超时：连接超时或读取超时。 */
        TIMEOUT,
        /** 429 限流。 */
        RATE_LIMIT,
        /** 5xx 服务端故障。 */
        SERVER_ERROR,
        /** 流已建立但中途断开：与 CONNECTION 分开，因为此时可能已有部分内容。 */
        STREAM_DISCONNECT,
        /** 401/403：密钥或权限问题，重试无意义。 */
        AUTH,
        /** 其余 4xx：请求本身有问题。 */
        CLIENT_ERROR,
        /** 无法归类的失败。 */
        UNKNOWN;

        /** 该分类是否可重试。委托给 {@link RetryPolicy#retryable(ErrorCategory)}，保证判定只有一处实现。 */
        public boolean retryable() {
            return RetryPolicy.retryable(this);
        }
    }

    /** SSE 流内错误事件中「值得重发」的 error.type 白名单。 */
    private static final String[] RETRYABLE_STREAM_ERROR_TYPES = {
            "api_error",
            "overloaded_error",
            "upstream_error",
            "stream_read_error"
    };

    private final int maxRetries;

    public RetryPolicy() {
        this(DEFAULT_MAX_RETRIES);
    }

    public RetryPolicy(int maxRetries) {
        // 夹到 >= 0：负数会让调用方以为「还能重试」却在第一次比较时就失败，
        // 属于配置错误应尽早归一化而不是让它在循环里表现为诡异行为
        this.maxRetries = Math.max(0, maxRetries);
    }

    public int maxRetries() {
        return maxRetries;
    }

    // ---------------------------------------------------------------- 分类

    /** 由 HTTP 状态码分类；非错误码（&lt; 400）归入 UNKNOWN。 */
    public static ErrorCategory classifyStatus(int statusCode) {
        if (statusCode == 408) {
            // 归类 TIMEOUT 而非 CLIENT_ERROR：对调用方而言这与读超时是同一类可恢复情况
            return ErrorCategory.TIMEOUT;
        }
        if (statusCode == 429) {
            return ErrorCategory.RATE_LIMIT;
        }
        if (statusCode == 401 || statusCode == 403) {
            return ErrorCategory.AUTH;
        }
        if (statusCode >= 500) {
            return ErrorCategory.SERVER_ERROR;
        }
        if (statusCode >= 400) {
            return ErrorCategory.CLIENT_ERROR;
        }
        return ErrorCategory.UNKNOWN;
    }

    /** 状态码是否可重试。 */
    public static boolean retryableStatus(int statusCode) {
        return classifyStatus(statusCode).retryable();
    }

    /**
     * 从异常分类。{@code streamDisconnected} 由调用方告知——
     * 只有编排层知道「流是否已经开始读取」，纯异常对象里没有这个信息。
     */
    public static ErrorCategory classify(Throwable t, boolean streamDisconnected) {
        if (t instanceof SocketTimeoutException) {
            return ErrorCategory.TIMEOUT;
        }
        if (t instanceof IOException) {
            // 「明确不可重试」的 IO 异常优先排除，否则会被下面的默认分支当成瞬时故障重放
            if (isExplicitlyNonRetryable(t)) {
                return ErrorCategory.CLIENT_ERROR;
            }
            // 注意 InterruptedIOException（SocketTimeoutException 的父类）不走 TIMEOUT：
            // 排除超时子类之后，剩下的「IO 被中断」在真实场景里几乎都是读流被打断，
            // 归到 CONNECTION 更贴近事实。若编排层知道流已经开始读取，应传
            // streamDisconnected=true，得到更精确的 STREAM_DISCONNECT。
            // 三者都是可重试分类，此处只影响 UI 文案与日志归因。
            if (streamDisconnected) {
                return ErrorCategory.STREAM_DISCONNECT;
            }
            return ErrorCategory.CONNECTION;
        }
        // 协议层把 HTTP 状态拼进 message（"HTTP 429: ..."）；复用已有解析器，
        // 避免在第二个地方重新实现一遍状态码提取
        int status = ModelApiError.extractHttpStatus(t == null ? null : t.getMessage());
        if (status != -1) {
            return classifyStatus(status);
        }
        return ErrorCategory.UNKNOWN;
    }

    public static ErrorCategory classify(Throwable t) {
        return classify(t, false);
    }

    /**
     * 是否为「明确不可重试」的 IO 异常。
     *
     * <p>用异常类型判断而非 message 文本：message 里可能回显服务端返回的任意内容，
     * 拿它做判定会被对方牵着走（例如响应体里恰好出现 "retry" 字样）。</p>
     *
     * <p>证书类异常的判定只能靠类名：{@code SSLHandshakeException} 在 Android 与
     * OpenJDK 上的包名/层级不同，且部分中转会包装成自有异常，直接按类名包含关系匹配更稳。
     * 这类失败重试只会稳定复现，还会白白多耗 10 次配额。</p>
     */
    private static boolean isExplicitlyNonRetryable(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 6) {
            if (current instanceof java.io.FileNotFoundException
                    || current instanceof java.net.ProtocolException
                    || current instanceof java.nio.charset.CharacterCodingException) {
                return true;
            }
            String name = current.getClass().getSimpleName();
            if (name.indexOf("SSLHandshakeException") >= 0
                    || name.indexOf("SSLPeerUnverifiedException") >= 0
                    || name.indexOf("CertificateException") >= 0) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /** SSE body 中 {@code error.type} 是否属于可重试白名单。 */
    public static boolean retryableStreamErrorType(String errorType) {
        if (errorType == null) {
            return false;
        }
        String normalized = errorType.trim().toLowerCase(Locale.ROOT);
        for (String candidate : RETRYABLE_STREAM_ERROR_TYPES) {
            if (candidate.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 可重试判定

    /** 分类本身是否意味着可重试。 */
    public static boolean retryable(ErrorCategory category) {
        if (category == null) {
            return false;
        }
        switch (category) {
            case CONNECTION:
            case TIMEOUT:
            case RATE_LIMIT:
            case SERVER_ERROR:
            case STREAM_DISCONNECT:
                return true;
            default:
                // AUTH / CLIENT_ERROR / UNKNOWN：确定性失败，或信息不足以判断——
                // UNKNOWN 归入不可重试是保守选择，避免把「报文解析失败」当成瞬时故障反复重放
                return false;
        }
    }

    /** HTTP 状态码判定（含 409 lock 超时）。 */
    public static boolean isRetryable(int statusCode) {
        if (statusCode == 409) {
            return true;
        }
        return retryableStatus(statusCode);
    }

    public boolean canRetry(int statusCode) {
        return isRetryable(statusCode);
    }

    public boolean canRetry(Throwable t, boolean streamDisconnected) {
        return retryable(classify(t, streamDisconnected));
    }

    public boolean canRetry(ErrorCategory category) {
        return retryable(category);
    }

    /**
     * 尝试次数是否还在预算内。
     *
     * @param attemptsSoFar 已经失败掉的尝试次数（1 表示刚失败的是第一次）
     */
    public boolean hasAttemptsLeft(int attemptsSoFar) {
        return attemptsSoFar <= maxRetries;
    }
}
