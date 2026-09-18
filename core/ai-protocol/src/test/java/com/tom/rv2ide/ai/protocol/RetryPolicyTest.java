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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.RetryPolicy.ErrorCategory;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * 可重试判定与错误分类测试。
 *
 * <p>逐条覆盖规格中的状态码与 SSE error.type，重点断言「不可重试」的那些——
 * 误判成可重试的代价是把确定性失败重放 10 次，比漏判严重。</p>
 */
final class RetryPolicyTest {

    // ------------------------------------------------------------ 状态码分类

    @Test
    void retryableStatusCodes() {
        // 408：服务端等请求体超时，重发通常就好
        assertTrue(RetryPolicy.retryableStatus(408));
        // 429：限流，必须等（配合 BackoffPolicy 的 Retry-After 生效）
        assertTrue(RetryPolicy.retryableStatus(429));
        // 全部 5xx
        assertTrue(RetryPolicy.retryableStatus(500));
        assertTrue(RetryPolicy.retryableStatus(502));
        assertTrue(RetryPolicy.retryableStatus(503));
        assertTrue(RetryPolicy.retryableStatus(504));
        assertTrue(RetryPolicy.retryableStatus(529));
        assertTrue(RetryPolicy.retryableStatus(599));
    }

    @Test
    void nonRetryableStatusCodes() {
        // 确定性失败：报文错 / 密钥无效 / 无权 / 不存在
        assertFalse(RetryPolicy.retryableStatus(400));
        assertFalse(RetryPolicy.retryableStatus(401));
        assertFalse(RetryPolicy.retryableStatus(403));
        assertFalse(RetryPolicy.retryableStatus(404));
        assertFalse(RetryPolicy.retryableStatus(405));
        assertFalse(RetryPolicy.retryableStatus(422));
        assertFalse(RetryPolicy.retryableStatus(451));
        assertFalse(RetryPolicy.retryableStatus(499));
    }

    @Test
    void statusClassification() {
        assertEquals(ErrorCategory.TIMEOUT, RetryPolicy.classifyStatus(408));
        assertEquals(ErrorCategory.RATE_LIMIT, RetryPolicy.classifyStatus(429));
        assertEquals(ErrorCategory.AUTH, RetryPolicy.classifyStatus(401));
        assertEquals(ErrorCategory.AUTH, RetryPolicy.classifyStatus(403));
        assertEquals(ErrorCategory.SERVER_ERROR, RetryPolicy.classifyStatus(500));
        assertEquals(ErrorCategory.SERVER_ERROR, RetryPolicy.classifyStatus(529));
        assertEquals(ErrorCategory.CLIENT_ERROR, RetryPolicy.classifyStatus(400));
        assertEquals(ErrorCategory.CLIENT_ERROR, RetryPolicy.classifyStatus(404));
        assertEquals(ErrorCategory.CLIENT_ERROR, RetryPolicy.classifyStatus(409));
        assertEquals(ErrorCategory.UNKNOWN, RetryPolicy.classifyStatus(200));
        assertEquals(ErrorCategory.UNKNOWN, RetryPolicy.classifyStatus(-1));
    }

    // -------------------------------------------------------- 409 lock 超时

    @Test
    void conflictIsRetryableDespiteBeingFourHundred() {
        // 409 是 4xx 里的例外：中转/网关用它表示「上游锁超时」，是一次性的
        assertTrue(RetryPolicy.isRetryable(409));
        // 但它仍归类为 CLIENT_ERROR——分类服务于 UI 展示，判定服务于重试节奏，
        // 两者不必一致，这正是 isRetryable 与 classifyStatus 分开的原因
        assertEquals(ErrorCategory.CLIENT_ERROR, RetryPolicy.classifyStatus(409));
    }

    // ---------------------------------------------------------- 异常分类

