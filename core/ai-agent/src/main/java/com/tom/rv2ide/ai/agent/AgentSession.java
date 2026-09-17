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

package com.tom.rv2ide.ai.agent;

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.protocol.ModelClient;
import com.tom.rv2ide.ai.protocol.ModelCompletionException;
import com.tom.rv2ide.ai.protocol.ModelCompletionResponse;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ModelRequestOptions;
import com.tom.rv2ide.ai.protocol.ModelStreamCallback;
import com.tom.rv2ide.ai.protocol.SystemModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCallTextParser;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 驱动一次 agent 对话的多轮工具调用循环。
 *
 * <p>循环语义：把系统提示词与用户请求发给模型 → 解析模型请求的工具调用 →
 * 逐个执行 → 把结果回灌进消息列表 → 再次请求模型，直到模型不再请求工具为止。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code runAgentLoop} 位于 app 模块的
 * {@code AgentExecutionController} 中，约 150 行内混合了 UI 渲染、进度会话、依赖注入与
 * 持久化调用，无法脱离 Android 测试。这里抽出纯逻辑版本：
 * <ul>
 *   <li>所有对外通知走 {@link AgentEvent} 事件流，不引用任何 UI 类型
 *   <li>工具调用与结果合并逻辑内联（上游在 Controller 里另有 {@code mergeToolCalls}）
 *   <li>保留上游的三重终止条件：取消、工具调用次数上限、总时长预算
 * </ul>
 *
 * <p>本类不做重试——重试由协议层负责（{@code AbstractHttpModelProtocol} 内含退避重试）。
 * 循环层只关心「是否还要再来一轮」。
 */
public final class AgentSession {

  /** 默认的总时长预算：30 分钟，与上游一致。 */
  public static final long DEFAULT_TIME_BUDGET_MS = 30L * 60L * 1000L;

  private final ModelClient modelClient;
  private final ToolRegistry registry;
  private final ToolExecutor executor;
  private final long timeBudgetMs;

  public AgentSession(ModelClient modelClient, ToolRegistry registry, ToolExecutor executor) {
    this(modelClient, registry, executor, DEFAULT_TIME_BUDGET_MS);
  }

  public AgentSession(
      ModelClient modelClient, ToolRegistry registry, ToolExecutor executor, long timeBudgetMs) {
    this.modelClient = modelClient;
    this.registry = registry;
    this.executor = executor;
    this.timeBudgetMs = timeBudgetMs;
  }

  /**
   * 运行循环直到模型不再请求工具或触发终止条件。
   *
   * @param config 模型配置（含 baseUrl / apiKey / modelId / 工具调用上限）
   * @param systemPrompt 系统提示词，通常由 {@link AgentPromptBuilder} 生成
   * @param userRequest 用户请求
   * @param toolContext 工具执行上下文（工作区、权限等）
   * @param cancellationToken 取消信号，可为 null
   * @param listener 事件接收者，可为 null
   * @return 循环结果，含最终文本与统计
   */
  public AgentRunResult run(
      ModelConfig config,
      String systemPrompt,
      String userRequest,
      ToolContext toolContext,
      ModelCancellationToken cancellationToken,
      AgentEvent.Listener listener) {
    return run(
        config, systemPrompt, userRequest, null, toolContext, cancellationToken, listener);
  }

  /**
   * 带历史的运行重载——用于续接既有会话。
   *
   * <p><b>为什么历史由调用方传入而非本类持有</b>：循环只负责"再跑一轮"的决策，
   * 不该知道消息从哪来（内存、文件、数据库）。持久化由调用方用 {@code listener}
   * 的事件流完成——每轮结束追加条目即可，循环无需改动。
   *
   * @param history 既有对话消息（由 {@code ConversationHistory.fold} 折叠而来），可为 null 或空
   */
  public AgentRunResult run(
      ModelConfig config,
      String systemPrompt,
      String userRequest,
      List<ModelMessage> history,
      ToolContext toolContext,
      ModelCancellationToken cancellationToken,
      AgentEvent.Listener listener) {

    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new SystemModelMessage(systemPrompt == null ? "" : systemPrompt));
    if (history != null) {
      messages.addAll(history);
    }
    messages.add(new UserModelMessage(userRequest == null ? "" : userRequest));

