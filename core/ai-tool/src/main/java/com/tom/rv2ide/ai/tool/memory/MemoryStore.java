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

package com.tom.rv2ide.ai.tool.memory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 长期记忆的存储。
 *
 * <p><b>范围</b>：只做「用户或模型显式保存的记忆条目」及其检索，**不做自动学习**。
 * 自动从对话里抽取记忆需要判断「什么值得记住」，做不好会积累一堆噪声条目，
 * 反而污染每一轮的提示词——这比没有记忆更糟。
 *
 * <p><b>写入策略</b>：整表重写而非 append-only。记忆是「当前有效的知识集合」，
 * 条目会被删除与修改；保留历史没有意义，且会让检索扫到已删除的内容。
 * 用「临时文件 + 原子替换」避免中途失败丢掉全部记忆。
 *
 * <p>读写失败一律静默降级：记忆是增强手段，不能因为它的问题让 AI 不可用。
 */
public final class MemoryStore {

  /** 记忆条数上限。防止无节制增长把提示词占满。 */
  public static final int MAX_ENTRIES = 200;

  /** 单条记忆长度上限。 */
  public static final int MAX_TEXT_CHARS = 2000;

  private static final String FIELD_ID = "id";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_TAGS = "tags";
  private static final String FIELD_CREATED = "createdAt";

  private final File file;

  private List<MemoryEntry> entries = new ArrayList<>();

  public MemoryStore(File file) {
    this.file = file;
    load();
  }

  /** 全部记忆，按创建时间正序。 */
  public synchronized List<MemoryEntry> all() {
    return new ArrayList<>(entries);
  }

  public synchronized int size() {
    return entries.size();
  }

  /**
   * 添加一条记忆。
   *
   * @param text 记忆正文；空白会被拒绝
   * @param tags 标签，可为空
   * @param now 创建时间戳（由调用方传入，便于测试）
   * @return 新条目；正文为空时返回 null
   */
  public synchronized MemoryEntry add(String text, List<String> tags, long now) {
    if (text == null || text.trim().isEmpty()) {
      return null;
    }
    String trimmed = text.trim();
    if (trimmed.length() > MAX_TEXT_CHARS) {
      trimmed = trimmed.substring(0, MAX_TEXT_CHARS);
    }
    MemoryEntry entry =
        new MemoryEntry(UUID.randomUUID().toString(), trimmed, cleanTags(tags), now);

    // 达到上限时丢掉最旧的一条：新记忆通常比旧记忆更相关，
    // 而拒绝新增会让「记住这个」在长期使用后突然失效。
    List<MemoryEntry> next = new ArrayList<>(entries);
    next.add(entry);
    while (next.size() > MAX_ENTRIES) {
      next.remove(0);
    }
    entries = next;
    save();
    return entry;
  }

  /** 按 id 删除；返回是否删掉了东西。 */
  public synchronized boolean remove(String id) {
    if (id == null) {
      return false;
    }
    List<MemoryEntry> next = new ArrayList<>();
    boolean removed = false;
    for (MemoryEntry entry : entries) {
      if (entry.getId().equals(id)) {
        removed = true;
      } else {
        next.add(entry);
      }
    }
    if (!removed) {
      return false;
    }
    entries = next;
    save();
    return true;
  }

  /** 清空全部记忆。 */
  public synchronized void clear() {
    entries = new ArrayList<>();
    save();
  }

  /**
   * 按查询检索相关记忆。
   *
   * @param limit 最多返回条数
   */
  public synchronized List<MemoryEntry> search(String query, int limit) {
    return MemorySearch.search(entries, query, limit);
  }

  /**
   * 渲染为注入提示词的文本块。
   *
   * <p>返回空串表示无相关记忆，调用方不应把它拼进提示词（避免留下空标题）。
   */
  public synchronized String renderForPrompt(String query, int limit) {
    List<MemoryEntry> relevant = search(query, limit);
    if (relevant.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (MemoryEntry entry : relevant) {
      sb.append("- ").append(entry.getText()).append('\n');
    }
    return sb.toString().trim();
  }

  /** 持久化文件。 */
  public File getFile() {
    return file;
  }

  private static List<String> cleanTags(List<String> tags) {
    List<String> cleaned = new ArrayList<>();
    if (tags == null) {
      return cleaned;
    }
    for (String tag : tags) {
      if (tag == null) {
        continue;
      }
      String trimmed = tag.trim();
      if (!trimmed.isEmpty()) {
        cleaned.add(trimmed);
      }
    }
    return cleaned;
  }

  private void load() {
    if (file == null || !file.exists()) {
      return;
    }
    String text;
    try {
      text = readAll(file);
    } catch (IOException e) {
      return;
    }
    try {
      JSONArray array = new JSONArray(text);
      List<MemoryEntry> loaded = new ArrayList<>();
      for (int i = 0; i < array.length(); i++) {
        JSONObject json = array.optJSONObject(i);
        if (json == null) {
          continue;
        }
        String body = json.optString(FIELD_TEXT, "");
        if (body.trim().isEmpty()) {
          continue;
        }
        List<String> tags = new ArrayList<>();
        JSONArray tagArray = json.optJSONArray(FIELD_TAGS);
        if (tagArray != null) {
          for (int t = 0; t < tagArray.length(); t++) {
            String tag = tagArray.optString(t, "");
            if (!tag.trim().isEmpty()) {
              tags.add(tag.trim());
            }
          }
        }
        loaded.add(
            new MemoryEntry(
                json.optString(FIELD_ID, UUID.randomUUID().toString()),
                body,
                tags,
                json.optLong(FIELD_CREATED, 0L)));
      }
      entries = loaded;
    } catch (org.json.JSONException e) {
      // 文件损坏 → 当作空记忆。不抛异常，否则 AI 功能会因此完全不可用。
      entries = new ArrayList<>();
    }
  }

  private void save() {
    if (file == null) {
      return;
    }
    JSONArray array = new JSONArray();
    for (MemoryEntry entry : entries) {
      JSONObject json = new JSONObject();
      try {
        json.put(FIELD_ID, entry.getId())
            .put(FIELD_TEXT, entry.getText())
            .put(FIELD_TAGS, new JSONArray(entry.getTags()))
            .put(FIELD_CREATED, entry.getCreatedAt());
      } catch (org.json.JSONException e) {
        continue;
      }
      array.put(json);
    }

    File parent = file.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      return;
    }
    File temp = new File(file.getPath() + ".tmp");
    try {
      try (FileOutputStream output = new FileOutputStream(temp, false)) {
        output.write(array.toString().getBytes(StandardCharsets.UTF_8));
      }
      if (!temp.renameTo(file)) {
        if (!(file.delete() && temp.renameTo(file))) {
          temp.delete();
        }
      }
    } catch (IOException e) {
      temp.delete();
      // 见类注释：静默降级为仅内存。
    }
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

  /**
   * 仅内存、不持久化的实现。
   *
   * <p>用 null 文件构造即可——{@link #load()} 与 {@link #save()} 都对 null 文件直接返回，
   * 因此写入只落在内存里，进程结束即丢失；而 {@code add} 仍返回条目，
   * 调用方不会因此误判失败。行为差异全部由 {@code file == null} 表达，无需单独的类。
   *
   * <p>用于测试，以及偏好存储不可用时的降级。
   */
  public static MemoryStore inMemory() {
    return new MemoryStore(null);
  }
}
