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
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelProtocolType;

/**
 * 模型配置的读写：服务商 + 模型名 + API Key + 自定义 baseUrl。
 *
 * <p><b>为什么是「单一当前配置」而不是多服务商槽位</b>：ACS 那边有 14 个预设 × 4 槽位
 * 的复杂模型，那是因为它要在编辑器里做「主模型 / 快模型」分工。剥离后的通用助手只有
 * 「我现在用哪个模型」一个问题，多出来的槽位只会让设置页变成表格。等真正需要时再加。
 *
 * <p><b>API Key 存在 SharedPreferences 明文里</b>：这是刻意的取舍。Android 上没有
 * 真正安全的本地密钥存储（Keystore 保护的是密钥本身，用它加密一个本地库仍需在
 * 设备上解密），而威胁模型是「拿到设备的人」——那种情况下任何本地存储都挡不住。
 * 用 Keystore 加密只会增加复杂度而不提升安全性，反而不如让用户知道「它就是明文存的」。
 */
public final class ModelSettings {

  private static final String PREFS_NAME = "assistant_model";

  private static final String KEY_PROVIDER = "provider_id";
  private static final String KEY_MODEL = "model_id";
  private static final String KEY_API_KEY_PREFIX = "api_key_";
  private static final String KEY_BASE_URL_PREFIX = "base_url_";

  private final SharedPreferences prefs;

  /** 服务商记录表（与 ACS 的 ProviderConfigStore 同一模型）。 */
  private final ProviderRecords records;

  public ModelSettings(Context context) {
    this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    this.records = new ProviderRecords(context);
  }

  /** 服务商记录表（供设置页增删改）。 */
  public ProviderRecords providerRecords() {
    return records;
  }

  /**
   * 解析服务商的展示名与协议：**先查预设，再查自定义**。
   *
   * <p>两者统一成一个内部视图，避免 {@link #toModelConfig()} 里出现两套分支。
   */
  private Resolved resolve(String providerId) {
    // 记录表是唯一真相：预设只在「新建记录」时当模板用。
    ProviderRecords.Record record = records.find(providerId);
    if (record != null) {
      // 是否要密钥看预设（本地模型不需要）；记录本身不区分。
      ProviderPresets.Preset preset = ProviderPresets.find(providerId);
      boolean requiresKey = preset == null || preset.isRequiresApiKey();
      return new Resolved(
          record.label, record.baseUrl, record.protocolType, requiresKey, record.apiKey);
    }
    // 没有记录时退回预设的默认值（用户还没点「添加」就开始对话的场景）。
    ProviderPresets.Preset preset = ProviderPresets.find(providerId);
    if (preset != null) {
      return new Resolved(
          preset.getLabel(),
          preset.getBaseUrl(),
          preset.getProtocolType(),
          preset.isRequiresApiKey(),
          apiKey(providerId));
    }
    return null;
  }

  /** 解析后的服务商视图。 */
  private static final class Resolved {
    final String label;
    final String baseUrl;
    final ModelProtocolType protocolType;
    final boolean requiresApiKey;
    /** 记录里的密钥；空串表示回退到按服务商存的旧键。 */
    final String recordApiKey;

    Resolved(
        String label,
        String baseUrl,
        ModelProtocolType protocolType,
        boolean requiresApiKey,
        String recordApiKey) {
      this.label = label;
      this.baseUrl = baseUrl;
      this.protocolType = protocolType;
      this.requiresApiKey = requiresApiKey;
      this.recordApiKey = recordApiKey == null ? "" : recordApiKey;
    }
  }

  /** 当前服务商 id；未配置时为 {@link ProviderPresets#DEFAULT_PROVIDER_ID}。 */
  public String providerId() {
    String value = prefs.getString(KEY_PROVIDER, null);
    return value == null || value.isEmpty() ? ProviderPresets.DEFAULT_PROVIDER_ID : value;
  }

  public void setProviderId(String providerId) {
    prefs.edit().putString(KEY_PROVIDER, providerId == null ? "" : providerId).apply();
  }

