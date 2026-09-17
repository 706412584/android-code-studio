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
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 文件持久化的待办存储。
 *
 * <p><b>为什么不用 append-only</b>：待办是「当前状态」而非「历史事件」，每次提交都是
 * 整表覆盖，保留历史没有意义（没有人会想回看第 3 轮的待办列表）。因此这里直接整体
 * 重写——与 {@link FileDiffStore} 的取舍相反，因为数据的性质不同。
 *
 * <p>写入用「临时文件 + 原子替换」：直接覆盖时若中途失败，会连旧列表也一起丢掉，
 * 模型就彻底失去了进度。
 *
 * <p>读写失败一律静默降级（读失败→空列表，写失败→仅内存）：待办是辅助手段，
 * 不能因为它的问题让 agent 的主任务失败。
 */
public final class FileTodoStateStore implements TodoStateStore {

  private static final String FIELD_CONTENT = "c";
  private static final String FIELD_STATUS = "s";

  private final File file;

  private List<TodoItem> items = new ArrayList<>();

  public FileTodoStateStore(File file) {
    this.file = file;
    load();
  }

  @Override
  public synchronized List<TodoItem> getItems() {
    return new ArrayList<>(items);
  }

  @Override
  public synchronized void setItems(List<TodoItem> next) {
    items = next == null ? new ArrayList<>() : new ArrayList<>(next);
    save();
  }

  @Override
  public synchronized void clear() {
    items = new ArrayList<>();
    save();
  }

  /** 持久化文件。 */
  public File getFile() {
    return file;
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
      List<TodoItem> loaded = new ArrayList<>();
      for (int i = 0; i < array.length(); i++) {
        JSONObject json = array.optJSONObject(i);
        if (json == null) {
          continue;
        }
        String content = json.optString(FIELD_CONTENT, "");
        if (content.isEmpty()) {
          continue;
        }
        loaded.add(new TodoItem(content, json.optString(FIELD_STATUS, TodoItem.STATUS_PENDING)));
      }
      items = loaded;
    } catch (org.json.JSONException e) {
      // 文件损坏（用户手工编辑过，或上次写入被中断）→ 当作空列表。
      // 不报错：待办丢失的代价远小于让整个工具不可用。
      items = new ArrayList<>();
    }
  }

  private void save() {
    if (file == null) {
      return;
    }
    JSONArray array = new JSONArray();
    for (TodoItem item : items) {
      JSONObject json = new JSONObject();
      try {
        json.put(FIELD_CONTENT, item.getContent()).put(FIELD_STATUS, item.getStatus());
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
}
