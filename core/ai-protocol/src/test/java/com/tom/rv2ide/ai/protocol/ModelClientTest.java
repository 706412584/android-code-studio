/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link ModelClient} 的门面与断流重试编排：
 * 协议分派、重试通知、以及「越过工具边界后绝不重发」这条正确性红线。
 */
final class ModelClientTest {

  private static ModelConfig config() {
    return ModelConfig.builder("t", "t", ModelProtocolType.OPENAI_COMPATIBLE, "l", "http://x", "k", "m").build();
  }

  /** 假协议：按脚本依次返回响应或抛异常，便于精确控制重试路径。 */
  private static final class ScriptedProtocol implements ModelProtocol {
    final List<Object> script = new ArrayList<>();
    int calls;

    @Override
    public ModelCompletionResponse complete(ModelConfig config, List<ModelMessage> messages) throws ModelCompletionException {
      return next();
    }

    @Override
    public ModelCompletionResponse stream(ModelConfig config, List<ModelMessage> messages,
                                          ModelStreamCallback callback, ModelCancellationToken token,
                                          ModelRequestOptions options) throws ModelCompletionException {
      return next();
    }

    private ModelCompletionResponse next() throws ModelCompletionException {
      Object step = script.get(Math.min(calls, script.size() - 1));
      calls++;
      if (step instanceof ModelCompletionException) {
        throw (ModelCompletionException) step;
      }
      return (ModelCompletionResponse) step;
    }
  }

  private static ModelClient clientFor(ScriptedProtocol protocol) {
    ModelProtocolFactory factory = new ModelProtocolFactory();
    factory.register(ModelProtocolType.OPENAI_COMPATIBLE, () -> protocol);
    return new ModelClient(factory);
  }

  @Test
  void completeDelegatesToProtocol() throws Exception {
    ScriptedProtocol protocol = new ScriptedProtocol();
    protocol.script.add(new ModelCompletionResponse("来自假协议"));
    assertEquals("来自假协议", clientFor(protocol).complete(config(), Collections.emptyList()).getText());
  }

  @Test
  void streamReturnsOnFirstSuccess() throws Exception {
    ScriptedProtocol protocol = new ScriptedProtocol();
    protocol.script.add(new ModelCompletionResponse("ok"));
    assertEquals("ok", clientFor(protocol).stream(config(), Collections.emptyList(), null, null, null).getText());
    assertEquals(1, protocol.calls);
  }

  @Test
  void streamRetriesRetryableFailureThenSucceeds() throws Exception {
    ScriptedProtocol protocol = new ScriptedProtocol();
    protocol.script.add(new ModelCompletionException("HTTP 500: oops").withHttpStatus(500).withRetryAfterMs(1));
    protocol.script.add(new ModelCompletionResponse("恢复"));
    final int[] notified = {0};
    ModelCompletionResponse response = clientFor(protocol).stream(
        config(), Collections.emptyList(), null, null, ModelRequestOptions.defaults(),
        (attempt, max, delay, cause) -> notified[0]++);
    assertEquals("恢复", response.getText());
    assertEquals(2, protocol.calls);
    assertEquals(1, notified[0]);
  }

  @Test
  void streamDoesNotRetryAfterCrossingToolBoundary() {
    ScriptedProtocol protocol = new ScriptedProtocol();
    // 已越过工具边界：重发会让工具被重复执行，必须原样抛出
    protocol.script.add(new ModelCompletionException("HTTP 500: x").withHttpStatus(500)
        .withPartial("", "", true));
    ScriptedProtocol unused = protocol;
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> clientFor(unused).stream(config(), Collections.emptyList(), null, null, null));
    assertTrue(e.crossedToolBoundary());
    assertEquals(1, unused.calls);
  }

  @Test
  void streamDoesNotRetryNonRetryableStatus() {
    ScriptedProtocol protocol = new ScriptedProtocol();
    protocol.script.add(new ModelCompletionException("HTTP 401: bad key").withHttpStatus(401));
    ScriptedProtocol unused = protocol;
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> clientFor(unused).stream(config(), Collections.emptyList(), null, null, null));
    assertEquals(401, e.httpStatus());
    assertEquals(1, unused.calls);
  }

  @Test
  void streamStopsRetryingWhenAttemptsExhausted() {
    ScriptedProtocol protocol = new ScriptedProtocol();
    // 每次都失败：只会有限次重试，不会无限循环。
    // 用 Retry-After 让每次退避都是 1ms，避免真实指数退避把测试拖成分钟级。
    protocol.script.add(new ModelCompletionException("HTTP 503: down")
        .withHttpStatus(503).withRetryAfterMs(1));
    ScriptedProtocol unused = protocol;
    assertThrows(ModelCompletionException.class,
        () -> clientFor(unused).stream(config(), Collections.emptyList(), null, null, null));
    assertEquals(RetryPolicy.DEFAULT_MAX_RETRIES + 1, unused.calls);
  }

  @Test
  void streamThrowsImmediatelyWhenTokenAlreadyCancelled() {
    ScriptedProtocol protocol = new ScriptedProtocol();
    protocol.script.add(new ModelCompletionResponse("never"));
    ModelCancellationToken token = new ModelCancellationToken();
    token.cancel();
    ModelCompletionException e = assertThrows(ModelCompletionException.class,
        () -> clientFor(protocol).stream(config(), Collections.emptyList(), null, token, null));
    assertTrue(e.getMessage().contains("取消"), e.getMessage());
    assertEquals(0, protocol.calls);
  }

  @Test
  void fourArgStreamUsesDefaultOptions() throws Exception {
    ScriptedProtocol protocol = new ScriptedProtocol();
    protocol.script.add(new ModelCompletionResponse("x"));
    assertEquals("x", clientFor(protocol).stream(config(), Collections.emptyList(), null, null).getText());
  }

  @Test
  void retryListenerIsOptional() throws Exception {
    ScriptedProtocol protocol = new ScriptedProtocol();
    protocol.script.add(new ModelCompletionException("HTTP 503: x").withHttpStatus(503).withRetryAfterMs(1));
    protocol.script.add(new ModelCompletionResponse("ok"));
    // retryListener 传 null 不应抛 NPE
    assertEquals("ok", clientFor(protocol)
        .stream(config(), Collections.emptyList(), null, null, null, null).getText());
  }

  @Test
  void shouldRetryStreamVerdicts() {
    RetryPolicy policy = new RetryPolicy(3);
    assertTrue(ModelClient.shouldRetryStream(
        new ModelCompletionException("HTTP 500").withHttpStatus(500), policy, 0));
    assertFalse(ModelClient.shouldRetryStream(
        new ModelCompletionException("HTTP 500").withHttpStatus(500).withPartial("", "", true), policy, 0));
    assertFalse(ModelClient.shouldRetryStream(
        new ModelCompletionException("HTTP 400").withHttpStatus(400), policy, 0));
    assertFalse(ModelClient.shouldRetryStream(
        new ModelCompletionException("HTTP 500").withHttpStatus(500), policy, 3));
  }

  @Test
  void shouldRetryStreamTreatsDisconnectAsStreamError() {
    // 无状态码的连接中断在流式上下文里应可重试
    assertTrue(ModelClient.shouldRetryStream(
        new ModelCompletionException("Model stream communication failed",
            new java.io.IOException("Connection reset")), new RetryPolicy(3), 0));
  }
}
