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

import java.util.List;

/**
 * 把条目序列折叠为摘要与历史消息。
 *
 * <p><b>为什么摘要要 fold 出来而不是存列</b>：append-only 日志里"当前标题"取决于
 * 最后一条标题条目。若把标题存成规范列，重命名就得 UPDATE——而回写会让崩溃可能损坏会话。
 * fold 的代价是列表页要读一遍文件，换来的是历史永不损坏、且标题变更可追溯。
 *
 * <p>折叠同时负责压缩语义：遇到 {@link CompactionEntry} 后，其
 * {@code upToOrdinal} 之前的消息不再进入历史（P0-2 实现，本类已按此语义处理）。
 */
public final class ConversationReducer {

  private ConversationReducer() {}

  /**
   * 折叠出摘要。
   *
   * <p>标题优先级：用户重命名 > 模型标题 > 首条用户消息 > 占位。
   * 用户显式意图永远压过自动生成的内容。
   */
  public static ConversationSummary summarize(
      String conversationId, List<ConversationLog.EntryLocation> entries) {
    String customTitle = "";
    String aiTitle = "";
    String firstUserContent = "";
    long createdAt = 0L;
    long modifiedAt = 0L;
    int messageCount = 0;

    for (ConversationLog.EntryLocation location : entries) {
      ConversationEntry entry = location.getEntry();
      if (createdAt == 0L) {
        createdAt = entry.getTimestamp();
      }
      if (entry.getTimestamp() > modifiedAt) {
        modifiedAt = entry.getTimestamp();
      }

      if (entry instanceof TitleEntry) {
        TitleEntry title = (TitleEntry) entry;
        if (title.getType() == ConversationEntry.Type.CUSTOM_TITLE) {
          customTitle = title.getTitle();
        } else {
          aiTitle = title.getTitle();
        }
      } else if (entry instanceof UserMessageEntry) {
        UserMessageEntry user = (UserMessageEntry) entry;
        if (!user.isMeta()) {
          messageCount++;
          if (firstUserContent.isEmpty()) {
            firstUserContent = user.getContent();
          }
        }
      } else if (entry instanceof AssistantMessageEntry) {
        messageCount++;
      }
    }

    String title = firstNonEmpty(customTitle, aiTitle, deriveFromUserMessage(firstUserContent));
    return new ConversationSummary(conversationId, title, createdAt, modifiedAt, messageCount);
  }

  private static String firstNonEmpty(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.trim().isEmpty()) {
        return candidate.trim();
      }
    }
    return ConversationSummary.UNTITLED;
  }

  /**
   * 从首条用户消息派生标题。
   *
   * <p>截断到单行且限长——列表项是单行显示，长消息会把标题撑爆。
   */
  private static String deriveFromUserMessage(String content) {
    if (content == null || content.trim().isEmpty()) {
      return "";
    }
    String singleLine = content.trim().replaceAll("\\s+", " ");
    int limit = 40;
    return singleLine.length() > limit ? singleLine.substring(0, limit) + "…" : singleLine;
  }
}
