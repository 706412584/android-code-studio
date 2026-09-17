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
import com.tom.rv2ide.ai.protocol.SystemModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 运行期裁剪的两条底线：系统提示词与本次用户请求永不消失，工具调用不与其结果拆散。
 *
 * <p>token 数按 {@link TokenEstimator} 手算：4 字符 ASCII 用户消息 = 5 tokens。
 */
final class RunContextManagerTest {

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

  private static RunContextManager manager(int contextSize, int overhead) {
    return new RunContextManager(new TokenUsageTracker(contextSize), overhead);
  }

  /** 组装 {@code [系统提示词, 历史..., 本次请求]} 并返回本次请求的下标。 */
  private static List<ModelMessage> messages(List<ModelMessage> history, ModelMessage request) {
    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new SystemModelMessage("sys"));
    messages.addAll(history);
    messages.add(request);
    return messages;
  }

  @Test
  void unconfiguredWindowKeepsEverything() {
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb"), user("cccc")), user("dddd"));
    List<ModelMessage> fitted = manager(0, 0).fit(messages, 4);
    assertEquals(5, fitted.size());
  }

  @Test
  void keepsSystemPromptAndCurrentRequestEvenWhenOverBudget() {
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb"), user("cccc")), user("dddd"));

    // 窗口极小：历史必须被丢光，但系统提示词与本次请求必须留下
    List<ModelMessage> fitted = manager(100, 0).fit(messages, 4);

    assertInstanceOf(SystemModelMessage.class, fitted.get(0));
    assertEquals("sys", fitted.get(0).getContent());
    assertEquals("dddd", fitted.get(fitted.size() - 1).getContent(), "本次请求不能被裁掉");
    assertTrue(fitted.size() >= 2, "系统提示词与本次请求之间至少要有系统提示词本身");
  }

  @Test
  void dropsOldestHistoryFirst() {
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb"), user("cccc")), user("dddd"));
    // 本次请求下标 = 1(系统) + 3(历史) = 4
    int requestIndex = 4;

    // 窗口 100 - 预留 8192 为负 → 预算归零 → 历史全丢，只剩系统提示词与请求
    List<ModelMessage> fitted = manager(100, 0).fit(messages, requestIndex);
    assertEquals(2, fitted.size());

    // 窗口足够大时历史原样保留
    List<ModelMessage> roomy = manager(1_000_000, 0).fit(messages, requestIndex);
    assertEquals(5, roomy.size());
  }

  @Test
  void budgetSubtractsOverheadAndOutputReserve() {
    // 20000 - 1000(开销) - 8192(预留) = 10808，够装 3 条 5-token 历史消息
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb"), user("cccc")), user("dddd"));
    List<ModelMessage> fitted = manager(20_000, 1_000).fit(messages, 4);
    assertEquals(5, fitted.size(), "10808 的预算足以装下 15 tokens 的历史");
  }

  @Test
  void zeroBudgetDoesNotMeanUnlimited() {
    // 这是最容易搞反的一处：ContextTrimmer 把预算 <= 0 解释为"不限"，
    // 因此预算归零时必须绕开它，否则最该裁剪的场景反而不裁。
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb")), user("cccc"));
    List<ModelMessage> fitted = manager(8_000, 0).fit(messages, 3);

    assertEquals(2, fitted.size(), "预算归零时历史必须被丢弃，而非因 <=0 被当作不限");
    assertInstanceOf(SystemModelMessage.class, fitted.get(0));
    assertEquals("cccc", fitted.get(1).getContent());
  }

  @Test
  void keepsToolCallAndResultTogether() {
    List<ModelMessage> history =
        List.of(user("aaaa"), assistantCalling(), toolResult(), user("bbbb"));
    List<ModelMessage> messages = messages(history, user("cccc"));

    // 预算充足时调用与结果必须同进同出
    List<ModelMessage> fitted = manager(1_000_000, 0).fit(messages, 5);
    boolean sawCall = false;
    boolean sawResult = false;
    for (ModelMessage message : fitted) {
      if (message instanceof AssistantModelMessage && !message.getToolCalls().isEmpty()) {
        sawCall = true;
      }
      if (message instanceof ToolModelMessage) {
        sawResult = true;
      }
    }
    assertEquals(sawCall, sawResult, "工具调用与其结果不得分离");
  }

  @Test
  void dropsLeadingOrphanToolResult() {
    // 磁盘上的旧历史可能以孤立工具结果开头；以它开头发请求会被服务商判 400
    List<ModelMessage> messages = messages(List.of(toolResult(), user("aaaa")), user("bbbb"));
    List<ModelMessage> fitted = manager(1_000_000, 0).fit(messages, 3);

    for (ModelMessage message : fitted) {
      assertFalse(message instanceof ToolModelMessage, "开头的孤立工具结果必须被丢弃");
    }
  }

  @Test
  void resultIsACopyNotTheSameList() {
    List<ModelMessage> messages =
        messages(List.of(user("aaaa")), user("bbbb"));
    List<ModelMessage> fitted = manager(0, 0).fit(messages, 2);
    fitted.clear();
    assertEquals(3, messages.size(), "裁剪结果不应把调用方传入的列表暴露出去");
  }

  @Test
  void requestIndexOutOfRangeIsClamped() {
    // 循环末尾会传入 messages.size()，此时"本次请求"已不在列表里；
    // 夹取到末位可避免 IndexOutOfBounds——裁剪永远不该让对话崩掉。
    List<ModelMessage> messages =
        messages(List.of(user("aaaa")), user("bbbb"));
    List<ModelMessage> fitted = manager(1_000_000, 0).fit(messages, 99);
    assertEquals(3, fitted.size());
  }

  @Test
  void emptyInputYieldsEmptyResult() {
    assertTrue(manager(1_000, 0).fit(null, 0).isEmpty());
    assertTrue(manager(1_000, 0).fit(List.of(), 0).isEmpty());
  }

  @Test
  void singleMessageListIsReturnedAsIs() {
    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new SystemModelMessage("sys"));
    assertEquals(1, manager(100, 0).fit(messages, 0).size());
  }
}
