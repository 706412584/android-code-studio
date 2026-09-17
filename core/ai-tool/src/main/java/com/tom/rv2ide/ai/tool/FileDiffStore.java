/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * 文件持久化的 {@link DiffStore}：改动记录跨进程存活，因此**重启后仍能回滚**。
 *
 * <p>这是 {@link InMemoryDiffStore} 的替代实现。后者的记录只活在内存里，
 * 用户关掉应用再打开就找不到之前的改动——「重启应用后仍可撤销」这条验收要求
 * 只有持久化实现才能满足。
 *
 * <p><b>格式：append-only JSONL。</b>沿用会话日志的取舍——文件只追加、永不原地改写，
 * 因此崩溃最多损坏最后一行，不会破坏已写入的历史。三种事件：
 * <ul>
 *   <li>{@code d} 一条改动记录（含前后内容）
 *   <li>{@code r} 标记某条已回滚
 *   <li>{@code v} 设置某条审查状态
 * </ul>
 * 状态变化追加新事件而非回写原记录，回放时按顺序应用即可得到最终状态。
 *
 * <p><b>启动时全量回放到内存</b>：之后所有查询都是内存操作。diff 文件远小于会话日志
 * （一个改动一条记录，且会被裁剪），全量回放的代价可以接受，换来查询路径无需碰磁盘。
 *
 * <p><b>为何不用 {@code ConversationLog} 的字节偏移与指纹判变</b>：那套机制是为
 * 「数万条目的长会话、需要增量索引」准备的。diff 记录数量在裁剪下天然有界，
 * 启动时全量读一遍即可，额外机制只会增加出错面。
 *
 * <p>实现线程安全（工具可能并发执行，回滚也可能与新的写入并发）。
 */
public final class FileDiffStore implements DiffStore {

  /** 事件类型：改动记录。 */
  private static final String EVENT_DIFF = "d";

  /** 事件类型：标记已回滚。 */
  private static final String EVENT_REVERT = "r";

  /** 事件类型：设置审查状态。 */
  private static final String EVENT_REVIEW = "v";

  private static final String FIELD_EVENT = "t";
  private static final String FIELD_ID = "id";
  private static final String FIELD_PATH = "p";
  private static final String FIELD_OLD = "old";
  private static final String FIELD_NEW = "new";
  private static final String FIELD_EXISTED = "ex";
  private static final String FIELD_TIMESTAMP = "ts";
  private static final String FIELD_STATE = "s";
  private static final String FIELD_MESSAGE = "m";

  /** 默认上限：8MB。超出后裁剪，只保留每个文件最近若干条记录。 */
  public static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;

  /** 裁剪时每个文件保留的记录条数。 */
  private static final int KEEP_PER_PATH = 20;

  private final File file;
  private final long maxBytes;

  private final Map<String, DiffRecord> byId = new LinkedHashMap<>();
  private final Map<String, List<DiffRecord>> chains = new LinkedHashMap<>();

  public FileDiffStore(File file) {
    this(file, DEFAULT_MAX_BYTES);
  }

  public FileDiffStore(File file, long maxBytes) {
    this.file = file;
    this.maxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
    load();
  }

  @Override
  public synchronized DiffRecord recordDiff(
      String filePath, String oldContent, String newContent, boolean oldExists) {
    DiffRecord record =
        new DiffRecord(
            java.util.UUID.randomUUID().toString(),
            filePath,
            oldContent,
            newContent,
            oldExists,
            System.currentTimeMillis());
    chains.computeIfAbsent(record.getFilePath(), k -> new ArrayList<>()).add(record);
    byId.put(record.getId(), record);

    JSONObject json = new JSONObject();
    try {
      json.put(FIELD_EVENT, EVENT_DIFF)
          .put(FIELD_ID, record.getId())
          .put(FIELD_PATH, record.getFilePath())
          .put(FIELD_OLD, record.getOldContent())
          .put(FIELD_NEW, record.getNewContent())
          .put(FIELD_EXISTED, record.isOldExists())
          .put(FIELD_TIMESTAMP, record.getTimestamp());
    } catch (org.json.JSONException e) {
      // JSONObject.put 只在 key 为 null 时抛；这里的 key 都是常量，实际不可达。
      return record;
    }
    appendLine(json.toString());
    return record;
  }

  @Override
  public synchronized List<DiffRecord> getDiffChain(String filePath) {
    List<DiffRecord> chain = chains.get(filePath);
    return chain == null ? new ArrayList<>() : new ArrayList<>(chain);
  }

