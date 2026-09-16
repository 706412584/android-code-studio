/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import com.tom.rv2ide.artificial.secrets.ApiKey;

/**
 * 把 IDE 里配置的服务商与密钥，转成协议层需要的 {@link ModelConfig}。
 *
 * <p>这是新旧代码的接缝：老代码按「服务商」组织（Gemini / OpenAI / Anthropic / ...），
 * 新协议层按「协议类型」组织（OpenAI 兼容 / Anthropic Messages）。
 * OpenAI、DeepSeek、Grok、LocalLLM 都讲 OpenAI 兼容协议，因此共用一种协议实现，
 * 差异只体现在 baseUrl 与模型名上。
 *
 * <p>本类只做映射，不发请求，便于单测。
 */
public final class AgentModelConfigs {

  private AgentModelConfigs() {}

  /** 一个服务商的连接信息。 */
  public static final class ProviderEndpoint {
    public final String id;
    public final String label;
    public final ModelProtocolType protocolType;
    public final String baseUrl;
    public final String apiKey;

    ProviderEndpoint(
        String id, String label, ModelProtocolType protocolType, String baseUrl, String apiKey) {
      this.id = id;
      this.label = label;
      this.protocolType = protocolType;
      this.baseUrl = baseUrl;
      this.apiKey = apiKey;
    }
  }

  /**
   * 解析指定服务商的端点信息；未配置密钥时返回 null。
   *
   * @param providerId 服务商标识，与 {@code Agents} 中的取值一致
   * @param customBaseUrl 自定义 baseUrl，仅 {@code localllm} 使用
   */
  public static ProviderEndpoint endpointFor(String providerId, String customBaseUrl) {
    if (providerId == null) {
      return null;
    }
    switch (providerId) {
      case "openai": {
        String key = ApiKey.INSTANCE.getOpenAIApiKey();
        return key.isEmpty()
            ? null
            : new ProviderEndpoint(
                "openai", "OpenAI", ModelProtocolType.OPENAI_COMPATIBLE,
                "https://api.openai.com/v1", key);
      }
      case "deepseek": {
        String key = ApiKey.INSTANCE.getDeepseekApiKey();
        return key.isEmpty()
            ? null
            : new ProviderEndpoint(
                "deepseek", "DeepSeek", ModelProtocolType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com/v1", key);
      }
      case "grok": {
        String key = ApiKey.INSTANCE.getGrokApiKey();
        return key.isEmpty()
            ? null
            : new ProviderEndpoint(
                "grok", "Grok", ModelProtocolType.OPENAI_COMPATIBLE,
                "https://api.x.ai/v1", key);
      }
      case "claude": {
        String key = ApiKey.INSTANCE.getAnthropicApiKey();
        return key.isEmpty()
            ? null
            : new ProviderEndpoint(
                "claude", "Anthropic Claude", ModelProtocolType.ANTHROPIC_MESSAGES,
                "https://api.anthropic.com", key);
      }
      case "localllm": {
        // 本地模型通常是无鉴权的 OpenAI 兼容服务（llama.cpp / Ollama / LM Studio），
        // 因此只要填了 baseUrl 就视为可用。
        if (customBaseUrl == null || customBaseUrl.trim().isEmpty()) {
          return null;
        }
        return new ProviderEndpoint(
            "localllm", "Local LLM", ModelProtocolType.OPENAI_COMPATIBLE,
            customBaseUrl.trim(), "local");
      }
      case "custom": {
        // 第三方 OpenAI 兼容网关：baseUrl / key / model 全部由用户填写。
        if (!ApiKey.INSTANCE.hasCustomEndpoint()) {
          return null;
        }
        String baseUrl = ApiKey.INSTANCE.getCustomBaseUrl().trim();
        return new ProviderEndpoint(
            "custom", "自定义端点", ModelProtocolType.OPENAI_COMPATIBLE,
            baseUrl, ApiKey.INSTANCE.getCustomApiKey());
      }
      default:
        return null;
    }
  }

  /**
   * 解析自定义端点使用的模型名。
   *
   * <p>自定义端点的模型名不能从 {@code Agents} 的预置列表取（那里没有用户自填的值），
   * 因此单独提供入口。
   */
  public static String modelIdFor(String providerId, String modelId) {
    if ("custom".equals(providerId)) {
      String custom = ApiKey.INSTANCE.getCustomModel();
      return custom.trim().isEmpty() ? modelId : custom.trim();
    }
    return modelId;
  }

  /**
   * 组装模型配置。
   *
   * @param endpoint 服务商端点
   * @param modelId 模型名
   * @param toolCallLimit 单轮对话允许的工具调用次数上限；{@code <= 0} 表示不限制
   */
  public static ModelConfig build(ProviderEndpoint endpoint, String modelId, int toolCallLimit) {
    if (endpoint == null) {
      throw new IllegalArgumentException("endpoint 不能为空");
    }
    return ModelConfig.builder(
            endpoint.id,
            endpoint.label,
            endpoint.protocolType,
            endpoint.label,
            endpoint.baseUrl,
            endpoint.apiKey,
            modelId == null ? "" : modelId)
        .toolCallLimit(toolCallLimit)
        .build();
  }

  /**
   * 判断某个服务商是否支持协议原生的工具调用。
   *
   * <p>Anthropic Messages 原生支持。OpenAI 兼容端是否支持取决于具体实现，
   * 保守起见交由调用方在首次失败后回退到文本解析——{@code ToolCallTextParser}
   * 会在正文里找调用，所以即使不支持原生工具也能工作。
   */
  public static boolean supportsNativeTools(ModelProtocolType type) {
    return type == ModelProtocolType.ANTHROPIC_MESSAGES;
  }
}
