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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 工具边界信息不能因为「正文为空」而丢失。
 *
 * <p><b>为什么必须钉住</b>：{@code ModelClient.shouldRetryStream} 的第一条否决条件就是
 * {@code crossedToolBoundary()}——越过边界的流不允许重发，否则重发会让工具**执行两次**
 * （重复写文件、重复跑命令）。这是正确性问题，不是效率问题。
 *
 * <p>而模型最常见的断流形态恰好是「先吐一个完整工具调用，正文一个字都没有，然后断」。
 * 此时 {@link AssistantCommitBuffer} 里 text/reasoning 都是空的，只有一个
 * {@code toolBoundaryCrossed} 标志。协议层若按「有部分内容才带信息」的逻辑走，
 * 这个标志就会被丢掉，重发保护随之失效。
 *
 * <p>实测复现：一次运行里模型发起 todo_update 后连接被对端关闭，日志出现连续两次
 * {@code ai[sse_io] Connection closed by peer}——同一步被跑了两遍。
 *
 * <p>本测试调用的是**协议层的真实方法**（两条序列化路径各一份），不是复刻的判定逻辑——
 * 复刻等于测试自己，判定被改回去测试仍然全绿。
 */
final class ToolBoundaryPreservationTest {

  /** 两条序列化路径各自持有一份实现，两份都必须守住同一个契约。 */
  private interface Attacher {
    ModelCompletionException attach(ModelCompletionException e, AssistantCommitBuffer buffer);
  }

  private static final List<Attacher> ATTACHERS =
      java.util.Arrays.asList(OpenAiCompatibleProtocol::attachPartial, AnthropicMessagesProtocol::attachPartial);

  private static final List<String> NAMES = java.util.Arrays.asList("openai", "anthropic");

  /**
   * 构造与协议层实际抛出的形态一致的异常。
   *
   * <p><b>必须带 IOException cause</b>：协议层在 IO 失败时抛的是
   * `new ModelCompletionException("Model stream communication failed: …", e)`，
   * 而 {@link RetryPolicy#classify} 要下钻 cause 链才能认出「这是可重试的连接问题」。
   * 只给一个裸的 ModelCompletionException，分类会落到 UNKNOWN（不可重试）——
   * 那样测出来的「可重试」结论是假的，与真实路径无关。
   */
  private static ModelCompletionException streamFailure() {
    return new ModelCompletionException(
        "Model stream communication failed: Connection closed by peer",
        new java.io.IOException("Connection closed by peer"));
  }

  /** 边界已越过、正文为空——最容易丢失信息的那种状态。 */
  @Test
  void boundaryIsCarriedEvenWhenThereIsNoPartialText() {
    for (int i = 0; i < ATTACHERS.size(); i++) {
      Attacher attacher = ATTACHERS.get(i);
      String name = NAMES.get(i);
      AssistantCommitBuffer buffer = new AssistantCommitBuffer();
      buffer.markToolUseStarted();

      assertFalse(buffer.hasPartial(), "前提：正文与推理都为空");
      assertTrue(buffer.crossedToolBoundary(), "前提：边界已越过");

      ModelCompletionException exception = attacher.attach(streamFailure(), buffer);

      assertTrue(
          exception.crossedToolBoundary(),
          name + ": 边界必须随异常带出，否则 ModelClient 会允许重发并重复执行工具");
      // 端到端确认：这个异常在 ModelClient 眼里确实不可重试。
      assertFalse(
          ModelClient.shouldRetryStream(exception, new RetryPolicy(), 0),
          name + ": 越过工具边界的失败必须被判为不可重发");
    }
  }

  /** 正文与边界都有时，两者都要带。 */
  @Test
  void boundaryAndPartialTextAreBothCarried() {
    for (int i = 0; i < ATTACHERS.size(); i++) {
      String name = NAMES.get(i);
      AssistantCommitBuffer buffer = new AssistantCommitBuffer();
      buffer.appendText("I will update the file.");
      buffer.markToolUseStarted();

      ModelCompletionException exception = ATTACHERS.get(i).attach(streamFailure(), buffer);

      assertTrue(exception.crossedToolBoundary(), name);
      assertEquals("I will update the file.", exception.partialText(), name);
    }
  }

  /**
   * 只有正文、没有边界：边界为 false，重发被允许——这是正常的可重试情形。
   *
   * <p>这条同时是对 {@link #streamFailure()} 的校验：若构造的异常形态不真实，
   * 这里会意外地测出「不可重试」，从而让上一条测试的结论失去意义。
   */
  @Test
  void partialTextWithoutBoundaryLeavesRetryAllowed() {
    for (int i = 0; i < ATTACHERS.size(); i++) {
      Attacher attacher = ATTACHERS.get(i);
      String name = NAMES.get(i);
      AssistantCommitBuffer buffer = new AssistantCommitBuffer();
      buffer.appendText("half a sentence");

      ModelCompletionException exception = attacher.attach(streamFailure(), buffer);

      assertFalse(exception.crossedToolBoundary(), name + ": 没越过边界时不该声称越过了");
      assertEquals("half a sentence", exception.partialText(), name);
      assertTrue(
          ModelClient.shouldRetryStream(exception, new RetryPolicy(), 0),
          name + ": 未越界的断流应允许重发");
    }
  }

  /** 缓冲全空：没有任何可带的信息，异常原样返回。 */
  @Test
  void emptyBufferCarriesNothing() {
    for (int i = 0; i < ATTACHERS.size(); i++) {
      String name = NAMES.get(i);
      AssistantCommitBuffer buffer = new AssistantCommitBuffer();

      ModelCompletionException exception = ATTACHERS.get(i).attach(streamFailure(), buffer);

      assertFalse(exception.hasPartial(), name);
      assertFalse(exception.crossedToolBoundary(), name);
    }
  }

  /** 只有推理、没有边界：同样不该声称越过边界。 */
  @Test
  void reasoningOnlyDoesNotFakeTheBoundary() {
    for (int i = 0; i < ATTACHERS.size(); i++) {
      String name = NAMES.get(i);
      AssistantCommitBuffer buffer = new AssistantCommitBuffer();
      buffer.appendReasoning("thinking about it");

      ModelCompletionException exception = ATTACHERS.get(i).attach(streamFailure(), buffer);

      assertFalse(exception.crossedToolBoundary(), name);
      assertEquals("thinking about it", exception.partialReasoning(), name);
    }
  }

  /** 异常上已带部分内容时不该被覆盖——上层可能已经填过更完整的信息。 */
  @Test
  void existingPartialOnTheExceptionIsNotOverwritten() {
    for (int i = 0; i < ATTACHERS.size(); i++) {
      String name = NAMES.get(i);
      AssistantCommitBuffer buffer = new AssistantCommitBuffer();
      buffer.appendText("from buffer");

      ModelCompletionException exception =
          ATTACHERS
              .get(i)
              .attach(
                  streamFailure().withPartial("already set", "", true),
                  buffer);

      assertEquals("already set", exception.partialText(), name + ": 已有部分内容不应被覆盖");
      assertTrue(exception.crossedToolBoundary(), name);
    }
  }
}