    List<ToolInfo> tools = new ArrayList<>(registry.getAll());

    int toolCallLimit = config == null ? -1 : config.getToolCallLimit();
    int toolCallCount = 0;
    int turnIndex = 0;
    String lastOutput = "";
    long startedAt = System.currentTimeMillis();

    while (true) {
      // 终止条件一：外部取消
      if (isCancelled(cancellationToken)) {
        return finish(listener, AgentEvent.failed("已取消"), lastOutput, toolCallCount, turnIndex);
      }
      // 终止条件二：工具调用次数上限
      if (toolCallLimit > 0 && toolCallCount >= toolCallLimit) {
        String message = "已达到工具调用次数上限 " + toolCallLimit + "。\n" + lastOutput;
        return finish(listener, AgentEvent.failed(message), lastOutput, toolCallCount, turnIndex);
      }
      // 终止条件三：总时长预算
      if (System.currentTimeMillis() - startedAt > timeBudgetMs) {
        String message =
            "已达到总时长预算 " + (timeBudgetMs / 60000L) + " 分钟。\n" + lastOutput;
        return finish(listener, AgentEvent.failed(message), lastOutput, toolCallCount, turnIndex);
      }

      turnIndex++;
      emit(listener, AgentEvent.turnStarted(turnIndex));

      ModelCompletionResponse response;
      try {
        response = requestModel(config, messages, tools, cancellationToken, listener);
      } catch (ModelCompletionException e) {
        String reason = "模型请求失败: " + e.getMessage();
        return finish(listener, AgentEvent.failed(reason), lastOutput, toolCallCount, turnIndex);
      }

      if (isCancelled(cancellationToken)) {
        return finish(listener, AgentEvent.failed("已取消"), lastOutput, toolCallCount, turnIndex);
      }

      // 工具调用有两个来源：协议原生字段，以及模型把调用写在正文里的文本形态。
      // 后者让不支持原生 tools 的模型（本地 LLM、部分兼容端）也能使用工具。
      ToolCallTextParser.Result parsed = ToolCallTextParser.parse(response.getText());
      List<ToolCall> calls = mergeToolCalls(response.getToolCalls(), parsed.getToolCalls());
      String output = parsed.hasToolMarkup() ? parsed.getText() : response.getText();

      emit(listener, AgentEvent.turnFinished(output, calls));

      if (!output.trim().isEmpty()) {
        lastOutput = output;
      }

      // 模型没有请求任何工具 → 对话结束
      if (calls.isEmpty()) {
        String finalText = lastOutput.trim().isEmpty() ? "（模型没有返回文本）" : lastOutput;
        return finish(
            listener, AgentEvent.completed(finalText), finalText, toolCallCount, turnIndex);
      }

      messages.add(
          new AssistantModelMessage(output, response.getReasoningContent(), calls));

      for (ToolCall call : calls) {
        if (isCancelled(cancellationToken)) {
          return finish(listener, AgentEvent.failed("已取消"), lastOutput, toolCallCount, turnIndex);
        }
        if (toolCallLimit > 0 && toolCallCount >= toolCallLimit) {
          String message = "已达到工具调用次数上限 " + toolCallLimit + "。\n" + lastOutput;
          return finish(listener, AgentEvent.failed(message), lastOutput, toolCallCount, turnIndex);
        }

        emit(listener, AgentEvent.toolStarted(call));
        ToolResult result = executor.execute(call, toolContext);
        toolCallCount++;
        emit(listener, AgentEvent.toolFinished(call, result));

        // 结果回灌：四元组齐全，isError 单独传递（部分协议需要它来区分成败）
        messages.add(
            new ToolModelMessage(
                result.getContent(), call.getId(), call.getName(), result.isError()));
      }
    }
  }

  private ModelCompletionResponse requestModel(
      ModelConfig config,
      List<ModelMessage> messages,
      List<ToolInfo> tools,
      ModelCancellationToken cancellationToken,
      AgentEvent.Listener listener)
      throws ModelCompletionException {

    ModelStreamCallback callback =
        listener == null
            ? null
            : new ModelStreamCallback() {
              @Override
              public void onTextDelta(String delta) {
                emit(listener, AgentEvent.textDelta(delta));
              }

              @Override
              public void onReasoningDelta(String delta) {
                emit(listener, AgentEvent.reasoningDelta(delta));
              }
            };

    // 只有声明支持原生工具的协议才把工具定义发给模型；否则模型通过提示词了解工具，
    // 并用文本形态输出调用（由 ToolCallTextParser 解析）。
    boolean nativeTools = modelClient.supportsNativeTools(config);
    List<ToolInfo> nativeToolList = nativeTools ? tools : new ArrayList<>();
    ModelRequestOptions options = new ModelRequestOptions("", false, nativeToolList);

    return modelClient.stream(config, messages, callback, cancellationToken, options);
  }

  /**
   * 合并原生与文本两种来源的工具调用。
   *
   * <p>去重依据有两个：调用 id，以及「工具名 + 规范化参数」签名。
   * 后者是必要的——同一个调用可能同时出现在原生字段和正文文本里，
   * 但两处的 id 不同，只靠 id 去重会重复执行。
   */
  static List<ToolCall> mergeToolCalls(List<ToolCall> nativeCalls, List<ToolCall> textCalls) {
    ArrayList<ToolCall> merged = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    Set<String> seenSignatures = new HashSet<>();

    if (nativeCalls != null) {
      for (ToolCall call : nativeCalls) {
        if (call == null || call.getName().isEmpty() || !seenIds.add(call.getId())) {
          continue;
        }
        merged.add(call);
        seenSignatures.add(signature(call));
      }
    }
    if (textCalls != null) {
      for (ToolCall call : textCalls) {
        if (call == null || call.getName().isEmpty() || seenIds.contains(call.getId())) {
          continue;
        }
        if (!seenSignatures.add(signature(call))) {
          continue;
        }
        merged.add(call);
      }
    }
    return merged;
  }

  private static String signature(ToolCall call) {
    return call.getName() + '\u0000' + normalizedArguments(call.getArguments());
  }

  /** 参数按 key 排序后序列化，使同一调用的不同书写顺序能被识别为重复。 */
  private static String normalizedArguments(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return "";
    }
    try {
      org.json.JSONObject object = new org.json.JSONObject(raw);
      java.util.List<String> names = new ArrayList<>();
      java.util.Iterator<String> keys = object.keys();
      while (keys.hasNext()) {
        names.add(keys.next());
      }
      java.util.Collections.sort(names);
      org.json.JSONObject sorted = new org.json.JSONObject();
      for (String name : names) {
        sorted.put(name, object.get(name));
      }
      return sorted.toString();
    } catch (org.json.JSONException e) {
      return raw;
    }
  }

  private static boolean isCancelled(ModelCancellationToken token) {
    return token != null && token.isCancelled();
  }

  private static void emit(AgentEvent.Listener listener, AgentEvent event) {
    if (listener != null) {
      listener.onEvent(event);
    }
  }

  private AgentRunResult finish(
      AgentEvent.Listener listener,
      AgentEvent terminalEvent,
      String lastOutput,
      int toolCallCount,
      int turns) {
    emit(listener, terminalEvent);
    boolean failed = terminalEvent.getType() == AgentEvent.Type.FAILED;
    return new AgentRunResult(terminalEvent.getMessage(), toolCallCount, turns, failed);
  }
}
