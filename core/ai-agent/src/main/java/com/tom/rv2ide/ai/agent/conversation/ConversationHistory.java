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

package com.tom.rv2ide.ai.agent.conversation;

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * 把会话条目还原为可续接的模型消息列表。
 *
 * <p><b>为什么需要它</b>：{@code AgentSession.run()} 每次都以空消息列表开始，因此
 * 重启后无法续接对话。把历史条目折叠成 {@link ModelMessage} 列表后作为起点传入，
 * 循环就能在既有上下文上继续。
 *
 * <p><b>压缩语义已在此实现</b>：遇到 {@link CompactionEntry} 时，其 {@code upToOrdinal}
 * 之前的条目不再进入历史，改用该条目的摘要作为一条用户消息。P0-2 只需负责产生
 * 压缩条目，读取侧无需改动。
 *
 * <p><b>为什么跳过 meta 与标题条目</b>：它们不参与模型对话，只影响 UI 与摘要。
 * 把它们喂给模型会浪费 token 且可能干扰理解。
 */
public final class ConversationHistory {

  private ConversationHistory() {}

  /**
   * 折叠条目为模型消息。
   *
   * @param entries 会话全部条目（按序）
   * @return 可直接作为 {@code AgentSession} 起点的消息列表；无有效条目时为空列表
   */
  public static List<ModelMessage> fold(List<ConversationLog.EntryLocation> entries) {
    List<ModelMessage> messages = new ArrayList<>();
    if (entries == null) {
      return messages;
    }

    // 第一趟：找出被压缩覆盖的最大序号，并按序收集摘要。
    // 必须与追加分开做——压缩条目是**回溯性**标记，它出现在被覆盖条目之后，
    // 单趟遍历时那些条目早已被追加，无法再撤回。
    int compactedUpTo = -1;
    List<String> summaries = new ArrayList<>();
    for (ConversationLog.EntryLocation location : entries) {
      if (location.getEntry() instanceof CompactionEntry) {
        CompactionEntry compaction = (CompactionEntry) location.getEntry();
        if (compaction.getUpToOrdinal() > compactedUpTo) {
          compactedUpTo = compaction.getUpToOrdinal();
        }
        // 摘要无条件保留：后一次压缩可能建立在前一次之上，丢弃会丢失信息。
        if (!compaction.getSummary().isEmpty()) {
          summaries.add(compaction.getSummary());
        }
      }
    }

    // 第二趟：只追加未被压缩覆盖的对话条目。
    for (ConversationLog.EntryLocation location : entries) {
      ConversationEntry entry = location.getEntry();
      if (entry instanceof CompactionEntry) {
        continue;
      }
      if (location.getOrdinal() <= compactedUpTo) {
        continue;
      }
      append(entry, messages);
    }

    // 摘要放在最前：它是被压缩区间的替身，必须出现在后续对话之前。
    if (!summaries.isEmpty()) {
      messages.add(0, new UserModelMessage(String.join("\n\n", summaries)));
    }
    return messages;
  }

  private static void append(ConversationEntry entry, List<ModelMessage> messages) {
    if (entry instanceof UserMessageEntry) {
      UserMessageEntry user = (UserMessageEntry) entry;
      // meta 条目是系统注入，不进入模型对话
      if (!user.isMeta()) {
        messages.add(new UserModelMessage(user.getContent()));
      }
    } else if (entry instanceof AssistantMessageEntry) {
      AssistantMessageEntry assistant = (AssistantMessageEntry) entry;
      messages.add(
          new AssistantModelMessage(
              assistant.getContent(), assistant.getReasoningContent(), assistant.getToolCalls()));
    } else if (entry instanceof ToolResultEntry) {
      ToolResultEntry result = (ToolResultEntry) entry;
      messages.add(
          new ToolModelMessage(
              result.getContent(),
              result.getToolCallId(),
              result.getToolName(),
              result.isError()));
    }
    // SessionMetaEntry / TitleEntry 不参与模型对话
  }
}
