/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 锁定「断流后该不该重发」的判据。
 *
 * <p>这里最重要的是**副作用边界**：一旦模型已产出完整工具调用，重发会让工具被
 * 重复执行（重复写文件、重复跑命令）。那是正确性问题，不是效率问题——
 * 宁可把失败如实报给用户。
 */
public class StreamRetryDecisionTest {

  private static ModelCompletionException err(String message, Throwable cause) {
    return new ModelCompletionException(message, cause);
  }

  @Test
  void retriesPlainConnectionFailure() {
    // 流已开始读之后连接断了：可重发
    ModelCompletionException e = err("stream broke", new IOException("connection reset"));
    assertTrue(ModelClient.shouldRetryStream(e, new RetryPolicy(), 0));
  }

  @Test
  void retriesReadTimeout() {
    ModelCompletionException e = err("timeout", new SocketTimeoutException("read timed out"));
    assertTrue(ModelClient.shouldRetryStream(e, new RetryPolicy(), 0));
  }

  @Test
  void retriesServerError() {
    ModelCompletionException e = err("HTTP 503: busy", null).withHttpStatus(503);
    assertTrue(ModelClient.shouldRetryStream(e, new RetryPolicy(), 0));
  }

  @Test
  void doesNotRetryAfterToolBoundaryCrossed() {
    // 核心安全属性：越过工具边界后禁止重发，哪怕错误本身看起来可重试。
    ModelCompletionException e =
        err("stream broke", new IOException("connection reset"))
            .withPartial("some text", "", true);
    assertFalse(
        ModelClient.shouldRetryStream(e, new RetryPolicy(), 0),
        "越过工具边界后必须禁止重发——否则工具会被重复执行");
  }

  @Test
  void retriesWhenPartialButNoToolBoundary() {
    // 有部分文本但没越过边界：可以重发（UI 会丢弃部分输出）
    ModelCompletionException e =
        err("stream broke", new IOException("connection reset")).withPartial("partial", "", false);
    assertTrue(ModelClient.shouldRetryStream(e, new RetryPolicy(), 0));
  }

  @Test
  void doesNotRetryAuthFailure() {
    ModelCompletionException e = err("HTTP 401: bad key", null).withHttpStatus(401);
    assertFalse(ModelClient.shouldRetryStream(e, new RetryPolicy(), 0));
  }

  @Test
  void doesNotRetryClientError() {
    ModelCompletionException e = err("HTTP 400: bad request", null).withHttpStatus(400);
    assertFalse(ModelClient.shouldRetryStream(e, new RetryPolicy(), 0));
  }

  @Test
  void doesNotRetryWhenAttemptsExhausted() {
    ModelCompletionException e = err("stream broke", new IOException("reset"));
    RetryPolicy policy = new RetryPolicy(2);
    assertTrue(ModelClient.shouldRetryStream(e, policy, 1), "还剩一次时应重发");
    assertFalse(ModelClient.shouldRetryStream(e, policy, 2), "用尽次数后不该重发");
  }
}
