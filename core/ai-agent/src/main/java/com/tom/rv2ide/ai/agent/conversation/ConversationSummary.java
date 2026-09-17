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

/**
 * 会话摘要——列表页需要的全部信息。
 *
 * <p>由 {@link ConversationReducer} 折叠条目派生，不单独持久化为规范列。好处是
 * 改标题、加消息都不需要 UPDATE 任何索引；代价是列表页要读一遍文件，
 * 由 {@code ConversationStore} 的实现决定是否缓存。
 */
public final class ConversationSummary {

  /** 无标题时的占位。 */
  public static final String UNTITLED = "未命名会话";

  private final String id;
  private final String title;
  private final long createdAt;
  private final long modifiedAt;
  private final int messageCount;

  public ConversationSummary(
      String id, String title, long createdAt, long modifiedAt, int messageCount) {
    this.id = id;
    this.title = title == null || title.isEmpty() ? UNTITLED : title;
    this.createdAt = createdAt;
    this.modifiedAt = modifiedAt;
    this.messageCount = messageCount;
  }

  public String getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public long getCreatedAt() {
    return createdAt;
  }

  public long getModifiedAt() {
    return modifiedAt;
  }

  /** 用户可见的消息数（不含 meta 条目）。 */
  public int getMessageCount() {
    return messageCount;
  }
}