  @Override
  public synchronized DiffRecord findById(String diffId) {
    return diffId == null ? null : byId.get(diffId);
  }

  @Override
  public synchronized List<DiffRecord> getAll() {
    return new ArrayList<>(byId.values());
  }

  @Override
  public synchronized DiffRecord markReverted(String diffId) {
    DiffRecord current = findById(diffId);
    if (current == null) {
      return null;
    }
    DiffRecord updated = replace(current.asReverted());
    appendEvent(EVENT_REVERT, diffId);
    return updated;
  }

  @Override
  public synchronized DiffRecord setReview(
      String diffId, String reviewState, String reviewMessage) {
    DiffRecord current = findById(diffId);
    if (current == null) {
      return null;
    }
    DiffRecord updated = replace(current.withReview(reviewState, reviewMessage));
    JSONObject json = new JSONObject();
    try {
      json.put(FIELD_EVENT, EVENT_REVIEW)
          .put(FIELD_ID, diffId)
          .put(FIELD_STATE, updated.getReviewState())
          .put(FIELD_MESSAGE, updated.getReviewMessage());
    } catch (org.json.JSONException e) {
      return updated;
    }
    appendLine(json.toString());
    return updated;
  }

  /** 记录总数，供诊断与测试。 */
  public synchronized int size() {
    return byId.size();
  }

  /** 持久化文件。 */
  public File getFile() {
    return file;
  }

  /**
   * 回放日志到内存。
   *
   * <p>坏行一律跳过：文件是 append-only 且用户可能手工编辑（ACS 是 IDE），
   * 一行损坏不该让全部历史不可读。若中途崩溃留下半行，跳过它即可。
   */
  private void load() {
    if (file == null || !file.exists()) {
      return;
    }
    String text;
    try {
      text = readAll(file);
    } catch (IOException e) {
      // 读不出来就当作空历史：回滚不可用，但应用不该因此起不来。
      return;
    }
    for (String line : text.split("\n")) {
      applyLine(line);
    }
  }

  private void applyLine(String line) {
    if (line == null || line.trim().isEmpty()) {
      return;
    }
    JSONObject json;
    try {
      json = new JSONObject(line);
    } catch (org.json.JSONException e) {
      return;
    }
    String event = json.optString(FIELD_EVENT, "");
    String id = json.optString(FIELD_ID, "");
    if (id.isEmpty()) {
      return;
    }
    if (EVENT_DIFF.equals(event)) {
      DiffRecord record =
          new DiffRecord(
              id,
              json.optString(FIELD_PATH, ""),
              json.optString(FIELD_OLD, ""),
              json.optString(FIELD_NEW, ""),
              json.optBoolean(FIELD_EXISTED, false),
              json.optLong(FIELD_TIMESTAMP, 0L));
      // 同一 id 重复出现时后写的覆盖先写的：裁剪后重写文件理论上不会产生重复，
      // 但手工编辑或异常中断可能留下重复行，覆盖语义比保留首条更符合「最后写入生效」。
      List<DiffRecord> chain =
          chains.computeIfAbsent(record.getFilePath(), k -> new ArrayList<>());
      boolean replaced = false;
      for (int i = 0; i < chain.size(); i++) {
        if (id.equals(chain.get(i).getId())) {
          chain.set(i, record);
          replaced = true;
          break;
        }
      }
      if (!replaced) {
        chain.add(record);
      }
      byId.put(id, record);
      return;
    }
    DiffRecord current = byId.get(id);
    if (current == null) {
      // 状态事件先于其记录出现（不该发生，除非文件被截断）→ 无法应用，跳过。
      return;
    }
    if (EVENT_REVERT.equals(event)) {
      replace(current.asReverted());
    } else if (EVENT_REVIEW.equals(event)) {
      replace(current.withReview(json.optString(FIELD_STATE, ""), json.optString(FIELD_MESSAGE, "")));
    }
  }

  private DiffRecord replace(DiffRecord updated) {
    byId.put(updated.getId(), updated);
    List<DiffRecord> chain = chains.get(updated.getFilePath());
    if (chain != null) {
      for (int i = 0; i < chain.size(); i++) {
        if (updated.getId().equals(chain.get(i).getId())) {
          chain.set(i, updated);
          break;
        }
      }
    }
    return updated;
  }

