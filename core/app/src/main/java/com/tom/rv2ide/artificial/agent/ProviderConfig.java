/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.artificial.agent;

import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个服务商配置：连接信息 + 4 个模型槽位。
 *
 * <p><b>与旧实现的关键差异</b>：此前服务商是硬编码在 {@link ProviderPresets} 里的常量，
 * 密钥存在按服务商写死的偏好键里。后果是「配了 A 的密钥、切到 B 时拿着 A 的密钥去请求」，
 * 而且两个服务商无法同时配置。改成记录后，<b>密钥随记录走</b>，切服务商不再串用。
 *
 * <p><b>4 个模型槽位</b>（{@code main} / {@code haiku} / {@code sonnet} / {@code opus}）：
 * 一个服务商可以配多个模型，按任务成本分级选用。只有 {@code main} 必填，其余可空——
 * 空槽位表示「与 main 相同」。模型名可带上下文后缀（如 {@code glm-5.2[1m]}），
 * 由 {@link ContextSizeParser} 解析。
 *
 * <p>纯 Java + org.json（Android 与 JVM 都有），可直接单测。
 */
public final class ProviderConfig {

  /** 槽位角色，顺序固定。 */
  public static final String SLOT_MAIN = "main";
  public static final String SLOT_HAIKU = "haiku";
  public static final String SLOT_SONNET = "sonnet";
  public static final String SLOT_OPUS = "opus";

  /** 槽位顺序表；界面与序列化都按它遍历，避免各处顺序不一致。 */
  public static final List<String> SLOT_ORDER =
      Collections.unmodifiableList(Arrays.asList(SLOT_MAIN, SLOT_HAIKU, SLOT_SONNET, SLOT_OPUS));

  private final String id;
  private final String label;
  private final ModelProtocolType protocolType;
  private final String baseUrl;
  private final String apiKey;
  /** 4 槽位模型名，顺序同 {@link #SLOT_ORDER}；空串表示未设置。 */
  private final String[] slotModels;

  public ProviderConfig(
      String id,
      String label,
      ModelProtocolType protocolType,
      String baseUrl,
      String apiKey,
      String[] slotModels) {
    this.id = safe(id);
    this.label = safe(label);
    this.protocolType =
        protocolType == null ? ModelProtocolType.OPENAI_COMPATIBLE : protocolType;
    this.baseUrl = safe(baseUrl);
    this.apiKey = safe(apiKey);
    this.slotModels = normalizeSlots(slotModels);
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }

  /** 补齐到 4 个槽位并去空白。 */
  private static String[] normalizeSlots(String[] slots) {
    String[] out = new String[SLOT_ORDER.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = (slots != null && i < slots.length) ? safe(slots[i]) : "";
    }
    return out;
  }

  public String getId() {
    return id;
  }

  public String getLabel() {
    return label.isEmpty() ? id : label;
  }

  public ModelProtocolType getProtocolType() {
    return protocolType;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  /** 取某槽位的模型名（可能带上下文后缀）。未知槽位返回空串。 */
  public String getSlotModel(String slot) {
    int index = SLOT_ORDER.indexOf(slot);
    return index < 0 ? "" : slotModels[index];
  }

  public String[] getSlotModels() {
    return slotModels.clone();
  }

  /** 主模型（未设置时为空串）。 */
  public String getMainModel() {
    return slotModels[0];
  }

  /**
   * 取指定槽位的模型名，空槽位回退到主模型。
   *
   * <p>「空 = 与 main 相同」而不是「空 = 不可用」：用户往往只关心主模型，
   * 其余三个槽位留空表示「这些场景也用主模型」，不必重复填四遍。
   */
  public String resolveSlot(String slot) {
    String value = getSlotModel(slot);
    return value.isEmpty() ? getMainModel() : value;
  }

  /** 发给 API 的真实模型 ID（已剥离上下文后缀）。 */
  public String apiModelId(String slot) {
    return ContextSizeParser.stripSuffix(resolveSlot(slot));
  }

  /** 该槽位声明的上下文窗口；未声明时返回 {@link ContextSizeParser#UNSET}。 */
  public int contextSize(String slot) {
    return ContextSizeParser.parseFromModelId(resolveSlot(slot));
  }

  /**
   * 是否具备发请求的最低条件。
   *
   * <p>本地服务商（Ollama / LM Studio）不需要密钥，因此判据是「有 baseUrl 且有主模型」，
   * 密钥只作为额外提示——判据必须与真正发请求时一致，否则界面显示可用、一发就失败。
   */
  public boolean isUsable() {
    return !baseUrl.isEmpty() && !getMainModel().isEmpty();
  }

  /** 该配置是否还需要用户补密钥（有 baseUrl 但没密钥）。 */
  public boolean needsApiKey() {
    return !baseUrl.isEmpty() && apiKey.isEmpty();
  }

  public ProviderConfig withApiKey(String newApiKey) {
    return new ProviderConfig(id, label, protocolType, baseUrl, newApiKey, slotModels);
  }

  // ---- 序列化 ----

  public JSONObject toJson() throws JSONException {
    JSONObject json = new JSONObject();
    json.put("id", id);
    json.put("label", label);
    json.put("protocol", protocolType.name());
    json.put("baseUrl", baseUrl);
    json.put("apiKey", apiKey);
    JSONArray models = new JSONArray();
    for (String model : slotModels) {
      models.put(model);
    }
    json.put("slots", models);
    return json;
  }

  /**
   * 反序列化。
   *
   * <p>字段缺失一律按空串处理而不是抛异常：配置文件可能来自旧版本，缺字段时应当
   * 退化成「该项未配置」并让用户补，而不是让整个服务商列表加载失败。
   */
  public static ProviderConfig fromJson(JSONObject json) {
    String id = json.optString("id", "");
    String label = json.optString("label", id);
    ModelProtocolType protocol =
        ModelProtocolType.fromStorage(json.optString("protocol", ""));
    String baseUrl = json.optString("baseUrl", "");
    String apiKey = json.optString("apiKey", "");
    JSONArray array = json.optJSONArray("slots");
    String[] slots = new String[SLOT_ORDER.size()];
    for (int i = 0; i < slots.length; i++) {
      slots[i] = array == null ? "" : array.optString(i, "");
    }
    return new ProviderConfig(id, label, protocol, baseUrl, apiKey, slots);
  }
}
