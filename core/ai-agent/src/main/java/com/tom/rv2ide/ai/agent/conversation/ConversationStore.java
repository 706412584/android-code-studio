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

import java.io.IOException;
import java.util.List;

/**
 * 会话存储的窄接口。
 *
 * <p><b>为什么是接口</b>：与 {@code ShellBackend} / {@code DiffStore} / {@code ToolSettingsPort}
 * 同模式——接口在纯 Java 模块、实现在 app 层。这样会话逻辑可 JVM 单测（现有 75 项测试的基础），
 * 且将来若 P0-8 需要全文检索而引入派生索引，只换实现、接口不变。
 *
 * <p>实现须保证：只追加、不改写历史。所有"变更"（重命名）都是新条目。
 */
public interface ConversationStore {

  /** 列出全部会话摘要，按最近修改倒序。 */
  List<ConversationSummary> list() throws IOException;

  /** 新建会话并写入 {@link SessionMetaEntry}。 */
  ConversationSummary create(String title, String cwd, String model, String permissionMode)
      throws IOException;

  /** 读取一个会话的全部条目。 */
  List<ConversationLog.EntryLocation> read(String conversationId) throws IOException;

  /**
   * 追加条目。
   *
   * @return 本条条目的起始字节偏移
   */
  long append(String conversationId, ConversationEntry entry) throws IOException;

  /**
   * 重命名。实现必须表达为追加 {@link TitleEntry}，不得回写历史。
   */
  void rename(String conversationId, String title) throws IOException;

  /** 删除会话及其日志。 */
  void delete(String conversationId) throws IOException;

  /** @return 会话是否存在。 */
  boolean exists(String conversationId);
}
