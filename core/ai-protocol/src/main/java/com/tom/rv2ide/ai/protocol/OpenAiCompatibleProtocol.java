/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from LineCode Pro (https://github.com/LangLang03/LineCodePro),
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

package com.tom.rv2ide.ai.protocol;
import com.tom.rv2ide.ai.tool.api.ErrorLog;
import com.tom.rv2ide.ai.tool.api.ToolCall;

import com.tom.rv2ide.ai.protocol.ModelCompletionException;
import com.tom.rv2ide.ai.protocol.ModelCompletionResponse;
import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.protocol.ModelRequestOptions;
import com.tom.rv2ide.ai.protocol.ReasoningCompatibility;
import com.tom.rv2ide.ai.protocol.ModelStreamCallback;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.AiBehaviorSettings;
import com.tom.rv2ide.ai.protocol.ThinkTagParser;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelContextParser;
import com.tom.rv2ide.ai.protocol.DashscopeReasoningStrategy;
import com.tom.rv2ide.ai.protocol.DeepseekReasoningStrategy;
import com.tom.rv2ide.ai.protocol.DefaultReasoningStrategy;
import com.tom.rv2ide.ai.protocol.KimiReasoningStrategy;
import com.tom.rv2ide.ai.protocol.MinimaxReasoningStrategy;
import com.tom.rv2ide.ai.protocol.MoonshotReasoningStrategy;
import com.tom.rv2ide.ai.protocol.OpenAiChatReasoningStrategy;
import com.tom.rv2ide.ai.protocol.ReasoningDeltaExtractor;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import com.tom.rv2ide.ai.protocol.StringUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class OpenAiCompatibleProtocol extends AbstractHttpModelProtocol {

    private final OpenAiMessageSerializer messageSerializer = new OpenAiMessageSerializer();
    private final ReasoningStrategyRegistry reasoningStrategyRegistry = createDefaultRegistry();

    private static ReasoningStrategyRegistry createDefaultRegistry() {
        ReasoningStrategyRegistry registry = new ReasoningStrategyRegistry();
        registry.register(new DashscopeReasoningStrategy());
        registry.register(new MinimaxReasoningStrategy());
        registry.register(new DeepseekReasoningStrategy());
        registry.register(new KimiReasoningStrategy());
        registry.register(new MoonshotReasoningStrategy());
        registry.register(new OpenAiChatReasoningStrategy());
        registry.register(new DefaultReasoningStrategy());
        return registry;
    }

    @Override
    public boolean supportsNativeTools(ModelConfig model) {
        return OpenAiCompatibleCapabilities.supportsNativeTools(model);
    }

    @Override
    public boolean supportsDedicatedCompression() {
        return true;
    }

    @Override
    public boolean supportsImageGeneration() {
        return true;
    }

    @Override
    public ModelCompletionResponse complete(ModelConfig config, List<ModelMessage> messages) throws ModelCompletionException {
        String raw = "";
        try {
            JSONObject body = new JSONObject();
            body.put("model", ModelContextParser.apiModelId(config));
            body.put("messages", messageSerializer.messagesJson(messages));
            body.put("temperature", 0.2);
            applyReasoningRequest(config, body, ModelRequestOptions.defaults());

            HashMap<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + config.getApiKey());
            raw = postJson(endpoint(config.getBaseUrl(), "/chat/completions"), body, headers);
            JSONObject response = new JSONObject(raw);
            String text = response
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content");
            JSONObject usage = response.optJSONObject("usage");
            int inputTokens = usage == null ? 0 : usage.optInt("prompt_tokens", 0);
            int outputTokens = usage == null ? 0 : usage.optInt("completion_tokens", 0);
            return new ModelCompletionResponse(text, "", java.util.Collections.emptyList(), inputTokens, outputTokens);
        } catch (ModelCompletionException e) {
            throw e;
        } catch (Exception e) {
            logParseError("parse_openai_complete", raw, e);
            throw new ModelCompletionException("OpenAI compatible protocol parse failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ModelCompletionResponse stream(
            ModelConfig config,
            List<ModelMessage> messages,
            ModelStreamCallback callback,
            ModelCancellationToken cancellationToken,
            ModelRequestOptions options
    ) throws ModelCompletionException {
        ModelRequestOptions requestOptions = options == null ? ModelRequestOptions.defaults() : options;
        JSONObject body;
        try {
            body = new JSONObject();
            body.put("model", ModelContextParser.apiModelId(config));
            body.put("messages", messageSerializer.messagesJson(messages, requestOptions.isPreserveReasoning()));
            body.put("temperature", 0.2);
            body.put("stream", true);
            if (!requestOptions.getTools().isEmpty()) {
                body.put("tools", ToolInfo.toJsonArray(requestOptions.getTools()));
                body.put("tool_choice", "auto");
            }
            applyReasoningRequest(config, body, requestOptions);
        } catch (Exception e) {
            throw new ModelCompletionException("OpenAI request build failed: " + e.getMessage(), e);
        }

        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ReasoningDeltaExtractor reasoningDeltaExtractor = new ReasoningDeltaExtractor();
        ThinkTagParser thinkTagParser = new ThinkTagParser();
        HashMap<Integer, ToolCallBuilder> toolCallBuilders = new HashMap<>();
        final int[] usageInputTokens = new int[1];
        final int[] usageOutputTokens = new int[1];
        // 部分内容保留（cc-haha commit buffer）：流中断且未出现工具调用时随异常带出
        com.tom.rv2ide.ai.protocol.AssistantCommitBuffer commitBuffer = new com.tom.rv2ide.ai.protocol.AssistantCommitBuffer();

        try {
        postJsonSse(endpoint(config.getBaseUrl(), "/chat/completions"), body, headers, cancellationToken, (eventType, data) -> {
            if ("[DONE]".equals(data.trim())) {
                return;
            }
            JSONObject event = new JSONObject(data);
            if (event.has("error")) {
                throw new ModelCompletionException("OpenAI stream error: " + describeError(event.opt("error")));
            }
            // 部分兼容端点会在最后一个 chunk（含空 choices）携带 usage，先于 choices 处理。
            JSONObject usage = event.optJSONObject("usage");
            if (usage != null) {
                usageInputTokens[0] = Math.max(usageInputTokens[0], usage.optInt("prompt_tokens", 0));
                usageOutputTokens[0] = Math.max(usageOutputTokens[0], usage.optInt("completion_tokens", 0));
            }
            JSONArray choices = event.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return;
            }
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) {
                return;
            }
            if ("content_filter".equals(choice.optString("finish_reason"))) {
                throw new ModelCompletionException("OpenAI stream error: output blocked by content safety policy");
            }
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) {
                return;
            }

            JSONArray toolCalls = delta.optJSONArray("tool_calls");
            if (toolCalls != null && toolCalls.length() > 0) {
                commitBuffer.markToolUseStarted();
                appendToolCallDeltas(toolCallBuilders, toolCalls);
            }

            String reasoningDelta = reasoningDeltaExtractor.extract(delta);
            if (reasoningDelta.length() > 0) {
                reasoning.append(reasoningDelta);
                commitBuffer.appendReasoning(reasoningDelta);
                if (callback != null) {
                    callback.onReasoningDelta(reasoningDelta);
                }
            }

            if (delta.has("content") && !delta.isNull("content")) {
                ThinkTagParser.Result parsed = thinkTagParser.append(delta.optString("content"));
                appendParsedDeltaTo(text, reasoning, parsed, callback, commitBuffer);
            }
        });
        String trailingReasoning = reasoningDeltaExtractor.flush();
        if (trailingReasoning.length() > 0) {
            reasoning.append(trailingReasoning);
            commitBuffer.appendReasoning(trailingReasoning);
            if (callback != null) {
                callback.onReasoningDelta(trailingReasoning);
            }
        }

        appendParsedDeltaTo(text, reasoning, thinkTagParser.flush(), callback, commitBuffer);
        return new ModelCompletionResponse(
                text.toString(),
                reasoning.toString(),
                buildToolCalls(toolCallBuilders),
                usageInputTokens[0],
                usageOutputTokens[0]
        );
        } catch (ModelCompletionException e) {
            throw attachPartial(e, commitBuffer);
        } catch (Exception e) {
            throw attachPartial(
                    new ModelCompletionException("OpenAI compatible protocol stream parse failed: " + e.getMessage(), e),
                    commitBuffer);
        }
    }

    /** 流中断且未越过工具边界时，把已收到内容挂到异常上供编排层提交。 */
    private static ModelCompletionException attachPartial(ModelCompletionException e,
                                                          com.tom.rv2ide.ai.protocol.AssistantCommitBuffer buffer) {
        if (e.hasPartial() || buffer == null || !buffer.hasPartial()) {
            return e;
        }
        return e.withPartial(buffer.text(), buffer.reasoning(), buffer.crossedToolBoundary());
    }

    /** appendParsedDelta 的带 commitBuffer 版本：流出的文本同步进缓冲。 */
    private void appendParsedDeltaTo(StringBuilder text, StringBuilder reasoning,
                                     ThinkTagParser.Result parsed, ModelStreamCallback callback,
                                     com.tom.rv2ide.ai.protocol.AssistantCommitBuffer commitBuffer) {
        if (parsed == null) {
            return;
        }
        if (parsed.getThinking().length() > 0) {
            reasoning.append(parsed.getThinking());
            commitBuffer.appendReasoning(parsed.getThinking());
            if (callback != null) {
                callback.onReasoningDelta(parsed.getThinking());
            }
        }
        if (parsed.getText().length() > 0) {
            text.append(parsed.getText());
            commitBuffer.appendText(parsed.getText());
            if (callback != null) {
                callback.onTextDelta(parsed.getText());
            }
        }
    }

  /**
   * 累积流式返回的 tool_calls 增量。
   *
   * <p>标准 OpenAI 流里每个调用块带 {@code index}，同一调用的 name/arguments 分多帧到达，
   * 按 index 归并即可。
   *
   * <p><b>非标准端点的处理</b>：部分网关把 {@code index} 发成 {@code -1} 或不发，且首帧
   * 可能不含 {@code function.name}。若照旧按 index 归并，多个不同调用会被挤进同一个
   * builder（name 互相覆盖、arguments 拼成非法 JSON）；而首帧的 arguments 片段若被丢弃，
   * 参数就残缺了。
   *
   * <p>因此 {@code index} 不可用时改用「调用边界」切分：
   * <ul>
   *   <li>末尾调用<b>还没有 name</b> → 这是它的续帧，复用（首帧无身份时不会丢参数）
   *   <li>否则 → 开启新调用
   * </ul>
   * 合成 key 从 {@link Integer#MIN_VALUE} <b>单调递增</b>，既不与真实 index（&ge;0）冲突，
   * 也保证 {@code buildToolCalls} 排序后仍按到达顺序输出。
   */
  private void appendToolCallDeltas(Map<Integer, ToolCallBuilder> builders, JSONArray toolCalls) {
    for (int i = 0; i < toolCalls.length(); i++) {
      JSONObject item = toolCalls.optJSONObject(i);
      if (item == null) {
        continue;
      }
      JSONObject function = item.optJSONObject("function");
      String id = item.has("id") && !item.isNull("id") ? item.optString("id") : "";
      String name =
          function != null && function.has("name") && !function.isNull("name")
              ? function.optString("name")
              : "";

      int index = item.optInt("index", i);
      ToolCallBuilder builder;
      if (index >= 0) {
        builder = builders.get(index);
        if (builder == null) {
          builder = new ToolCallBuilder();
          builders.put(index, builder);
        }
      } else {
        Integer tailKey = builders.isEmpty() ? null : java.util.Collections.max(builders.keySet());
        ToolCallBuilder tail = tailKey == null ? null : builders.get(tailKey);
        if (tail != null && !tail.hasName()) {
          // 末尾调用尚未命名 → 本帧是它的参数续帧，复用避免丢参数
          builder = tail;
        } else {
          builder = new ToolCallBuilder();
          builders.put(nextSyntheticIndex(builders), builder);
        }
      }

      if (id.length() > 0) {
        builder.id = id;
      }
      if (function == null) {
        continue;
      }
      if (name.length() > 0) {
        builder.name = name;
      }
      if (function.has("arguments") && !function.isNull("arguments")) {
        builder.arguments.append(function.optString("arguments"));
      }
    }
  }

  /**
   * 生成一个不与真实 index 冲突的合成 key，且<b>单调递增</b>。
   *
   * <p>递增很关键：合成 key 参与 {@code buildToolCalls} 的排序，递减会让多个调用的
   * 输出顺序整体反转。
   */
  private static int nextSyntheticIndex(Map<Integer, ToolCallBuilder> builders) {
    if (builders.isEmpty()) {
      return Integer.MIN_VALUE;
    }
    int max = java.util.Collections.max(builders.keySet());
    return max < 0 ? max + 1 : Integer.MIN_VALUE;
  }

  private List<ToolCall> buildToolCalls(Map<Integer, ToolCallBuilder> builders) {
    ArrayList<Integer> indexes = new ArrayList<>(builders.keySet());
    indexes.sort(Integer::compareTo);
    ArrayList<ToolCall> calls = new ArrayList<>();
    for (Integer index : indexes) {
      ToolCallBuilder builder = builders.get(index);
      if (builder == null) {
        continue;
      }
      if (!builder.hasName()) {
        // 不静默丢弃：这类丢帧会让「模型什么都没做」变得无从排查
        ErrorLog.record(
            "parse",
            "丢弃无 function.name 的 tool_call 增量",
            null,
            "index=" + index + " arguments=" + builder.arguments);
        continue;
      }
      calls.add(builder.build(index));
    }
    return calls;
  }

    private static final class ToolCallBuilder extends AbstractToolCallBuilder {
    }

    private void applyReasoningRequest(ModelConfig config, JSONObject body, ModelRequestOptions options) throws Exception {
        if (!OpenAiCompatibleCapabilities.supportsReasoningRequestParameters(config)) {
            return;
        }
        String base = config.getBaseUrl().toLowerCase(java.util.Locale.ROOT);
        String model = ModelContextParser.apiModelId(config).toLowerCase(java.util.Locale.ROOT);
        ModelRequestOptions compatibleOptions = ReasoningCompatibility.adapt(config, options);
        String effort = compatibleOptions.getReasoningEffort();
        boolean enabled = AiBehaviorSettings.isReasoningEnabled(effort);
        String concrete = AiBehaviorSettings.concreteReasoningEffort(effort);
        ReasoningRequestContext context = new ReasoningRequestContext(
                enabled, concrete, compatibleOptions.isPreserveReasoning(), base, model, thinkingBudget(concrete));
        ReasoningRequestStrategy strategy = reasoningStrategyRegistry.find(base, model);
        if (strategy != null) {
            strategy.apply(body, context);
        }
    }

    JSONObject reasoningRequestBodyForTest(ModelConfig config, ModelRequestOptions options) throws Exception {
        JSONObject body = new JSONObject();
        applyReasoningRequest(config, body, options == null ? ModelRequestOptions.defaults() : options);
        return body;
    }

    /**
     * 把 SSE 错误字段可读地转为文本。{@code error} 可能是字符串或 JSON 对象：
     * 对象时直接 {@code JSONObject.toString()} 会把中文转义成 {@code \\uXXXX}，
     * 因此优先读取 {@code message}/{@code type} 字段，最后统一做一次 Unicode 转义解码。
     */
    private static String describeError(Object error) {
        if (error == null) {
            return "";
        }
        if (error instanceof JSONObject) {
            JSONObject obj = (JSONObject) error;
            String message = obj.optString("message");
            if (message != null && message.length() > 0) {
                return StringUtils.decodeUnicodeEscapes(message);
            }
            String type = obj.optString("type");
            if (type != null && type.length() > 0) {
                return StringUtils.decodeUnicodeEscapes(type);
            }
            return StringUtils.decodeUnicodeEscapes(obj.optString("code"));
        }
        return StringUtils.decodeUnicodeEscapes(error.toString());
    }
}
