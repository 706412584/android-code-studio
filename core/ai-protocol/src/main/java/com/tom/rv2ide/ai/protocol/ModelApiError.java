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

import java.net.SocketTimeoutException;

/**
 * 模型 API 错误分类器（借鉴 cc-haha classifyAPIError / shouldRetry 语义）。
 *
 * <p>把协议层抛出的 {@link com.tom.rv2ide.ai.protocol.ModelCompletionException}（message 含
 * "HTTP xxx" 前缀或 "Model stream communication failed"）与 cause 链中的
 * IO 异常分类为可判定重试策略的错误类型。</p>
 */
public final class ModelApiError {

    public enum Kind {
        RATE_LIMIT, SERVER_OVERLOAD, AUTH, SERVER_ERROR,
        TIMEOUT, CONNECTION_ERROR, WATCHDOG_IDLE, CLIENT_ERROR,
        CANCELLED, UNKNOWN
    }

    private final Kind kind;
    private final int httpStatus;
    private final long retryAfterMs;
    private final String message;

    /** 测试与编排层构造入口；retryAfterMs 单位毫秒。 */
    public ModelApiError(Kind kind, int httpStatus, long retryAfterMs, String message) {
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfterMs = retryAfterMs;
        this.message = message == null ? "" : message;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    public String message() {
        return message;
    }

    /** 该类错误是否值得重试（AUTH/CLIENT_ERROR/CANCELLED 等确定性失败不重试）。 */
    public static boolean retryable(Kind kind) {
        return kind == Kind.RATE_LIMIT
                || kind == Kind.SERVER_OVERLOAD
                || kind == Kind.SERVER_ERROR
                || kind == Kind.TIMEOUT
                || kind == Kind.CONNECTION_ERROR
                || kind == Kind.WATCHDOG_IDLE;
    }

    public boolean retryable() {
        return retryable(kind);
    }

    /** 从异常解析分类；取消判定由调用方先行处理，本方法不识别取消。 */
    public static ModelApiError fromThrowable(Throwable t) {
        return fromThrowable(t, false);
    }

    /**
     * @param watchdogFired 流空闲看门狗已触发时强制 WATCHDOG_IDLE
     *                     （disconnect 引起的 SocketException 不应被误判为普通连接错误）。
     */
    public static ModelApiError fromThrowable(Throwable t, boolean watchdogFired) {
        String message = t == null ? "" : String.valueOf(t.getMessage());
        int status = extractHttpStatus(message);
        if (watchdogFired) {
            return new ModelApiError(Kind.WATCHDOG_IDLE, status, 0, message);
        }
        // 1) SSE body 内的服务器错误事件（200 流内）：overloaded_error / rate_limit
        if (message.contains("overloaded_error") || message.contains("overloaded")) {
            return new ModelApiError(Kind.SERVER_OVERLOAD, status == -1 ? 529 : status, 0, message);
        }
        if (message.contains("rate_limit") || message.contains("Rate limit")) {
            return new ModelApiError(Kind.RATE_LIMIT, status == -1 ? 429 : status, 0, message);
        }
        // 2) cause 链中的 IO 异常特征
        Kind ioKind = classifyCause(t);
        if (ioKind != null) {
            return new ModelApiError(ioKind, status, 0, message);
        }
        // 3) "HTTP xxx" 前缀状态码分类
        if (status != -1) {
            return new ModelApiError(classifyStatus(status), status, extractRetryAfterMs(t, message), message);
        }
        return new ModelApiError(Kind.UNKNOWN, -1, 0, message);
    }

    /** Retry-After 毫秒：W2 起协议层挂载到 ModelCompletionException；当前从 message 后缀解析。 */
    private static long extractRetryAfterMs(Throwable t, String message) {
        // 形如 "HTTP 429: ... retry-after=30"（协议层拼装）；无则 0
        if (message == null) {
            return 0;
        }
        int index = message.indexOf("retry-after=");
        if (index < 0) {
            return 0;
        }
        int start = index + "retry-after=".length();
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Long.parseLong(message.substring(start, end)) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Kind classifyCause(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 6) {
            if (current instanceof SocketTimeoutException) {
                return Kind.TIMEOUT;
            }
            String name = current.getClass().getSimpleName();
            if (name.contains("SocketTimeout")) {
                return Kind.TIMEOUT;
            }
            String detail = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (detail.contains("connection reset") || detail.contains("broken pipe")
                    || detail.contains("socket closed") || detail.contains("econnreset")
                    || detail.contains("epipe") || detail.contains("econnaborted")
                    || detail.contains("connection aborted") || detail.contains("premature close")
                    || name.contains("SSLException")) {
                return Kind.CONNECTION_ERROR;
            }
            if (detail.contains("timeout") || detail.contains("timed out")) {
                return Kind.TIMEOUT;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static Kind classifyStatus(int status) {
        if (status == 429) {
            return Kind.RATE_LIMIT;
        }
        if (status == 529) {
            return Kind.SERVER_OVERLOAD;
        }
        if (status == 401 || status == 403) {
            return Kind.AUTH;
        }
        if (status == 408) {
            return Kind.TIMEOUT;
        }
        if (status >= 500) {
            return Kind.SERVER_ERROR;
        }
        if (status >= 400) {
            return Kind.CLIENT_ERROR;
        }
        return Kind.UNKNOWN;
    }

    /** 解析 message 中 "HTTP <code>" 前缀；无则 -1。 */
    static int extractHttpStatus(String message) {
        if (message == null) {
            return -1;
        }
        int index = message.indexOf("HTTP ");
        if (index < 0) {
            return -1;
        }
        int start = index + "HTTP ".length();
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
