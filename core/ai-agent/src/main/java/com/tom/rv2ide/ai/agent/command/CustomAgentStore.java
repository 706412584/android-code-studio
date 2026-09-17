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

package com.tom.rv2ide.ai.agent.command;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 自定义 agent 的存储。
 *
 * <p>按名字索引（{@link LinkedHashMap}）而非列表：名字就是工具的标识，重复名字会让
 * 同一个 {@code agentx_xxx} 工具对应两个定义，模型看到的行为随机。写入时按名字覆盖，
 * 天然实现「编辑」。
 *
 * <p>读写失败一律静默降级：自定义 agent 是扩展能力，配置损坏不该让 AI 功能不可用。
 */
public final class CustomAgentStore {

  /** 数量上限。每个自定义 agent 都会变成一个工具定义，过多会占满 tools 数组。 */
  public static final int MAX_AGENTS = 30;

  private static final String FIELD_NAME = "name";
  private static final String FIELD_DESCRIPTION = "description";
  private static final String FIELD_PROMPT = "prompt";
  private static final String FIELD_ENABLED = "enabled";

  private final File file;

  private final Map<String, CustomAgent> agents = new LinkedHashMap<>();

  public CustomAgentStore(File file) {
    this.file = file;
    load();
  }

  /** 全部自定义 agent，按添加顺序。 */
  public synchronized List<CustomAgent> all() {
    return new ArrayList<>(agents.values());
  }

  /** 可用的（已启用且校验通过）自定义 agent。 */
  public synchronized List<CustomAgent> usable() {
    List<CustomAgent> result = new ArrayList<>();
    for (CustomAgent agent : agents.values()) {
      if (agent.isUsable()) {
        result.add(agent);
      }
    }
    return result;
  }

  /** 按名字查找；名字大小写不敏感。 */
  public synchronized CustomAgent find(String name) {
    if (name == null) {
      return null;
    }
    return agents.get(CustomAgent.sanitizeName(name).toLowerCase(java.util.Locale.ROOT));
  }

  public synchronized int size() {
    return agents.size();
  }

  /**
   * 保存（新增或按名字覆盖）。
   *
   * @return 校验失败的原因；成功返回空串
   */
  public synchronized String save(CustomAgent agent) {
    if (agent == null) {
      return "agent 不能为空。";
    }
    String error = agent.validationError();
    if (!error.isEmpty()) {
      return error;
    }
    if (!agents.containsKey(agent.normalizedName()) && agents.size() >= MAX_AGENTS) {
      return "自定义 agent 数量已达上限（" + MAX_AGENTS + " 个）。";
    }
    agents.put(agent.normalizedName(), agent);
    persist();
    return "";
  }

  /** 按名字删除；返回是否删掉了东西。 */
  public synchronized boolean remove(String name) {
    if (name == null) {
      return false;
    }
    CustomAgent removed = agents.remove(CustomAgent.sanitizeName(name).toLowerCase(java.util.Locale.ROOT));
    if (removed == null) {
      return false;
    }
    persist();
    return true;
  }

  /** 清空。 */
  public synchronized void clear() {
    agents.clear();
    persist();
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
      for (int i = 0; i < array.length(); i++) {
        JSONObject json = array.optJSONObject(i);
        if (json == null) {
          continue;
        }
        CustomAgent agent =
            new CustomAgent(
                json.optString(FIELD_NAME, ""),
                json.optString(FIELD_DESCRIPTION, ""),
                json.optString(FIELD_PROMPT, ""),
                json.optBoolean(FIELD_ENABLED, true));
        if (agent.getName().isEmpty()) {
          continue;
        }
        agents.put(agent.normalizedName(), agent);
      }
    } catch (org.json.JSONException e) {
      // 文件损坏 → 当作空配置。不抛异常，否则 AI 功能会因此不可用。
      agents.clear();
    }
  }

  private void persist() {
    if (file == null) {
      return;
    }
    JSONArray array = new JSONArray();
    for (CustomAgent agent : agents.values()) {
      JSONObject json = new JSONObject();
      try {
        json.put(FIELD_NAME, agent.getName())
            .put(FIELD_DESCRIPTION, agent.getDescription())
            .put(FIELD_PROMPT, agent.getPrompt())
            .put(FIELD_ENABLED, agent.isEnabled());
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
   * 仅内存、不持久化的实现（测试与降级用）。
   *
   * <p>用 null 文件构造即可——{@link #load()} 与 {@link #persist()} 都对 null 文件直接
   * 返回，因此写入只落在内存里，进程结束即丢失。
   */
  public static CustomAgentStore inMemory() {
    return new CustomAgentStore(null);
  }
}
