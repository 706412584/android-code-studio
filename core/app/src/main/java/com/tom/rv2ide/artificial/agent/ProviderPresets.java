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

package com.tom.rv2ide.artificial.agent;

import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 服务商预设表：**新增服务商只需在这里加一行**。
 *
 * <p><b>为什么需要它</b>：此前服务商信息散落在至少四处——端点映射（本包
 * {@code AgentModelConfigs}）、密钥读取（{@code ApiKey}）、模型列表（{@code Agents}）、
 * UI 显示名（{@code AIPreferencesFragment}）。加一个 OpenAI 兼容的服务商要同时改四处，
 * 漏掉任何一处都表现为「设置里能选但请求失败」或「列表里看不到」。
 * 集中到一张表后，端点、协议、模型与显示名只有一个来源。
 *
 * <p><b>接入成本</b>：绝大多数服务商讲 OpenAI 兼容协议，因此新增它们**不需要写代码**——
 * 加一条预设 + 在 {@link #apiKey} 处补一个密钥读取即可。只有协议不同的服务商
 * （如 Anthropic 的 Messages API）才需要额外的协议实现。
 *
 * <p>本类不引用 Android 类型（密钥通过 {@link ApiKeyLookup} 注入），可单测。
 */
public final class ProviderPresets {

  /** 密钥读取回调；由 app 层注入，使本类不依赖偏好存储。 */
  public interface ApiKeyLookup {
    /** 返回指定预设的密钥；未配置返回空串。 */
    String keyFor(String providerId);
  }

  /** 一个服务商预设。 */
  public static final class Preset {

    private final String id;
    private final String label;
    private final ModelProtocolType protocolType;
    private final String baseUrl;
    private final List<String> models;
    private final boolean requiresApiKey;
    private final String apiKeyHelpUrl;
    /** 4 槽位默认模型（main/haiku/sonnet/opus 顺序）。 */
    private final String[] slotModels;

    Preset(
        String id,
        String label,
        ModelProtocolType protocolType,
        String baseUrl,
        List<String> models,
        boolean requiresApiKey,
        String apiKeyHelpUrl) {
      this.id = id;
      this.label = label;
      this.protocolType = protocolType;
      this.baseUrl = baseUrl;
      this.models = Collections.unmodifiableList(new ArrayList<>(models));
      this.requiresApiKey = requiresApiKey;
      this.apiKeyHelpUrl = apiKeyHelpUrl == null ? "" : apiKeyHelpUrl;
      // 槽位默认值由模型清单推导：main 取首项，其余留空表示「与 main 相同」。
      // 不在这里硬编码四份模型名——那样每加一个模型要改两处，且两处很容易不一致。
      this.slotModels =
          new String[] {
            getDefaultModel(), "", "", "",
          };
    }

    /** 稳定标识，写入偏好与用于查找。 */
    public String getId() {
      return id;
    }

    /** 界面上显示的名字。 */
    public String getLabel() {
      return label;
    }

    public ModelProtocolType getProtocolType() {
      return protocolType;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    /** 该服务商可用的模型名，按推荐顺序。 */
    public List<String> getModels() {
      return models;
    }

    /** 推荐模型：列表首项。 */
    public String getDefaultModel() {
      return models.isEmpty() ? "" : models.get(0);
    }

    /** 是否必须填密钥。本地模型通常不需要。 */
    public boolean isRequiresApiKey() {
      return requiresApiKey;
    }

    /** 获取密钥的页面地址，供 UI 提示用户去哪里申请。 */
    public String getApiKeyHelpUrl() {
      return apiKeyHelpUrl;
    }

    /**
     * 4 槽位默认模型（main/haiku/sonnet/opus 顺序）。
     *
     * <p>供「从预设添加服务商」时预填表单，以及旧数据迁移时生成初始记录。
     */
    public String[] getSlotModels() {
      return slotModels.clone();
    }
  }

  /**
   * 全部预设，按界面展示顺序。
   *
   * <p>本地模型放最后：它是需要用户自备服务端的选项，不适合放在默认位置。
   */
  private static final List<Preset> ALL =
      Collections.unmodifiableList(
          Arrays.asList(
              // ---- 协议不同的服务商 ----
              new Preset(
                  "claude",
                  "Anthropic Claude",
                  ModelProtocolType.ANTHROPIC_MESSAGES,
                  "https://api.anthropic.com",
                  Arrays.asList(
                      "claude-sonnet-4-5-20250929",
                      "claude-haiku-4-5-20251001",
                      "claude-opus-4-5-20251101",
                      "claude-opus-4-1-20250805",
                      "claude-opus-4-20250514",
                      "claude-sonnet-4-20250514",
                      "claude-3-7-sonnet-20250219",
                      "claude-3-5-haiku-20241022",
                      "claude-3-haiku-20240307"),
                  true,
                  "https://console.anthropic.com/settings/keys"),

              // ---- OpenAI 兼容：海外 ----
              new Preset(
                  "openai",
                  "OpenAI",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://api.openai.com/v1",
                  Arrays.asList(
                      "gpt-5.1-codex-max",
                      "gpt-5.1-codex",
                      "gpt-5.1-codex-mini",
                      "gpt-5.1",
                      "gpt-5",
                      "gpt-5-mini",
                      "gpt-5-nano",
                      "gpt-4.1",
                      "gpt-4.1-mini",
                      "gpt-4o",
                      "gpt-4o-mini",
                      "o3",
                      "o4-mini"),
                  true,
                  "https://platform.openai.com/api-keys"),
              new Preset(
                  "grok",
                  "xAI Grok",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://api.x.ai/v1",
                  Arrays.asList(
                      "grok-4-1-fast-reasoning",
                      "grok-4-1-fast-non-reasoning",
                      "grok-code-fast-1",
                      "grok-4-fast-reasoning",
                      "grok-4-0709",
                      "grok-3",
                      "grok-3-mini"),
                  true,
                  "https://console.x.ai/"),
              new Preset(
                  "groq",
                  "Groq",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://api.groq.com/openai/v1",
                  Arrays.asList(
                      "llama-3.3-70b-versatile",
                      "llama-3.1-8b-instant",
                      "deepseek-r1-distill-llama-70b",
                      "qwen-2.5-coder-32b"),
                  true,
                  "https://console.groq.com/keys"),

              // ---- OpenAI 兼容：国内 ----
              new Preset(
                  "deepseek",
                  "DeepSeek",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://api.deepseek.com/v1",
                  Arrays.asList("deepseek-chat", "deepseek-reasoner"),
                  true,
                  "https://platform.deepseek.com/api_keys"),
              new Preset(
                  "glm",
                  "智谱 GLM",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://open.bigmodel.cn/api/paas/v4",
                  Arrays.asList("glm-4.6", "glm-4.5", "glm-4.5-air", "glm-4-plus", "glm-4-flash"),
                  true,
                  "https://open.bigmodel.cn/usercenter/apikeys"),
              new Preset(
                  "kimi",
                  "月之暗面 Kimi",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://api.moonshot.cn/v1",
                  Arrays.asList(
                      "kimi-k2-turbo-preview",
                      "kimi-k2-0905-preview",
                      "moonshot-v1-128k",
                      "moonshot-v1-32k"),
                  true,
                  "https://platform.moonshot.cn/console/api-keys"),
              new Preset(
                  "qwen",
                  "阿里通义千问",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://dashscope.aliyuncs.com/compatible-mode/v1",
                  Arrays.asList(
                      "qwen3-coder-plus",
                      "qwen3-max",
                      "qwen-plus",
                      "qwen-turbo",
                      "qwen2.5-coder-32b-instruct"),
                  true,
                  "https://bailian.console.aliyun.com/"),
              new Preset(
                  "minimax",
                  "MiniMax",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://api.minimax.chat/v1",
                  Arrays.asList("MiniMax-M2", "abab6.5s-chat", "abab6.5-chat"),
                  true,
                  "https://platform.minimaxi.com/"),
              new Preset(
                  "siliconflow",
                  "硅基流动",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://api.siliconflow.cn/v1",
                  Arrays.asList(
                      "deepseek-ai/DeepSeek-V3",
                      "Qwen/Qwen3-Coder-480B-A35B-Instruct",
                      "Qwen/Qwen2.5-Coder-32B-Instruct"),
                  true,
                  "https://cloud.siliconflow.cn/account/ak"),

              // ---- OpenAI 兼容：聚合 ----
              new Preset(
                  "openrouter",
                  "OpenRouter",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "https://openrouter.ai/api/v1",
                  Arrays.asList(
                      "anthropic/claude-sonnet-4.5",
                      "openai/gpt-5.1",
                      "google/gemini-2.5-pro",
                      "deepseek/deepseek-chat",
                      "qwen/qwen3-coder"),
                  true,
                  "https://openrouter.ai/keys"),

              // ---- 本地：无需密钥 ----
              new Preset(
                  "localllm",
                  "本地模型",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "http://127.0.0.1:11434/v1",
                  Arrays.asList("local-model", "qwen2.5-coder:7b", "llama3.1:8b"),
                  false,
                  ""),
              new Preset(
                  "custom",
                  "自定义端点",
                  ModelProtocolType.OPENAI_COMPATIBLE,
                  "",
                  Collections.<String>emptyList(),
                  false,
                  "")));

  /**
   * 默认服务商。
   *
   * <p>刻意不是 {@code gemini}：Gemini 讲的是 Google 自有协议（{@code generateContent}），
   * 而协议层只实现了 OpenAI 兼容与 Anthropic Messages 两种。把默认值指向一个没有协议
   * 实现的服务商，会让首次使用的用户在配好密钥后仍然「一条消息都发不出去」。
   * DeepSeek 讲 OpenAI 兼容协议、国内可直连、有免费额度，是更合适的开箱默认值。
   */
  public static final String DEFAULT_PROVIDER_ID = "deepseek";

  private ProviderPresets() {}

  /** 全部预设。 */
  public static List<Preset> all() {
    return ALL;
  }

  /** 按 id 查找；不存在返回 null。 */
  public static Preset find(String providerId) {
    if (providerId == null) {
      return null;
    }
    for (Preset preset : ALL) {
      if (preset.getId().equals(providerId)) {
        return preset;
      }
    }
    return null;
  }

  /** 按 id 取模型列表；未知服务商返回空列表。 */
  public static List<String> modelsFor(String providerId) {
    Preset preset = find(providerId);
    return preset == null ? Collections.<String>emptyList() : preset.getModels();
  }

  /** 按 id 取显示名；未知服务商回退为 id 本身（比显示空白好定位问题）。 */
  public static String labelFor(String providerId) {
    Preset preset = find(providerId);
    return preset == null ? (providerId == null ? "" : providerId) : preset.getLabel();
  }

  /** 全部服务商 id，按展示顺序。 */
  public static List<String> allIds() {
    List<String> ids = new ArrayList<>(ALL.size());
    for (Preset preset : ALL) {
      ids.add(preset.getId());
    }
    return ids;
  }

  /**
   * 反查模型属于哪个服务商。
   *
   * <p>按模型名找服务商（例如用户直接粘贴了一个模型名）。同名模型在多个服务商出现时
   * 返回**第一个**匹配——顺序即 {@link #all()} 的顺序，因此把更常用的服务商排在前面
   * 就能得到符合直觉的结果。
   */
  public static String providerForModel(String modelName) {
    if (modelName == null || modelName.isEmpty()) {
      return null;
    }
    for (Preset preset : ALL) {
      if (preset.getModels().contains(modelName)) {
        return preset.getId();
      }
    }
    return null;
  }

  /**
   * 解析出可用的端点；密钥缺失或 baseUrl 为空时返回 null。
   *
   * @param providerId 服务商 id
   * @param customBaseUrl 覆盖预设的 baseUrl（仅本地模型与自定义端点需要）
   * @param lookup 密钥读取回调；null 表示无密钥可用
   */
  public static AgentModelConfigs.ProviderEndpoint endpointFor(
      String providerId, String customBaseUrl, ApiKeyLookup lookup) {
    Preset preset = find(providerId);
    if (preset == null) {
      return null;
    }

    String baseUrl = preset.getBaseUrl();
    if (customBaseUrl != null && !customBaseUrl.trim().isEmpty()) {
      baseUrl = customBaseUrl.trim();
    }
    if (baseUrl.isEmpty()) {
      // 自定义端点必须由用户提供 baseUrl，预设里是空的。
      return null;
    }

    String apiKey = lookup == null ? "" : lookup.keyFor(providerId);
    if (apiKey == null) {
      apiKey = "";
    }
    if (preset.isRequiresApiKey() && apiKey.isEmpty()) {
      return null;
    }
    if (apiKey.isEmpty()) {
      // 本地服务通常无鉴权，但请求仍需一个非空的 Authorization 值占位。
      apiKey = "local";
    }

    return new AgentModelConfigs.ProviderEndpoint(
        preset.getId(), preset.getLabel(), preset.getProtocolType(), baseUrl, apiKey);
  }
}
