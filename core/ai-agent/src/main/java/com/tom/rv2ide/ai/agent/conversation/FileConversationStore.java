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

package com.tom.rv2ide.ai.agent.conversation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 *
 * <p><b>并发保护</b>：{@link #append} 与 {@link #deleteEntries} 共用一把
 * {@link ReentrantReadWriteLock}——append 用读锁（多线程可并发追加），
 * deleteEntries 用写锁（独占，重写期间不允许任何 append）。删除是
 * append-only 哲学的唯一破例，必须保证重写期间没有并发写入被覆盖丢失。
 */
public final class FileConversationStore implements ConversationStore {

  private static final String EXTENSION = ".jsonl";

  private final File directory;

  /** 追加（读锁）与删除（写锁）的互斥。 */
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

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
    return createWithMeta(
        title,
        SessionMetaEntry.create(System.currentTimeMillis(), cwd, model, permissionMode));
  }

  @Override
  public ConversationSummary createSubAgent(
      String title, String cwd, String model, String permissionMode,
      String parentId, String subagentType)
      throws IOException {
    return createWithMeta(
        title,
        SessionMetaEntry.createSubAgent(
            System.currentTimeMillis(), cwd, model, permissionMode, parentId, subagentType));
  }

  /** 建文件 + 写首条 meta + 可选标题，返回折叠出的摘要。 */
  private ConversationSummary createWithMeta(String title, SessionMetaEntry meta)
      throws IOException {
    String id = newId();
    long now = meta.getTimestamp();
    ConversationLog log = logFor(id);
    log.append(meta);
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
  public ConversationLog.HistoryPage readPage(String conversationId, String beforeUuid)
      throws IOException {
    lock.readLock().lock();
    try {
      return logFor(conversationId).readPage(beforeUuid);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long append(String conversationId, ConversationEntry entry) throws IOException {
    lock.readLock().lock();
    try {
      return logFor(conversationId).append(entry);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void rename(String conversationId, String title) throws IOException {
    // 追加而非回写：保持 append-only，崩溃不会损坏既有历史。
    lock.readLock().lock();
    try {
      logFor(conversationId)
          .append(TitleEntry.customTitle(null, System.currentTimeMillis(), title));
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void delete(String conversationId) throws IOException {
    lock.writeLock().lock();
    try {
      ConversationLog log = logFor(conversationId);
      if (!log.delete()) {
        throw new IOException("无法删除会话文件: " + log.getFile());
      }
      // 连带清理删除备份与临时文件——不留可恢复的明文。
      new File(directory, conversationId + ".jsonl.bak").delete();
      new File(directory, conversationId + ".jsonl.tmp").delete();
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public boolean exists(String conversationId) {
    return isValidId(conversationId) && logFor(conversationId).exists();
  }

  @Override
  public ConversationSummary fork(String conversationId, int upToOrdinal) throws IOException {
    // 与 deleteEntries 同级写锁：复制前缀期间不允许 append 改变源文件。
    lock.writeLock().lock();
    try {
      ConversationLog source = logFor(conversationId);
      List<ConversationLog.EntryLocation> entries = source.readAll();
      List<ConversationLog.EntryLocation> prefix = new ArrayList<>();
      for (ConversationLog.EntryLocation location : entries) {
        if (location.getOrdinal() <= upToOrdinal) {
          prefix.add(location);
        }
      }
      if (prefix.isEmpty()) {
        throw new IOException("分叉点之前没有可复制的条目: " + upToOrdinal);
      }
      String sourceTitle = ConversationReducer.summarize(conversationId, entries).getTitle();

      String newId = newId();
      ConversationLog target = logFor(newId);
      for (ConversationLog.EntryLocation location : prefix) {
        target.append(location.getEntry());
      }
      // 外置截图目录按新会话 id 复制：条目里的 imageRef 相对「<会话文件>.images/」，
      // 不复制的话分叉后的历史截图全部读不到（getImageBase64 懒加载 404）。
      copyDirectory(
          new File(directory, conversationId + EXTENSION + ".images"),
          new File(directory, newId + EXTENSION + ".images"));
      // 标题走追加而非回写：与 rename 同一 append-only 语义，同时保留源标题供辨认。
      String forkTitle =
          sourceTitle == null || sourceTitle.trim().isEmpty()
              ? "分叉会话"
              : sourceTitle.trim() + "（分叉）";
      target.append(TitleEntry.customTitle(null, System.currentTimeMillis(), forkTitle));
      return ConversationReducer.summarize(newId, target.readAll());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** 整目录复制（外置截图用）；源目录不存在时跳过。 */
  private static void copyDirectory(File source, File dest) throws IOException {
    if (!source.isDirectory()) {
      return;
    }
    if (!dest.isDirectory() && !dest.mkdirs()) {
      throw new IOException("无法创建目录: " + dest);
    }
    File[] children = source.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      File target = new File(dest, child.getName());
      if (child.isDirectory()) {
        copyDirectory(child, target);
      } else {
        copyFile(child, target);
      }
    }
  }

  @Override
  public int deleteEntries(String conversationId, List<Integer> ordinals) throws IOException {
    if (ordinals == null || ordinals.isEmpty()) {
      return 0;
    }
    lock.writeLock().lock();
    try {
      ConversationLog log = logFor(conversationId);
      File file = log.getFile();
      if (!file.exists()) {
        return 0;
      }
      List<ConversationLog.EntryLocation> entries = log.readAll();
      java.util.Set<Integer> wanted = new java.util.HashSet<>(ordinals);

      // 计算每个 ordinal 之前被删的行数（用于 CompactionEntry.upToOrdinal 重编号）。
      // 被删行本身不参与计数——它已经不是有效参照物了。
      java.util.Set<Integer> deletedSet = new java.util.HashSet<>();
      for (ConversationLog.EntryLocation loc : entries) {
        if (wanted.contains(loc.getOrdinal())) {
          deletedSet.add(loc.getOrdinal());
        }
      }

      // 重写文件：逐行保留未删条目；遇到 CompactionEntry 时调整 upToOrdinal
      // （减去该边界之前被删的行数），保持 fold 的压缩语义正确。
      File backup = new File(directory, conversationId + ".jsonl.bak");
      File temp = new File(directory, conversationId + ".jsonl.tmp");
      copyFile(file, backup);

      int removed = 0;
      try (java.io.RandomAccessFile source = new java.io.RandomAccessFile(file, "r");
           java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
        for (ConversationLog.EntryLocation loc : entries) {
          if (deletedSet.contains(loc.getOrdinal())) {
            removed++;
            continue; // 跳过被删行
          }
          ConversationEntry entry = loc.getEntry();
          if (entry instanceof CompactionEntry) {
            CompactionEntry compaction = (CompactionEntry) entry;
            int shift = 0;
            for (int deletedOrdinal : deletedSet) {
              // upToOrdinal 是「被压缩覆盖的条目序号上界（含）」，
              // 因此 ordinal <= upToOrdinal 的删除都会影响边界位置。
              if (deletedOrdinal <= compaction.getUpToOrdinal()) {
                shift++;
              }
            }
            if (shift > 0) {
              // 边界之前删了 shift 行，upToOrdinal 前移。
              // 不写回磁盘（append-only），而是重写这条的内容。
              CompactionEntry adjusted =
                  new CompactionEntry(
                      compaction.getUuid(),
                      compaction.getParentUuid(),
                      compaction.getTimestamp(),
                      compaction.getSummary(),
                      Math.max(0, compaction.getUpToOrdinal() - shift));
              // 序列化调整后的条目。
              try {
                String json = ConversationCodec.toLine(adjusted) + "\n";
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
              } catch (org.json.JSONException e) {
                throw new IOException("压缩条目序列化失败", e);
              }
              continue;
            }
          }
          // 未删条目原样拷贝字节。
          source.seek(loc.getByteStart());
          byte[] lineBytes = new byte[loc.getByteLength()];
          source.readFully(lineBytes);
          out.write(lineBytes);
        }
        out.getFD().sync();
      }
      if (removed == 0) {
        temp.delete();
        return 0;
      }
      // Windows 上 File.renameTo 要求目标不存在；Files.move + REPLACE_EXISTING 才是
      // 跨平台的「原子覆盖」语义。ATOMIC_MOVE 尽力而为：文件系统不支持时回退普通 move。
      try {
        java.nio.file.Files.move(
            temp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        java.nio.file.Files.move(
            temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return removed;
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static void copyFile(File source, File dest) throws IOException {
    try (java.io.FileInputStream in = new java.io.FileInputStream(source);
         java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        out.write(buffer, 0, read);
      }
      out.getFD().sync();
    }
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
