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
   * <p><b>查找顺序是「用户记录优先，预设兜底」</b>：用户可以在服务商管理界面增删改
   * 服务商（含 baseUrl / 协议 / 密钥 / 模型），记录存在 {@code providers.json}；
   * 预设表退化为「快速填充模板」，只在新记录尚未创建时提供默认值。
   *
   * <p>这个顺序解决了旧实现的一个硬伤：此前 7 个 OpenAI 兼容服务商共用一个密钥槽位，
   * 配了 A 的密钥切到 B 时会拿着 A 的密钥去请求。改成记录后密钥随记录走。
   *
   * @param providerId 服务商标识
   * @param customBaseUrl 覆盖 baseUrl（自定义端点需要；记录里已有值时以记录为准）
   */
  public static ProviderEndpoint endpointFor(String providerId, String customBaseUrl) {
    ProviderConfig record = recordFor(providerId);
    if (record != null) {
      return fromRecord(record, customBaseUrl);
    }
    return ProviderPresets.endpointFor(providerId, customBaseUrl, API_KEY_LOOKUP);
  }

  /**
   * 取用户配置的服务商记录；没有则返回 null。
   *
   * <p>读盘失败或存储不可用时返回 null，让调用方回退到预设——这样即使
   * {@code providers.json} 损坏，助手仍能用预设跑起来，而不是彻底不可用。
   */
  public static ProviderConfig recordFor(String providerId) {
    if (providerId == null || providerId.isEmpty()) {
      return null;
    }
    try {
      android.content.Context ctx = com.tom.rv2ide.app.BaseApplication.getBaseInstance();
      if (ctx == null) {
        return null;
      }
      return new ProviderConfigStore(ctx).find(providerId);
    } catch (Throwable e) {
      return null;
    }
  }

  /** 把用户记录转成端点信息。 */
  private static ProviderEndpoint fromRecord(ProviderConfig record, String customBaseUrl) {
    String baseUrl = record.getBaseUrl();
    // 自定义端点的 baseUrl 由用户在表单里填；记录里为空时接受调用方传入的覆盖值。
    if (baseUrl.isEmpty() && customBaseUrl != null && !customBaseUrl.trim().isEmpty()) {
      baseUrl = customBaseUrl.trim();
    }
    if (baseUrl.isEmpty()) {
      return null;
    }

    String apiKey = record.getApiKey();
    if (apiKey.isEmpty()) {
      // 本地服务（Ollama / LM Studio）无鉴权，但请求仍需一个非空 Authorization 占位。
      // 判据与预设路径一致：不强制要求密钥，只要求 baseUrl 与模型齐备。
      apiKey = "local";
    }

    return new ProviderEndpoint(
        record.getId(), record.getLabel(), record.getProtocolType(), baseUrl, apiKey);
  }

  /**
   * 该服务商当前是否可用（密钥与端点都齐）。
   *
   * <p>供选择器判断「能不能切过去」。判据必须与真正发请求时一致，否则界面显示可用、
   * 一发消息就报「未配置有效的 API 密钥」——把这里做成 {@link #endpointFor} 的薄封装
   * 就杜绝了这种漂移。
   */
  public static boolean isProviderUsable(String providerId, String customBaseUrl) {
    return endpointFor(providerId, customBaseUrl) != null;
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
    // 用户记录里的槽位模型优先。传进来的 modelId 可能带上下文后缀
    // （如 glm-5.2[1m]），必须剥离后再发给 API——后缀是本地元数据。
    ProviderConfig record = recordFor(providerId);
    if (record != null) {
      String fromRecord = record.resolveSlot(ProviderConfig.SLOT_MAIN);
      if (!fromRecord.isEmpty()) {
        return ContextSizeParser.stripSuffix(fromRecord);
      }
    }
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
    // 用户记录里的主模型优先于预设的推荐值：用户显式配过的模型才是他想要的，
    // 预设首项只是「没配过时的建议」。
    ProviderConfig record = recordFor(providerId);
    if (record != null && !record.getMainModel().isEmpty()) {
      return ContextSizeParser.stripSuffix(record.getMainModel());
    }
    ProviderPresets.Preset preset = ProviderPresets.find(providerId);
    return preset == null ? "" : preset.getDefaultModel();
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