  /**
   * 当前模型名。
   *
   * <p>未显式设置时回退到该服务商预设的推荐模型（列表首项）——用户选了服务商却没选模型，
   * 发送按钮不该因为「模型名为空」而失效。
   */
  public String modelId() {
    String value = prefs.getString(KEY_MODEL, null);
    if (value != null && !value.isEmpty()) {
      return value;
    }
    ProviderRecords.Record record = records.find(providerId());
    if (record != null) {
      return record.mainModel();
    }
    ProviderPresets.Preset preset = ProviderPresets.find(providerId());
    return preset == null ? "" : preset.getDefaultModel();
  }

  public void setModelId(String modelId) {
    prefs.edit().putString(KEY_MODEL, modelId == null ? "" : modelId).apply();
  }

  /** 某个服务商已保存的密钥；没有时为空串。 */
  public String apiKey(String providerId) {
    return prefs.getString(KEY_API_KEY_PREFIX + providerId, "");
  }

  public void setApiKey(String providerId, String apiKey) {
    prefs.edit()
        .putString(KEY_API_KEY_PREFIX + providerId, apiKey == null ? "" : apiKey.trim())
        .apply();
  }

  /** 某个服务商已保存的自定义 baseUrl；没有时为空串（用预设的）。 */
  public String customBaseUrl(String providerId) {
    return prefs.getString(KEY_BASE_URL_PREFIX + providerId, "");
  }

  public void setCustomBaseUrl(String providerId, String baseUrl) {
    prefs.edit()
        .putString(KEY_BASE_URL_PREFIX + providerId, baseUrl == null ? "" : baseUrl.trim())
        .apply();
  }

  /** 当前服务商的密钥。 */
  public String apiKey() {
    return apiKey(providerId());
  }

  /**
   * 解析出可直接交给 {@code AgentSession} 的配置。
   *
   * @return 配置对象；**配置不完整时返回 null**（未知服务商、baseUrl 为空）
   */
  public ModelConfig toModelConfig() {
    String providerId = providerId();
    Resolved resolved = resolve(providerId);
    if (resolved == null) {
      return null;
    }
    String baseUrl = customBaseUrl(providerId);
    if (baseUrl.isEmpty()) {
      baseUrl = resolved.baseUrl;
    }
    if (baseUrl.isEmpty()) {
      // 自定义端点必须由用户提供 baseUrl，预设里是空的。
      return null;
    }
    String model = modelId();
    if (model.isEmpty()) {
      return null;
    }
    ModelProtocolType protocol =
        resolved.protocolType == null ? ModelProtocolType.OPENAI_COMPATIBLE : resolved.protocolType;
    // 密钥优先用记录里的（ACS 模型：密钥随服务商记录走）；
    // 记录里没有时回退到旧的分服务商键（兼容升级前配过的用户）。
    String key = resolved.recordApiKey.isEmpty() ? apiKey(providerId) : resolved.recordApiKey;
    return ModelConfig.builder(
            providerId, resolved.label, protocol, resolved.label, baseUrl, key, model)
        .build();
  }

  /** 当前配置是否已就绪（能发出请求）。 */
  public boolean isReady() {
    ModelConfig config = toModelConfig();
    if (config == null) {
      return false;
    }
    // 需要密钥的服务商必须有密钥；本地模型不需要。
    Resolved resolved = resolve(providerId());
    boolean requiresKey = resolved == null || resolved.requiresApiKey;
    return !requiresKey || !config.getApiKey().isEmpty();
  }

  /** 面向用户的「还差什么」说明；已就绪时为空串。 */
  public String readinessProblem() {
    String providerId = providerId();
    Resolved resolved = resolve(providerId);
    if (resolved == null) {
      return "未知服务商：" + providerId;
    }
    String baseUrl = customBaseUrl(providerId);
    if (baseUrl.isEmpty()) {
      baseUrl = resolved.baseUrl;
    }
    if (baseUrl.isEmpty()) {
      return "请填写 API 地址";
    }
    if (modelId().isEmpty()) {
      return "请选择模型";
    }
    if (resolved.requiresApiKey && apiKey(providerId).isEmpty()) {
      return "请填写 " + resolved.label + " 的 API Key";
    }
    return "";
  }
}