  /**
   * 追加一行；写完后必要时裁剪。
   *
   * <p><b>顺序是「先追加、后裁剪」</b>：裁剪会从内存索引重写整个文件，而新记录此刻已在
   * 内存里，所以裁剪自然会把它写进去。若反过来先裁剪再追加，新记录会被写两遍
   * （一遍在重写里、一遍在追加里），白占空间。
   *
   * <p>写失败一律吞掉：回滚是补救手段，不能因为磁盘写不进去让 agent 的写入操作失败——
   * 那会让「本来成功的文件改动」看起来像失败了。
   */
  private void appendLine(String line) {
    if (file == null) {
      return;
    }
    try {
      File parent = file.getParentFile();
      if (parent != null && !parent.exists() && !parent.mkdirs()) {
        return;
      }
      try (FileOutputStream output = new FileOutputStream(file, true)) {
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
      }
      if (file.length() > maxBytes) {
        compact();
      }
    } catch (IOException e) {
      // 见方法注释：静默降级为「仅内存记录」。
    }
  }

  private void appendEvent(String event, String diffId) {
    JSONObject json = new JSONObject();
    try {
      json.put(FIELD_EVENT, event).put(FIELD_ID, diffId);
    } catch (org.json.JSONException e) {
      return;
    }
    appendLine(json.toString());
  }

  /**
   * 裁剪：每个文件只保留最近 {@link #KEEP_PER_PATH} 条，重写整个文件。
   *
   * <p>这是唯一会原地改写文件的路径，因此用「写临时文件 + 原子替换」：
   * 直接覆盖原文件时若中途失败，会同时丢掉旧历史与新历史。
   */
  private void compact() {
    Map<String, List<DiffRecord>> kept = new LinkedHashMap<>();
    for (Map.Entry<String, List<DiffRecord>> entry : chains.entrySet()) {
      List<DiffRecord> chain = entry.getValue();
      int from = Math.max(0, chain.size() - KEEP_PER_PATH);
      kept.put(entry.getKey(), new ArrayList<>(chain.subList(from, chain.size())));
    }

    Map<String, DiffRecord> keptById = new LinkedHashMap<>();
    for (List<DiffRecord> chain : kept.values()) {
      for (DiffRecord record : chain) {
        keptById.put(record.getId(), record);
      }
    }

    File temp = new File(file.getPath() + ".tmp");
    StringBuilder sb = new StringBuilder();
    for (DiffRecord record : keptById.values()) {
      JSONObject json = new JSONObject();
      try {
        json.put(FIELD_EVENT, EVENT_DIFF)
            .put(FIELD_ID, record.getId())
            .put(FIELD_PATH, record.getFilePath())
            .put(FIELD_OLD, record.getOldContent())
            .put(FIELD_NEW, record.getNewContent())
            .put(FIELD_EXISTED, record.isOldExists())
            .put(FIELD_TIMESTAMP, record.getTimestamp());
        sb.append(json).append('\n');
        if (record.isReverted()) {
          JSONObject revert = new JSONObject();
          revert.put(FIELD_EVENT, EVENT_REVERT).put(FIELD_ID, record.getId());
          sb.append(revert).append('\n');
        }
        if (record.getReviewState() != null
            && !DiffRecord.REVIEW_PENDING.equals(record.getReviewState())) {
          JSONObject review = new JSONObject();
          review
              .put(FIELD_EVENT, EVENT_REVIEW)
              .put(FIELD_ID, record.getId())
              .put(FIELD_STATE, record.getReviewState())
              .put(FIELD_MESSAGE, record.getReviewMessage());
          sb.append(review).append('\n');
        }
      } catch (org.json.JSONException e) {
        // 常量 key，实际不可达。
      }
    }

    boolean written = false;
    try {
      try (FileOutputStream output = new FileOutputStream(temp, false)) {
        output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
      }
      written = temp.renameTo(file);
      if (!written) {
        // 某些文件系统上目标存在时 rename 会失败；退化为删除后重命名。
        written = file.delete() && temp.renameTo(file);
      }
    } catch (IOException e) {
      written = false;
    }
    if (!written) {
      // 重写失败 → 文件仍是旧内容，内存索引也必须保持旧状态，
      // 否则会出现「内存里有、文件里没有」的假象。
      temp.delete();
      return;
    }

    // 内存索引跟着裁剪。不同步的话会留下文件里已不存在的记录，
    // 表现为「本次进程能查到，重启后就消失」。
    byId.clear();
    byId.putAll(keptById);
    chains.clear();
    chains.putAll(kept);
  }

  private static String readAll(File file) throws IOException {
    try (FileInputStream input = new FileInputStream(file)) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
