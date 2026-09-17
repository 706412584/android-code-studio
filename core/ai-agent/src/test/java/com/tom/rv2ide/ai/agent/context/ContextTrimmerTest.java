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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 裁剪的核心不变量：工具调用与其结果同生共死，且最新一组无条件保留。
 *
 * <p>每个测试的 token 数都按 {@link TokenEstimator} 的公式手算过（ASCII 4 字符/token，
 * 每条消息固定开销 4），所以预算值看起来"不整"是刻意的——它们正好卡在某个分组的边界上。
 */
final class ContextTrimmerTest {

  /** 一条 4 字符 ASCII 用户消息：固定开销 4 + 1 = 5 tokens。 */
  private static UserModelMessage user(String text) {
    return new UserModelMessage(text);
  }

  /** 请求一次工具调用的助手消息：4 + name(1) + args "{}"(1) = 6 tokens。 */
  private static AssistantModelMessage assistantCalling() {
    return new AssistantModelMessage("", "", List.of(new ToolCall("c1", "t", "{}")));
  }

  /** 对应的工具结果：4 + content(1) + id(1) + name(1) = 7 tokens。 */
  private static ToolModelMessage toolResult() {
    return new ToolModelMessage("aaaa", "c1", "t");
  }

  @Test
  void nullInputReturnsEmptyResult() {
    ContextTrimmer.Result result = ContextTrimmer.trim(null, 100);
    assertTrue(result.getMessages().isEmpty());
    assertFalse(result.wasTrimmed());
    assertEquals(0, result.getOriginalTokens());
  }

  @Test
  void emptyInputReturnsEmptyResult() {
    ContextTrimmer.Result result = ContextTrimmer.trim(List.of(), 100);
    assertTrue(result.getMessages().isEmpty());
    assertFalse(result.wasTrimmed());
  }

