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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 基于文件系统的会话存储：{@code <根目录>/<会话id>.jsonl}。
 *
 * <p><b>为什么在纯 Java 模块而非 app 层</b>：本类只用 {@link File}，不需要 Android API。
 * 目录由调用方注入（app 层传 {@code filesDir}），因此可 JVM 单测——
 * 这是本类能覆盖目录穿越、损坏文件跳过等边界的前提。
 *
 * <p>一会话一文件：删除、列出、并发写互不干扰；内容为 append-only JSONL。
 *
 * <p><b>为什么不用 SharedPreferences</b>：它要求整份数据在内存中、写入为全量覆盖，
 * 长会话既占内存又每次重写全文，与 append-only 相悖。
 */
public final class FileConversationStore implements ConversationStore {

  private static final String EXTENSION = ".jsonl";

  private final File directory;

  public FileConversationStore(File directory) {
    this.directory = directory;
  }

  public File getDirectory() {
    return directory;
  }

  @Override
  public List<ConversationSummary> list() throws IOException {
    List<ConversationSummary> summaries = new ArrayList<>();
    File[] files = directory.listFiles();
    if (files == null) {
      return summaries;
    }
    for (File file : files) {
      String name = file.getName();
      if (!file.isFile() || !name.endsWith(EXTENSION)) {
        continue;
      }
      String id = name.substring(0, name.length() - EXTENSION.length());
      try {
        summaries.add(ConversationReducer.summarize(id, new ConversationLog(file).readAll()));
      } catch (IOException e) {
        // 单个会话损坏不应让整个列表不可用——跳过它，其余照常列出。
        continue;
      }
    }
    summaries.sort(Comparator.comparingLong(ConversationSummary::getModifiedAt).reversed());
    return summaries;
  }

  @Override
  public ConversationSummary create(String title, String cwd, String model, String permissionMode)
      throws IOException {
    String id = newId();
    long now = System.currentTimeMillis();
    ConversationLog log = logFor(id);
    log.append(SessionMetaEntry.create(now, cwd, model, permissionMode));
    if (title != null && !title.trim().isEmpty()) {
      log.append(TitleEntry.customTitle(null, now, title.trim()));
    }
    return ConversationReducer.summarize(id, log.readAll());
  }

  @Override
  public List<ConversationLog.EntryLocation> read(String conversationId) throws IOException {
    return logFor(conversationId).readAll();
  }

  @Override
  public long append(String conversationId, ConversationEntry entry) throws IOException {
    return logFor(conversationId).append(entry);
  }

  @Override
  public void rename(String conversationId, String title) throws IOException {
    // 追加而非回写：保持 append-only，崩溃不会损坏既有历史。
    logFor(conversationId)
        .append(TitleEntry.customTitle(null, System.currentTimeMillis(), title));
  }

  @Override
  public void delete(String conversationId) throws IOException {
    ConversationLog log = logFor(conversationId);
    if (!log.delete()) {
      throw new IOException("无法删除会话文件: " + log.getFile());
    }
  }

  @Override
  public boolean exists(String conversationId) {
    return isValidId(conversationId) && logFor(conversationId).exists();
  }

  private ConversationLog logFor(String conversationId) {
    if (!isValidId(conversationId)) {
      // 会话 id 直接参与文件名，必须防目录穿越——否则 delete/rename 可被诱导操作任意文件。
      throw new IllegalArgumentException("非法会话 id: " + conversationId);
    }
    return new ConversationLog(new File(directory, conversationId + EXTENSION));
  }

  /** 会话 id 由本类生成（UUID），只允许十六进制与短横线。 */
  private static boolean isValidId(String id) {
    if (id == null || id.isEmpty() || id.length() > 64) {
      return false;
    }
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      boolean allowed =
          (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '-';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }
}
