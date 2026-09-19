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
  private final String cwd;
  private final long createdAt;
  private final long modifiedAt;
  private final int messageCount;

  public ConversationSummary(
      String id, String title, String cwd, long createdAt, long modifiedAt, int messageCount) {
    this.id = id;
    this.title = title == null || title.isEmpty() ? UNTITLED : title;
    this.cwd = cwd == null ? "" : cwd;
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

  /**
   * 本会话绑定的工作区根目录（绝对路径），来自会话首条 {@link SessionMetaEntry}。
   *
   * <p><b>为什么摘要要带 cwd</b>：会话列表是全局的，而工作区是按项目隔离的。
   * 列表项要能标出「这条会话属于哪个项目」，上层也要能判断「这条会话是不是
   * 当前项目的」——两者都需要 cwd。此前摘要不含它，上层只能逐个读会话文件反查，
   * 那是 O(n) 次文件读，而 cwd 本来就在 fold 时经手的 meta 条目里。
   *
   * @return 工作区绝对路径；无 meta 条目或未记录时为空串（调用方按「不匹配」处理）
   */
  public String getCwd() {
    return cwd;
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
