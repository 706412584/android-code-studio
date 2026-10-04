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

package com.tom.rv2ide.ai.agent.builtin;

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
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 内置 agent 的用户覆盖存储。
 *
 * <p><b>为什么默认值与覆盖分开存</b>：内置预设随应用升级而变化（我们会改进提示词）。
 * 若把用户的修改与默认值混在一起落盘，升级后就无法区分「用户改过」与「默认变了」——
 * 要么覆盖用户改动，要么永远用着旧默认。这里只存**与默认不同**的覆盖项，
 * {@link #reset(String)} 就是删掉那条覆盖，默认值自然重新生效。
 *
 * <p><b>只认已知 id</b>：加载时丢弃 id 不在 {@link BuiltinAgents#all()} 里的记录——
 * 那是已被移除的旧内置 agent 留下的残渣，留着只会让 UI 显示一个无法派遣的幽灵条目。
 *
 * <p>读写失败一律静默降级：内置 agent 是扩展能力，配置损坏不该让 AI 功能不可用。
 */
public final class BuiltinAgentStore {

  private static final String FIELD_ID = "id";
  private static final String FIELD_NAME = "name";
  private static final String FIELD_DESCRIPTION = "description";
  private static final String FIELD_PROMPT = "prompt";
  private static final String FIELD_TOOLS = "tools";
  private static final String FIELD_ENABLED = "enabled";

  private final File file;

  /** id → 用户覆盖。仅存与默认不同的项。 */
  private final Map<String, BuiltinAgent> overrides = new LinkedHashMap<>();

  public BuiltinAgentStore(File file) {
    this.file = file;
    load();
  }

  /** 仅内存、不持久化的实现（测试与降级用）。 */
  public static BuiltinAgentStore inMemory() {
    return new BuiltinAgentStore(null);
  }

  /** 全部内置 agent：默认值合并用户覆盖，按预设顺序。 */
  public synchronized List<BuiltinAgent> all() {
    List<BuiltinAgent> result = new ArrayList<>();
    for (BuiltinAgent preset : BuiltinAgents.all()) {
      BuiltinAgent override = overrides.get(preset.getId());
      result.add(override == null ? preset : override);
    }
    return result;
  }

  /** 可派遣的内置 agent：启用且校验通过。 */
  public synchronized List<BuiltinAgent> enabled() {
    List<BuiltinAgent> result = new ArrayList<>();
    for (BuiltinAgent agent : all()) {
      if (agent.isUsable()) {
        result.add(agent);
      }
    }
    return result;
  }

  /** 按 id 取（默认值合并覆盖后）；未知返回 null。 */
  public synchronized BuiltinAgent find(String id) {
    String key = BuiltinAgent.sanitizeId(id);
    if (key.isEmpty()) {
      return null;
    }
    for (BuiltinAgent agent : all()) {
      if (agent.getId().equals(key)) {
        return agent;
      }
    }
    return null;
  }

  /**
   * 保存用户覆盖（按 id 覆盖）。
   *
   * <p>只接受已知的内置 id：否则会产生一个永远不会被 {@link #all()} 输出的孤儿覆盖。
   *
   * @return 校验失败原因；成功返回空串
   */
  public synchronized String save(BuiltinAgent agent) {
    if (agent == null) {
      return "agent 不能为空。";
    }
    BuiltinAgent preset = BuiltinAgents.find(agent.getId());
    if (preset == null) {
      return "未知的内置 agent：" + agent.getId();
    }
    String error = agent.validationError();
    if (!error.isEmpty()) {
      return error;
    }
    // 与默认完全一致 → 不存覆盖（等价于恢复默认），避免留下一个「改回了默认值」的冗余记录。
    if (isSameAsDefault(agent, preset)) {
      overrides.remove(agent.getId());
    } else {
      overrides.put(agent.getId(), agent);
    }
    persist();
    return "";
  }

  /**
   * 把某个内置 agent 恢复为默认：删除它的用户覆盖。
   *
   * @return 未知 id 时返回原因；成功（含本就无覆盖）返回空串
   */
  public synchronized String reset(String id) {
    String key = BuiltinAgent.sanitizeId(id);
    if (key.isEmpty() || BuiltinAgents.find(key) == null) {
      return "未知的内置 agent：" + id;
    }
    if (overrides.remove(key) != null) {
      persist();
    }
    return "";
  }

  /** 是否被用户改过。 */
  public synchronized boolean isCustomized(String id) {
    String key = BuiltinAgent.sanitizeId(id);
    return !key.isEmpty() && overrides.containsKey(key);
  }

  /** 清空全部覆盖（全部恢复默认）。 */
  public synchronized void clear() {
    overrides.clear();
    persist();
  }

  public File getFile() {
    return file;
  }

  private static boolean isSameAsDefault(BuiltinAgent agent, BuiltinAgent preset) {
    return agent.isEnabled() == preset.isEnabled()
        && agent.getPrompt().equals(preset.getPrompt())
        && agent.getName().equals(preset.getName())
        && agent.getDescription().equals(preset.getDescription())
        && agent.getTools().equals(preset.getTools());
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
        String id = BuiltinAgent.sanitizeId(json.optString(FIELD_ID, ""));
        // 丢弃已移除的内置 agent 留下的残渣。
        if (id.isEmpty() || BuiltinAgents.find(id) == null) {
          continue;
        }
        List<String> tools = new ArrayList<>();
        JSONArray toolsJson = json.optJSONArray(FIELD_TOOLS);
        if (toolsJson != null) {
          for (int j = 0; j < toolsJson.length(); j++) {
            String tool = toolsJson.optString(j, "").trim();
            if (!tool.isEmpty()) {
              tools.add(tool);
            }
          }
        }
        BuiltinAgent agent =
            new BuiltinAgent(
                id,
                json.optString(FIELD_NAME, ""),
                json.optString(FIELD_DESCRIPTION, ""),
                json.optString(FIELD_PROMPT, ""),
                tools,
                json.optBoolean(FIELD_ENABLED, true));
        if (agent.getName().isEmpty() || agent.getPrompt().isEmpty()) {
          continue;
        }
        overrides.put(id, agent);
      }
    } catch (org.json.JSONException e) {
      // 文件损坏 → 当作无覆盖（全部用默认）。不抛异常，否则 AI 功能会因此不可用。
      overrides.clear();
    }
  }

  private void persist() {
    if (file == null) {
      return;
    }
    JSONArray array = new JSONArray();
    for (BuiltinAgent agent : overrides.values()) {
      JSONObject json = new JSONObject();
      try {
        json.put(FIELD_ID, agent.getId())
            .put(FIELD_NAME, agent.getName())
            .put(FIELD_DESCRIPTION, agent.getDescription())
            .put(FIELD_PROMPT, agent.getPrompt())
            .put(FIELD_ENABLED, agent.isEnabled());
        JSONArray tools = new JSONArray();
        for (String tool : agent.getTools()) {
          tools.put(tool);
        }
        json.put(FIELD_TOOLS, tools);
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
