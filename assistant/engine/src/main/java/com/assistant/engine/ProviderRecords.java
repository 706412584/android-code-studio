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

package com.assistant.engine;

import android.content.Context;
import android.content.SharedPreferences;
import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 服务商记录表——**与 ACS 的 {@code ProviderConfigStore} 同一模型**。
 *
 * <p><b>为什么不是「预设 vs 自定义」两套</b>：ACS 的做法是「预设表退化为快速填充模板，
 * 真正的配置是用户自己的服务商记录」。一份记录含 id / 名称 / 协议 / baseUrl / apiKey /
 * 4 个槽位模型。这样：
 *
 * <ul>
 *   <li>每个服务商各有一份密钥，互不干扰（旧实现 7 家共用一个键，切服务商会串）
 *   <li>可以从预设一键新建，也可以完全手填
 *   <li>图片 / 视频生成只存「服务商 id + 模型名」，端点从记录解析——不必重填 baseUrl/apiKey
 * </ul>
 *
 * <p><b>与 ACS 的差异</b>：ACS 的槽位是 main/haiku/sonnet/opus 四档，用于它在编辑器里
 * 的主/快模型分工。本 App 只需要 main（其余三档留空表示「与 main 相同」），
 * 但字段保留——将来要加「压缩模型」之类时不必改存储格式。
 */
public final class ProviderRecords {

  private static final String PREFS_NAME = "assistant_providers";
  private static final String KEY_LIST = "records";

  /** 一条服务商记录。 */
  public static final class Record {
    public final String id;
    public final String label;
    public final ModelProtocolType protocolType;
    public final String baseUrl;
    public final String apiKey;
    /** 4 个槽位：main / fast / …；空串表示跟随 main。 */
    public final String[] slotModels;

    public Record(
        String id,
        String label,
        ModelProtocolType protocolType,
        String baseUrl,
        String apiKey,
        String[] slotModels) {
      this.id = id == null ? "" : id;
      this.label = label == null ? "" : label;
      this.protocolType =
          protocolType == null ? ModelProtocolType.OPENAI_COMPATIBLE : protocolType;
      this.baseUrl = baseUrl == null ? "" : baseUrl;
      this.apiKey = apiKey == null ? "" : apiKey;
      this.slotModels = normalizeSlots(slotModels);
    }

    private static String[] normalizeSlots(String[] raw) {
      String[] out = new String[] {"", "", "", ""};
      if (raw != null) {
        for (int i = 0; i < Math.min(raw.length, out.length); i++) {
          out[i] = raw[i] == null ? "" : raw[i];
        }
      }
      return out;
    }

    /** 主模型（槽位 0）。 */
    public String mainModel() {
      return slotModels[0];
    }

    /** 是否可用于发请求：baseUrl 与主模型都不为空。 */
    public boolean isUsable() {
      return !baseUrl.isEmpty() && !mainModel().isEmpty();
    }

    JSONObject toJson() throws JSONException {
      JSONArray slots = new JSONArray();
      for (String s : slotModels) {
        slots.put(s);
      }
      return new JSONObject()
          .put("id", id)
          .put("label", label)
          .put("protocol", protocolType.name())
          .put("baseUrl", baseUrl)
          .put("apiKey", apiKey)
          .put("slots", slots);
    }

    static Record fromJson(JSONObject o) {
      ModelProtocolType protocol;
      try {
        protocol = ModelProtocolType.valueOf(o.optString("protocol", "OPENAI_COMPATIBLE"));
      } catch (IllegalArgumentException e) {
        protocol = ModelProtocolType.OPENAI_COMPATIBLE;
      }
      JSONArray slots = o.optJSONArray("slots");
      String[] models = new String[4];
      if (slots != null) {
        for (int i = 0; i < Math.min(slots.length(), 4); i++) {
          models[i] = slots.optString(i, "");
        }
      }
      return new Record(
          o.optString("id", ""),
          o.optString("label", ""),
          protocol,
          o.optString("baseUrl", ""),
          o.optString("apiKey", ""),
          models);
    }
  }

  private final SharedPreferences prefs;

  public ProviderRecords(Context context) {
    this.prefs =
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** 全部记录，按添加顺序。 */
  public List<Record> all() {
    List<Record> out = new ArrayList<>();
    String raw = prefs.getString(KEY_LIST, "");
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    try {
      JSONArray arr = new JSONArray(raw);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o != null) {
          out.add(Record.fromJson(o));
        }
      }
    } catch (JSONException e) {
      // 存储损坏：返回空表，用户重配一次即可，不让整个设置页打不开。
    }
    return out;
  }

  public Record find(String id) {
    if (id == null) {
      return null;
    }
    for (Record record : all()) {
      if (record.id.equals(id)) {
        return record;
      }
    }
    return null;
  }

  /**
   * 从预设新建一条记录。
   *
   * <p>预设只是**快速填充模板**：id 用预设 id（这样「从预设新建」后仍能按预设识别 logo），
   * 但配置存在本表里，用户可随意改。
   */
  public void addFromPreset(ProviderPresets.Preset preset) {
    List<Record> list = all();
    // 已存在同 id 时不重复添加（用户可能点了两次）。
    for (Record existing : list) {
      if (existing.id.equals(preset.getId())) {
        return;
      }
    }
    list.add(
        new Record(
            preset.getId(),
            preset.getLabel(),
            preset.getProtocolType(),
            preset.getBaseUrl(),
            "",
            new String[] {preset.getDefaultModel(), "", "", ""}));
    save(list);
  }

  /** 完全自定义新建。 */
  public String addCustom(
      String label, String baseUrl, String apiKey, ModelProtocolType protocol, String mainModel) {
    return addCustom(label, baseUrl, apiKey, protocol,
        new String[] {mainModel == null ? "" : mainModel});
  }

  /**
   * 完全自定义新建（**保留全部槽位**）。
   *
   * <p>编辑表单会同时编辑 main 与其余三个槽位；只收 main 的旧重载会把用户填的第二/三/四
   * 槽位静默清空。本重载接收完整槽位数组并原样落库。
   */
  public String addCustom(
      String label, String baseUrl, String apiKey, ModelProtocolType protocol, String[] slotModels) {
    List<Record> list = all();
    String id = "custom_" + System.currentTimeMillis();
    list.add(new Record(id, label, protocol, baseUrl, apiKey, slotModels));
    save(list);
    return id;
  }

  /** 更新一条（按 id）。 */
  public void update(
      String id,
      String label,
      String baseUrl,
      String apiKey,
      ModelProtocolType protocol,
      String[] slotModels) {
    List<Record> list = all();
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).id.equals(id)) {
        list.set(i, new Record(id, label, protocol, baseUrl, apiKey, slotModels));
        break;
      }
    }
    save(list);
  }

  public void remove(String id) {
    List<Record> list = all();
    list.removeIf(r -> r.id.equals(id));
    save(list);
  }

  private void save(List<Record> list) {
    JSONArray arr = new JSONArray();
    for (Record record : list) {
      try {
        arr.put(record.toJson());
      } catch (JSONException ignored) {
        // 单条序列化失败不拖垮整表。
      }
    }
    prefs.edit().putString(KEY_LIST, arr.toString()).apply();
  }
}
