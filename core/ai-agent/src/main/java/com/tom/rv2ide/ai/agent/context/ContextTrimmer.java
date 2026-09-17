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

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按 token 预算裁剪历史消息。
 *
 * <p><b>核心约束：工具调用与其结果不可拆散</b>。若保留了一条请求工具调用的助手消息，
 * 却丢掉了对应的工具结果，模型会看到"自己请求了工具但没有结果"——多数服务商会直接
 * 报 400（tool_call_id 无对应结果），或被模型误判为工具失败而重复调用。
 * 因此裁剪以「工具调用组」为最小单位：一条助手消息及其全部结果作为整体保留或整体丢弃。
 *
 * <p><b>为什么不直接删中间的消息</b>：对话有因果依赖。删掉中间的问答会让后续
 * 「它」"那个"之类指代失去所指。策略是从**最旧**开始整组丢弃，保留最近上下文——
 * 最近的对话与当前任务最相关。
 *
 * <p><b>裁剪与压缩的分工</b>：裁剪是"丢弃"，不产生信息；压缩（{@link ContextCompactor}）
 * 是"摘要"，保留要点。先裁剪（便宜、无网络），不够再压缩。
 */
public final class ContextTrimmer {

  /**
   * 至少保留的最近消息数。即使预算极小，也不能把最近对话丢光。
   *
   * <p>公开是为了让压缩边界的选择（{@code ConversationCompaction}）与裁剪遵循同一条底线——
   * 否则压缩可能把裁剪本来会保留的消息也一并吞掉。
   */
  public static final int MIN_KEEP_MESSAGES = 2;

  private ContextTrimmer() {}

  /**
   * 裁剪消息列表以适配 token 预算。
   *
   * @param messages 完整历史（按时间序，不含系统提示词）
   * @param budgetTokens 可用于历史消息的 token 预算
   * @return 裁剪后的消息；若原本就在预算内则原样返回
   */
  public static Result trim(List<ModelMessage> messages, int budgetTokens) {
    if (messages == null || messages.isEmpty()) {
      return new Result(Collections.emptyList(), 0, 0, 0);
    }
    int originalTokens = TokenEstimator.estimate(messages);
    if (budgetTokens <= 0 || originalTokens <= budgetTokens) {
      return new Result(new ArrayList<>(messages), originalTokens, originalTokens, 0);
    }

    List<List<ModelMessage>> groups = groupByToolCall(messages);
    List<List<ModelMessage>> kept = new ArrayList<>();
    int keptTokens = 0;
    int keptMessageCount = 0;

    // 从最新往回保留，直到预算用尽。最新的一组无条件保留：哪怕它本身就超预算，
    // 丢掉它等于没有上下文可用（而保留它至少模型能看到当前问题）。
    for (int i = groups.size() - 1; i >= 0; i--) {
      List<ModelMessage> group = groups.get(i);
      int groupTokens = TokenEstimator.estimate(group);
      boolean isNewestGroup = kept.isEmpty();
      boolean withinBudget = keptTokens + groupTokens <= budgetTokens;

      if (!isNewestGroup && !withinBudget && keptMessageCount >= MIN_KEEP_MESSAGES) {
        break;
      }
      kept.add(0, group);
      keptTokens += groupTokens;
      keptMessageCount += group.size();
    }

    List<ModelMessage> result = new ArrayList<>();
    for (List<ModelMessage> group : kept) {
      result.addAll(group);
    }
    int droppedMessages = messages.size() - result.size();
    return new Result(result, originalTokens, keptTokens, droppedMessages);
  }

  /**
   * 按工具调用分组：一条请求了工具的助手消息，与其后的工具结果消息归为一组。
   *
   * <p>不请求工具的普通消息各自成组。这样裁剪时不会把调用与结果拆开。
   */
  static List<List<ModelMessage>> groupByToolCall(List<ModelMessage> messages) {
    List<List<ModelMessage>> groups = new ArrayList<>();
    int index = 0;
    while (index < messages.size()) {
      ModelMessage message = messages.get(index);
      List<ModelMessage> group = new ArrayList<>();
      group.add(message);
      index++;

      if (message instanceof AssistantModelMessage
          && !((AssistantModelMessage) message).getToolCalls().isEmpty()) {
        // 把紧随其后的工具结果全部并入本组
        while (index < messages.size() && messages.get(index) instanceof ToolModelMessage) {
          group.add(messages.get(index));
          index++;
        }
      }
      groups.add(group);
    }
    return groups;
  }

  /** 裁剪结果。 */
  public static final class Result {
    private final List<ModelMessage> messages;
    private final int originalTokens;
    private final int keptTokens;
    private final int droppedMessages;

    Result(List<ModelMessage> messages, int originalTokens, int keptTokens, int droppedMessages) {
      this.messages = messages;
      this.originalTokens = originalTokens;
      this.keptTokens = keptTokens;
      this.droppedMessages = droppedMessages;
    }

    public List<ModelMessage> getMessages() {
      return messages;
    }

    public int getOriginalTokens() {
      return originalTokens;
    }

    public int getKeptTokens() {
      return keptTokens;
    }

    /** 被丢弃的消息条数；大于 0 表示发生了裁剪。 */
    public int getDroppedMessages() {
      return droppedMessages;
    }

    public boolean wasTrimmed() {
      return droppedMessages > 0;
    }
  }
}
