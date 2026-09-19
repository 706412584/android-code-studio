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

package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 重试事件必须把次数、上限、退避时长暴露成**结构化字段**。
 *
 * <p><b>为什么不能只留一段拼好的 message</b>：UI 要渲染「连接中断，8 秒后重试
 * （第 2/10 次）· HTTP 503」，其中「8」「2」「10」是三个独立数字。如果只能从
 * message 里正则提取，任何一次文案调整（换语序、加修饰）都会静默破坏界面，
 * 而且测试无法覆盖。字段化之后文案与数据可以各自演进。
 */
final class AgentEventRetryFieldsTest {

  @Test
  void retryEventCarriesStructuredNumbers() {
    AgentEvent event = AgentEvent.streamRetrying(2, 10, 8_000L, "HTTP 503: upstream down");

    assertEquals(AgentEvent.Type.STREAM_RETRYING, event.getType());
    assertEquals(2, event.getRetryAttempt());
    assertEquals(10, event.getRetryMaxAttempts());
    assertEquals(8_000L, event.getRetryDelayMs());
    assertEquals("HTTP 503: upstream down", event.getRetryReason());
  }

  @Test
  void messageRemainsHumanReadableForLogs() {
    // 结构化字段是给 UI 的，message 仍要能直接看懂——日志与不关心结构的调用方用它。
    AgentEvent event = AgentEvent.streamRetrying(2, 10, 8_000L, "HTTP 503");

    assertTrue(event.getMessage().contains("2/10"), event.getMessage());
    assertTrue(event.getMessage().contains("8s"), event.getMessage());
    assertTrue(event.getMessage().contains("HTTP 503"), event.getMessage());
  }

  @Test
  void subSecondDelayIsReportedAsAtLeastOneSecond() {
    // 退避首轮是 500ms。文案写「等待 0s」会让用户以为「马上就好」，
    // 而实际会再等半秒——取整向上更贴近体感。
    AgentEvent event = AgentEvent.streamRetrying(1, 10, 500L, "");

    assertEquals(500L, event.getRetryDelayMs(), "结构化字段保留真实毫秒值");
    assertTrue(event.getMessage().contains("1s"), event.getMessage());
  }

  @Test
  void nonRetryEventsReportZeroAndEmptyReason() {
    // 0 与空串是「本事件没有这个字段」的哨兵，UI 据此不做重试渲染。
    AgentEvent event = AgentEvent.turnStarted(3);

    assertEquals(0, event.getRetryAttempt());
    assertEquals(0, event.getRetryMaxAttempts());
    assertEquals(0L, event.getRetryDelayMs());
    assertEquals("", event.getRetryReason());
  }

  @Test
  void nullReasonBecomesEmptyStringNotLiteralNull() {
    // 拼进 message 的 null 会变成字符串 "null"，而空串不会。
    AgentEvent event = AgentEvent.streamRetrying(1, 10, 1_000L, null);

    assertEquals("", event.getRetryReason());
    assertTrue(!event.getMessage().contains("null"), event.getMessage());
  }
}