  @Test
  void withinBudgetReturnsEverythingUntouched() {
    List<ModelMessage> messages = List.of(user("aaaa"), user("bbbb"));
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 1000);
    assertEquals(2, result.getMessages().size());
    assertEquals(10, result.getOriginalTokens());
    assertEquals(10, result.getKeptTokens());
    assertEquals(0, result.getDroppedMessages());
    assertFalse(result.wasTrimmed());
  }

  @Test
  void exactlyAtBudgetIsNotTrimmed() {
    List<ModelMessage> messages = List.of(user("aaaa"), user("bbbb"));
    // 恰好 10 == 10，属于"在预算内"
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 10);
    assertEquals(2, result.getMessages().size());
    assertFalse(result.wasTrimmed());
  }

  @Test
  void nonPositiveBudgetIsTreatedAsUnlimited() {
    // 预算语义未配置时不该把历史清空——那是比超限更糟的失败
    List<ModelMessage> messages = List.of(user("aaaa"), user("bbbb"));
    assertEquals(2, ContextTrimmer.trim(messages, 0).getMessages().size());
    assertEquals(2, ContextTrimmer.trim(messages, -5).getMessages().size());
  }

  @Test
  void dropsOldestGroupsFirst() {
    List<ModelMessage> messages =
        List.of(user("aaaa"), user("bbbb"), user("cccc"), user("dddd"));
    // 总 20；预算 12 只能装下最近两条
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 12);
    assertEquals(2, result.getMessages().size());
    assertEquals("cccc", result.getMessages().get(0).getContent());
    assertEquals("dddd", result.getMessages().get(1).getContent());
    assertEquals(10, result.getKeptTokens());
    assertEquals(2, result.getDroppedMessages());
    assertTrue(result.wasTrimmed());
  }

  @Test
  void keepsAtLeastTwoMessagesEvenWhenOverBudget() {
    List<ModelMessage> messages = List.of(user("aaaa"), user("bbbb"), user("cccc"));
    // 预算 6 连两条都装不下，但最近两条必须留下
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 6);
    assertEquals(2, result.getMessages().size());
    assertEquals("bbbb", result.getMessages().get(0).getContent());
    assertEquals("cccc", result.getMessages().get(1).getContent());
  }

  @Test
  void newestGroupIsKeptEvenIfItAloneExceedsBudget() {
    List<ModelMessage> messages = List.of(user("aaaa"), user("bbbb"), user("cccc"));
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 1);
    // 丢掉最新一组等于没有上下文可用
    assertEquals(2, result.getMessages().size());
    assertTrue(result.getKeptTokens() > 1);
  }

  @Test
  void keepsToolCallAndResultTogetherWhenGroupFits() {
    List<ModelMessage> messages =
        List.of(user("aaaa"), assistantCalling(), toolResult(), user("bbbb"), user("cccc"));
    // 总 28；预算 26 恰好能装下 [assistant+tool, user, user]
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 26);

    assertEquals(4, result.getMessages().size());
    assertInstanceOf(AssistantModelMessage.class, result.getMessages().get(0));
    assertInstanceOf(ToolModelMessage.class, result.getMessages().get(1));
    assertEquals(23, result.getKeptTokens());
    assertEquals(1, result.getDroppedMessages());
  }

  @Test
  void dropsToolCallAndResultTogetherWhenGroupDoesNotFit() {
    List<ModelMessage> messages =
        List.of(user("aaaa"), assistantCalling(), toolResult(), user("bbbb"), user("cccc"));
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 14);

    // 关键不变量：绝不能留下"请求了工具却没有结果"的助手消息
    for (ModelMessage message : result.getMessages()) {
      assertFalse(
          message instanceof AssistantModelMessage,
          "被裁掉工具结果时，请求该调用的助手消息也必须一起消失");
      assertFalse(message instanceof ToolModelMessage);
    }
    assertEquals(2, result.getMessages().size());
  }

  @Test
  void singleGroupContainingToolCallIsNeverSplit() {
    // 整个历史就是一个工具调用组，且超预算——必须整组保留
    List<ModelMessage> messages = List.of(assistantCalling(), toolResult());
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 1);
    assertEquals(2, result.getMessages().size());
    assertInstanceOf(AssistantModelMessage.class, result.getMessages().get(0));
    assertInstanceOf(ToolModelMessage.class, result.getMessages().get(1));
    assertFalse(result.wasTrimmed());
  }

  @Test
  void groupsToolResultsFollowingAssistantWithToolCalls() {
    List<ModelMessage> messages = List.of(user("aaaa"), assistantCalling(), toolResult());
    List<List<ModelMessage>> groups = ContextTrimmer.groupByToolCall(messages);

    assertEquals(2, groups.size());
    assertEquals(1, groups.get(0).size());
    assertEquals(2, groups.get(1).size());
    assertInstanceOf(AssistantModelMessage.class, groups.get(1).get(0));
    assertInstanceOf(ToolModelMessage.class, groups.get(1).get(1));
  }

  @Test
  void assistantWithoutToolCallsFormsItsOwnGroup() {
    List<ModelMessage> messages = List.of(new AssistantModelMessage("aaaa"), toolResult());
    List<List<ModelMessage>> groups = ContextTrimmer.groupByToolCall(messages);

    // 没请求工具，后面的工具结果不属于它（属于更早的调用）
    assertEquals(2, groups.size());
    assertEquals(1, groups.get(0).size());
    assertEquals(1, groups.get(1).size());
  }

  @Test
  void leadingToolResultWithoutAssistantFormsOwnGroup() {
    List<ModelMessage> messages = List.of(toolResult(), user("aaaa"));
    List<List<ModelMessage>> groups = ContextTrimmer.groupByToolCall(messages);
    assertEquals(2, groups.size());
    assertEquals(1, groups.get(0).size());
  }

  @Test
  void groupingPreservesOrderAndCoversAllMessages() {
    List<ModelMessage> messages =
        List.of(
            user("aaaa"),
            assistantCalling(),
            toolResult(),
            toolResult(),
            user("bbbb"),
            assistantCalling(),
            toolResult());
    List<List<ModelMessage>> groups = ContextTrimmer.groupByToolCall(messages);

    assertEquals(4, groups.size());
    assertEquals(1, groups.get(0).size());
    assertEquals(3, groups.get(1).size(), "一次调用后的多个结果都并入同组");
    assertEquals(1, groups.get(2).size());
    assertEquals(2, groups.get(3).size());

    int total = 0;
    for (List<ModelMessage> group : groups) {
      total += group.size();
    }
    assertEquals(messages.size(), total, "分组必须覆盖全部消息且不重不漏");
  }

  @Test
  void resultExposesOriginalAndKeptTokenCounts() {
    List<ModelMessage> messages = List.of(user("aaaa"), user("bbbb"), user("cccc"));
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 12);
    assertEquals(15, result.getOriginalTokens());
    assertEquals(10, result.getKeptTokens());
    assertEquals(1, result.getDroppedMessages());
    assertTrue(result.wasTrimmed());
  }

  @Test
  void untrimmedResultIsACopyNotTheSameList() {
    List<ModelMessage> messages = new java.util.ArrayList<>(List.of(user("aaaa")));
    ContextTrimmer.Result result = ContextTrimmer.trim(messages, 100);
    assertEquals(messages.size(), result.getMessages().size());
    result.getMessages().clear();
    assertEquals(1, messages.size(), "裁剪结果不应把调用方传入的列表暴露出去");
  }
}