    @Test
    void connectionFailuresAreRetryable() {
        assertTrue(RetryPolicy.retryable(ErrorCategory.CONNECTION));
        assertTrue(RetryPolicy.retryable(ErrorCategory.TIMEOUT));
        assertTrue(RetryPolicy.retryable(ErrorCategory.STREAM_DISCONNECT));
        assertTrue(RetryPolicy.retryable(ErrorCategory.RATE_LIMIT));
        assertTrue(RetryPolicy.retryable(ErrorCategory.SERVER_ERROR));
    }

    @Test
    void deterministicCategoriesAreNotRetryable() {
        assertFalse(RetryPolicy.retryable(ErrorCategory.AUTH));
        assertFalse(RetryPolicy.retryable(ErrorCategory.CLIENT_ERROR));
        // UNKNOWN 保守地判为不可重试：信息不足时重放可能稳定复现同一个错误
        assertFalse(RetryPolicy.retryable(ErrorCategory.UNKNOWN));
        assertFalse(RetryPolicy.retryable(null));
    }

    @Test
    void socketTimeoutClassifiesAsTimeout() {
        assertEquals(ErrorCategory.TIMEOUT, RetryPolicy.classify(new SocketTimeoutException("read timed out")));
    }

    @Test
    void ioExceptionsClassifyAsConnectionUnlessStreamDisconnected() {
        IOException io = new IOException("boom");
        // 同一个异常，语义由编排层补充：还没读到数据 vs 流已开始但断了
        assertEquals(ErrorCategory.CONNECTION, RetryPolicy.classify(io, false));
        assertEquals(ErrorCategory.STREAM_DISCONNECT, RetryPolicy.classify(io, true));
        assertEquals(ErrorCategory.CONNECTION, RetryPolicy.classify(io));
    }

    @Test
    void commonNetworkExceptionsAreClassifiedAsConnection() {
        // 这些是 Android 上最常见的瞬时故障形态
        assertEquals(ErrorCategory.CONNECTION, RetryPolicy.classify(new ConnectException("Connection refused")));
        assertEquals(ErrorCategory.CONNECTION, RetryPolicy.classify(new UnknownHostException("api.example.com")));
        assertEquals(ErrorCategory.CONNECTION, RetryPolicy.classify(new SocketException("Connection reset")));
        assertEquals(ErrorCategory.CONNECTION, RetryPolicy.classify(new EOFException("unexpected end of stream")));
        // InterruptedIOException 是 SocketTimeoutException 的父类，但排除超时子类后
        // 它代表「读流被打断」，归 CONNECTION 而非 TIMEOUT
        assertEquals(ErrorCategory.CONNECTION, RetryPolicy.classify(new InterruptedIOException("interrupted")));
    }

    @Test
    void explicitlyNonRetryableIoExceptionsAreRejected() {
        // 这几类同样是 IOException，但重试只会稳定复现，还会白耗 10 次配额
        assertFalse(RetryPolicy.retryable(RetryPolicy.classify(
                new java.io.FileNotFoundException("no such endpoint"))));
        assertFalse(RetryPolicy.retryable(RetryPolicy.classify(
                new java.net.ProtocolException("unexpected method"))));
        assertFalse(RetryPolicy.retryable(RetryPolicy.classify(
                new java.nio.charset.CharacterCodingException() {
                })));
        assertEquals(ErrorCategory.CLIENT_ERROR,
                RetryPolicy.classify(new java.net.ProtocolException("bad request line")));
    }

    @Test
    void nonRetryableIoExceptionIsFoundInCauseChain() {
        // 协议层常见的包装形态：外层是普通 IOException，根因才是致命错误。
        // 只看最外层会误判为可重试。
        Throwable wrapped = new IOException("wrapper",
                new java.io.FileNotFoundException("missing"));
        assertFalse(RetryPolicy.retryable(RetryPolicy.classify(wrapped)));
    }

    @Test
    void sslHandshakeFailureIsNotRetryable() {
        // 证书/握手失败在 Android 与 OpenJDK 上的异常层级不同，按类名匹配
        class SSLHandshakeException extends IOException {
        }
        assertFalse(RetryPolicy.retryable(RetryPolicy.classify(new SSLHandshakeException())));
    }

