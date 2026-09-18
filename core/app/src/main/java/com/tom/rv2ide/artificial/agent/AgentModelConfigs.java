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

    public ProviderEndpoint(
        String id, String label, ModelProtocolType protocolType, String baseUrl, String apiKey) {
      this.id = id;
      this.label = label;
      this.protocolType = protocolType;
      this.baseUrl = baseUrl;
      this.apiKey = apiKey;
    }
  }

  /**
   * 解析指定服务商的端点信息；未配置密钥或 baseUrl 时返回 null。
   *
   * <p>端点信息全部来自 {@link ProviderPresets} 的预设表——本类不再持有服务商清单，
   * 新增服务商只需在预设表里加一行。
   *
   * @param providerId 服务商标识
   * @param customBaseUrl 覆盖预设的 baseUrl（本地模型与自定义端点需要）
   */
  public static ProviderEndpoint endpointFor(String providerId, String customBaseUrl) {
    return ProviderPresets.endpointFor(providerId, customBaseUrl, API_KEY_LOOKUP);
  }

  /**
   * 把偏好存储里的密钥接到预设表上。
   *
   * <p>包内可见而非 private：{@code AgentOrchestrator.diagnoseEndpointFailure} 需要用它
   * 判断失败原因究竟是「缺密钥」还是「缺 baseUrl」，否则只能给出含糊的提示。
   */
  static final ProviderPresets.ApiKeyLookup API_KEY_LOOKUP =
      new ProviderPresets.ApiKeyLookup() {
        @Override
        public String keyFor(String providerId) {
          if (providerId == null) {
            return "";
          }
          switch (providerId) {
            case "openai":
              return ApiKey.INSTANCE.getOpenAIApiKey();
            case "deepseek":
              return ApiKey.INSTANCE.getDeepseekApiKey();
            case "grok":
              return ApiKey.INSTANCE.getGrokApiKey();
            case "claude":
              return ApiKey.INSTANCE.getAnthropicApiKey();
            case "custom":
              return ApiKey.INSTANCE.getCustomApiKey();
            default:
              // 预设表里的其它服务商（glm / kimi / qwen / groq / openrouter 等）都是
              // OpenAI 兼容端点，密钥存在通用槽位里——它们是同一个协议的不同入口，
              // 不值得为每个服务商各开一个偏好键。
              return ApiKey.INSTANCE.getOpenAICompatibleApiKey();
          }
        }
      };

  /**
   * 解析自定义端点使用的模型名。
   *
   * <p>自定义端点的模型名不能从预设表取（那里没有用户自填的值），因此单独提供入口。
   */
  public static String modelIdFor(String providerId, String modelId) {
    if ("custom".equals(providerId)) {
      String custom = ApiKey.INSTANCE.getCustomModel();
      return custom.trim().isEmpty() ? modelId : custom.trim();
    }
    return modelId;
  }

  /**
   * 兜底模型名：未指定模型时按服务商取推荐值。
   *
   * <p>取不到时返回空串而不是抛异常——让请求带着空模型名发出去并由服务端报错，
   * 比在本地静默换一个用户没选的模型更容易排查。
   */
  public static String defaultModelFor(String providerId) {
    return ProviderPresets.find(providerId) == null
        ? ""
        : ProviderPresets.find(providerId).getDefaultModel();
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