    @Test
    void httpStatusEmbeddedInMessageIsUsedWhenNoIOExceptionCause() {
        // 协议层把状态码拼进 message（"HTTP 429: ..."）；没有 IO cause 时仍要能分类
        assertEquals(ErrorCategory.RATE_LIMIT,
                RetryPolicy.classify(new RuntimeException("HTTP 429: rate limited")));
        assertEquals(ErrorCategory.AUTH,
                RetryPolicy.classify(new RuntimeException("HTTP 401: invalid api key")));
        assertEquals(ErrorCategory.SERVER_ERROR,
                RetryPolicy.classify(new RuntimeException("HTTP 503: upstream unavailable")));
        assertEquals(ErrorCategory.UNKNOWN,
                RetryPolicy.classify(new RuntimeException("something went sideways")));
        assertEquals(ErrorCategory.UNKNOWN, RetryPolicy.classify(null));
    }

    // --------------------------------------------------- SSE error.type

    @Test
    void retryableStreamErrorTypes() {
        assertTrue(RetryPolicy.retryableStreamErrorType("api_error"));
        assertTrue(RetryPolicy.retryableStreamErrorType("overloaded_error"));
        assertTrue(RetryPolicy.retryableStreamErrorType("upstream_error"));
        assertTrue(RetryPolicy.retryableStreamErrorType("stream_read_error"));
    }

    @Test
    void streamErrorTypeMatchingIsCaseAndWhitespaceInsensitive() {
        // 不同中转的 JSON 大小写与空白不一致，不能要求严格相等
        assertTrue(RetryPolicy.retryableStreamErrorType("API_ERROR"));
        assertTrue(RetryPolicy.retryableStreamErrorType("  Overloaded_Error  "));
        assertTrue(RetryPolicy.retryableStreamErrorType("STREAM_READ_ERROR"));
    }

    @Test
    void nonRetryableStreamErrorTypes() {
        // 策略拒绝：重发一百次也还是被拒
        assertFalse(RetryPolicy.retryableStreamErrorType("invalid_request_error"));
        assertFalse(RetryPolicy.retryableStreamErrorType("authentication_error"));
        assertFalse(RetryPolicy.retryableStreamErrorType("permission_error"));
        assertFalse(RetryPolicy.retryableStreamErrorType("content_policy_violation"));
        assertFalse(RetryPolicy.retryableStreamErrorType(null));
        assertFalse(RetryPolicy.retryableStreamErrorType(""));
        assertFalse(RetryPolicy.retryableStreamErrorType("   "));
    }

    // -------------------------------------------------------------- 次数上限

    @Test
    void defaultMaxRetriesIsTen() {
        assertEquals(10, new RetryPolicy().maxRetries());
        assertEquals(RetryPolicy.DEFAULT_MAX_RETRIES, new RetryPolicy().maxRetries());
    }

    @Test
    void maxRetriesIsInjectable() {
        assertEquals(3, new RetryPolicy(3).maxRetries());
        // 负数夹到 0，避免「以为还能重试」却立即失败
        assertEquals(0, new RetryPolicy(-7).maxRetries());
    }

    @Test
    void attemptsBudgetBoundary() {
        RetryPolicy policy = new RetryPolicy(10);
        assertTrue(policy.hasAttemptsLeft(1));
        assertTrue(policy.hasAttemptsLeft(10));
        // 第 11 次失败意味着已经重试满 10 次
        assertFalse(policy.hasAttemptsLeft(11));
        assertFalse(policy.hasAttemptsLeft(12));
    }

    @Test
    void instanceHelpersDelegateToStaticRules() {
        RetryPolicy policy = new RetryPolicy(10);
        assertTrue(policy.canRetry(429));
        assertTrue(policy.canRetry(409));
        assertFalse(policy.canRetry(400));
        assertTrue(policy.canRetry(new SocketException("reset"), false));
        assertTrue(policy.canRetry(ErrorCategory.SERVER_ERROR));
        assertFalse(policy.canRetry(ErrorCategory.AUTH));
    }
}
