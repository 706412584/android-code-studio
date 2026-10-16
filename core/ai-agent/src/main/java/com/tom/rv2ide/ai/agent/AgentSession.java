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
import com.tom.rv2ide.ai.agent.context.RunContextManager;
import com.tom.rv2ide.ai.tool.api.ToolCallTextParser;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ExceptionUtils;
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
 *   <li>保留上游的终止条件：取消、工具调用次数上限、总时长预算（默认关闭，见
 *       {@link #DEFAULT_TIME_BUDGET_MS}）
 * </ul>
 *
 * <p>本类不做重试——重试由协议层负责（{@code AbstractHttpModelProtocol} 内含退避重试）。
 * 循环层只关心「是否还要再来一轮」。
 */
public final class AgentSession {

  /**
   * 默认的总时长预算：0 表示**不限制**。
   *
   * <p><b>2026-10-15 由 30 分钟改为不限制</b>：30 分钟对长任务（大型重构、
   * 构建/测试循环）是硬切——用户只看到「已达到总时长预算」而任务半途而废，
   * 且往往恰好在接近完成时被切断。是否继续交由用户决定（随时可取消），
   * 单次工具超时与工具调用次数上限仍然各自生效。
   *
   * <p>保留该字段与构造参数：子类/测试可显式传入预算（&gt;0 时生效），
   * 循环的终止检查按 {@code timeBudgetMs > 0} 判断是否启用。
   */
  public static final long DEFAULT_TIME_BUDGET_MS = 0L;

  private final ModelClient modelClient;
  private final ToolRegistry registry;
  private final ToolExecutor executor;
  private final long timeBudgetMs;

  // ── 运行中消息注入（cc-haha queued_command 同款语义）──
  //
  // 用户在运行期间点「立即插入」时，消息**不打断当前轮**，而是进入本槽；循环在
  // 「所有工具执行完、下一轮请求之前」消费它——这是唯一安全的注入点：此刻 messages
  // 尾部是完整的 tool_result 集合，追加一条 user 消息不会让 tool_result 与 assistant
  // 消息交错（OpenAI 兼容协议容忍交错，但 Anthropic 的 tool_use/tool_result 配对
  // 依赖相邻性，半途插入会直接 400）。
  //
  // 线程：offerInject 由 UI/orchestrator 线程调用，poll 在循环线程——用锁而非
  // 无锁队列，因为还要与 acceptingInject 的翻转原子。

  private final Object injectLock = new Object();
  private final java.util.ArrayDeque<String> injectQueue = new java.util.ArrayDeque<>();

  /**
   * 系统通知槽（如「后台任务已完成」）。
   *
   * <p><b>为什么与用户注入分开</b>：两者的来源与渲染完全不同。用户注入要作为
   * {@link AgentEvent.Type#USER_MESSAGE} 上报，让 UI 画出用户气泡；而系统通知不是
   * 用户说的话，画成用户气泡会让人以为是自己发的。因此分两个槽，通知走 PROGRESS
   * 事件（UI 只显示一行提示），但同样在「工具执行完、下一轮请求前」这个安全点注入。
   */
  private final java.util.ArrayDeque<String> noticeQueue = new java.util.ArrayDeque<>();

  /** 本实例是否还能接收注入。run 收尾（含所有 return 路径）时翻 false。 */
  private boolean acceptingInject = true;

  /**
   * 投递一条系统通知（后台任务完成等）。返回是否被接受。
   *
   * <p>返回 false 表示本实例已收尾、没有循环会再读——调用方应改走「状态栏通知」
   * 之类的兜底路径，而不是把通知丢掉。
   */
  public boolean offerNotice(String text) {
    if (text == null || text.trim().isEmpty()) {
      return false;
    }
    synchronized (injectLock) {
      if (!acceptingInject) {
        return false;
      }
      noticeQueue.addLast(text);
      return true;
    }
  }

  /** 循环线程在安全边界取走一条通知；没有则返回 null。 */
  private String pollNotice() {
    synchronized (injectLock) {
      return noticeQueue.pollFirst();
    }
  }

  /**
   * 尝试投递一条运行中注入。返回是否被接受。
   *
   * <p><b>返回 false 时调用方必须回退</b>（把消息留在队列里）：本实例已收尾，
   * 没有循环会再读这个槽。
   */
  public boolean offerInject(String text) {
    if (text == null || text.trim().isEmpty()) {
      return false;
    }
    synchronized (injectLock) {
      if (!acceptingInject) {
        return false;
      }
      injectQueue.addLast(text);
      return true;
    }
  }

  /** 循环线程在安全边界取走一条注入；没有则返回 null。 */
  private String pollInject() {
    synchronized (injectLock) {
      return injectQueue.pollFirst();
    }
  }

  /**
   * 取走**未被消费**的注入（运行收尾后调用）。
   *
   * <p>投递成功（offerInject 返回 true）但循环恰好已过最后一个检查点时，
   * 消息会留在这里。调用方（orchestrator）负责把它们放回队列——不取走的话
   * 这些消息就永久丢失了。
   */
  public List<String> drainLeftoverInjects() {
    synchronized (injectLock) {
      List<String> leftover = new ArrayList<>(injectQueue);
      injectQueue.clear();
      return leftover;
    }
  }

  /**
   * 运行中注入的包装文案（cc-haha `wrapCommandText` 的 human 分支同款语义）。
   *
   * <p>包装文本只进本轮请求的 messages 数组；落盘与 UI 气泡都用**原文**——
   * 下轮运行 fold 历史时模型看到的是干净的用户消息，与 cc-haha 的瞬时 attachment 一致。
   */
  static String wrapInjectedUserMessage(String raw) {
    return "用户在你工作期间发来一条新消息：\n"
        + raw
        + "\n\n重要：完成当前任务后，你必须处理上面的用户消息，不要忽略它。";
  }

  /**
   * 系统通知的包装文案。
   *
   * <p>与用户注入的措辞刻意不同：通知是**事实陈述**（某个后台任务结束了），
   * 模型据此自行决定要不要处理（通常会用 {@code task_output} 看结果）。
   * 用「你必须处理」会强迫它在无关时也去处理，浪费轮次。
   */
  static String wrapNotice(String raw) {
    return "[系统通知]\n" + raw;
  }

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
    return run(
        config, systemPrompt, userRequest, history, toolContext, cancellationToken, listener, null);
  }

  /**
   * 带上下文管理的运行重载——P0-2。
   *
   * <p><b>为什么上下文管理在循环内部而不在入口</b>：一轮对话里消息持续增长，入口处
   * 算好的预算在若干轮后可能已不足（尤其是一次读入大文件之后）。把裁剪放进循环，
   * 每次请求前都保证请求能发出去，长任务才不会跑到一半因超限失败。
   *
   * @param contextManager 上下文管理器；为 {@code null} 时不做裁剪与压缩（旧行为）
   */
  public AgentRunResult run(
      ModelConfig config,
      String systemPrompt,
      String userRequest,
      List<ModelMessage> history,
      ToolContext toolContext,
      ModelCancellationToken cancellationToken,
      AgentEvent.Listener listener,
      RunContextManager contextManager) {
    return run(
        config, systemPrompt, userRequest, history, toolContext, cancellationToken, listener,
        contextManager, null, null);
  }

  /**
   * 带附件与推理强度的运行重载。
   *
   * @param rawInputJson 多模态输入（图片等）的原始 JSON；{@code null} 表示纯文本。
   *     走 {@link UserModelMessage} 的第二个构造参数——两条协议序列化路径都会把它
   *     转成 image_url / image block，不需要各自特殊处理。
   * @param reasoningEffort 推理强度（off/low/medium/high 或空表示默认）；空值时由
   *     {@link ModelRequestOptions} 归一成 medium。
   */
  public AgentRunResult run(
      ModelConfig config,
      String systemPrompt,
      String userRequest,
      List<ModelMessage> history,
      ToolContext toolContext,
      ModelCancellationToken cancellationToken,
      AgentEvent.Listener listener,
      RunContextManager contextManager,
      String rawInputJson,
      String reasoningEffort) {
    try {
      return runLoop(
          config, systemPrompt, userRequest, history, toolContext, cancellationToken, listener,
          contextManager, rawInputJson, reasoningEffort);
    } finally {
      // 收尾：关闭注入槽。此后 offerInject 一律拒绝——调用方（orchestrator）据此
      // 把消息留在队列里走正常排队路径。必须在 finally：本方法有多条 return
      // （取消/超限/超时/正常完成），漏掉任何一条都会让注入槽永远可写而无人消费。
      synchronized (injectLock) {
        acceptingInject = false;
      }
    }
  }

  private AgentRunResult runLoop(
      ModelConfig config,
      String systemPrompt,
      String userRequest,
      List<ModelMessage> history,
      ToolContext toolContext,
      ModelCancellationToken cancellationToken,
      AgentEvent.Listener listener,
      RunContextManager contextManager,
      String rawInputJson,
      String reasoningEffort) {

    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new SystemModelMessage(systemPrompt == null ? "" : systemPrompt));
    if (history != null) {
      messages.addAll(history);
    }
    // 有附件时走带 rawInputJson 的构造：纯文本请求传 null 而不是空串，
    // 让协议层能明确区分「没有附件」与「附件字段为空」。
    String prompt = userRequest == null ? "" : userRequest;
    messages.add(
        rawInputJson == null || rawInputJson.isEmpty()
            ? new UserModelMessage(prompt)
            : new UserModelMessage(prompt, rawInputJson));
    // 本次请求的下标：裁剪必须保住它，否则模型不知道自己在做什么。
    final int historyEnd = messages.size() - 1;

    // 工具列表**每轮请求前重建**（见循环内），不能只算一次：
    // tool_search 展开的按需工具必须从下一轮起出现在 tools 数组里，
    // 只算一次的话展开结果永远不生效（模型检索到却调不到）。

    int toolCallLimit = config == null ? -1 : config.getToolCallLimit();
    int toolCallCount = 0;
    int turnIndex = 0;
    String lastOutput = "";
    long startedAt = System.currentTimeMillis();
    // 反应式压缩重试只允许一次（见下方 catch 块）。放在循环外：整个运行共享，
    // 收缩后仍超限说明问题不在体积，重试更多次只会反复烧请求。
    boolean overflowRetried = false;

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
      // 终止条件三：总时长预算（0 = 不限制，见 DEFAULT_TIME_BUDGET_MS）
      if (timeBudgetMs > 0 && System.currentTimeMillis() - startedAt > timeBudgetMs) {
        String message =
            "已达到总时长预算 " + (timeBudgetMs / 60000L) + " 分钟。\n" + lastOutput;
        return finish(listener, AgentEvent.failed(message), lastOutput, toolCallCount, turnIndex);
      }

      // 运行中注入检查点（cc-haha 同款语义）：此刻上一轮的工具已全部执行完
      // （或本轮是首轮），messages 尾部是完整的 tool_result 集合——追加 user
      // 消息不会破坏 tool_use/tool_result 配对（见 injectQueue 注释）。
      //
      // 首轮时也会取：用户在别处发起运行、消息刚插进来时循环可能还没发首个请求，
      // 这种窗口下消息也应在首轮就被模型看到，而不是等下一轮。
      String injected;
      while ((injected = pollInject()) != null) {
        emit(listener, AgentEvent.userMessage(injected));
        messages.add(new UserModelMessage(wrapInjectedUserMessage(injected)));
      }

      // 系统通知（后台任务完成等）走同一安全点，但不作为用户消息渲染。
      // 以 user 角色注入消息体：此刻尾部是完整 tool_result 集合，追加 user 消息
      // 不会破坏 Anthropic 的 tool_use/tool_result 配对（见上方注释）。
      String notice;
      while ((notice = pollNotice()) != null) {
        emit(listener, AgentEvent.progress(notice));
        messages.add(new UserModelMessage(wrapNotice(notice)));
      }

      // 每次请求前裁剪：预算在轮次之间会因新增消息而变得不足。
      if (contextManager != null) {
        List<ModelMessage> fitted = contextManager.fit(messages, historyEnd);
        messages.clear();
        messages.addAll(fitted);
      }

      turnIndex++;
      emit(listener, AgentEvent.turnStarted(turnIndex));

      // 每轮重建工具列表：上一轮 tool_search 展开的按需工具在这里进入 tools 数组，
      // 未展开的按需工具（MCP）不出现——它们的目录已随提示词注入（见 ToolSearchTool）。
      //
      // 代价说明：tools 数组变化会打断服务端的前缀缓存（prompt cache），展开的那一轮
      // 首 token 延迟上升。这是按需加载的固有代价（Aharou 同样如此），且只在
      // 「模型真的检索并展开」的轮次发生——不展开时列表逐轮相同，缓存不受影响。
      List<ToolInfo> tools = new ArrayList<>(registry.getRequestableTools());

      ModelCompletionResponse response;
      try {
        response =
            requestModel(config, messages, tools, cancellationToken, listener, reasoningEffort);
      } catch (ModelCompletionException e) {
        // ── 反应式压缩重试（cc-haha reactive compact 同款语义）──
        //
        // 服务端报上下文超限（400 context_length / "prompt is too long" / "maximum
        // context length" 等）时，本地估算显然低估了实际用量（图片按张固定估算、
        // 各服务商分词差异都可能造成）。此时不能把 400 原样抛给用户——cc-haha 的
        // 语义是「检测 → 摘要/收缩替换 → 重试」，绝不裸抛。
        //
        // 本地无模型调用可做摘要（那需要网络与用户等待），退而求其次做**激进裁剪**：
        // 把消息列表收缩到当前估算的一半，然后重试一次。只重试一次：
        // 收缩后仍超限说明问题不在体积（或窗口配置本身有误），重试更多次只会
        // 反复烧请求。裁剪保留系统提示词、本次请求与最近轮次（见 shrinkForOverflow）。
        if (isContextOverflowError(e) && contextManager != null && !overflowRetried) {
          overflowRetried = true;
          if (contextManager.shrinkForOverflow(messages, historyEnd)) {
            emit(listener, AgentEvent.progress("上下文超出服务端限制，已压缩历史后重试"));
            continue;
          }
        }
        String reason = "模型请求失败: " + e.getMessage();
        return finish(listener, AgentEvent.failed(reason), lastOutput, toolCallCount, turnIndex);
      }

      // 服务端 usage 是最可靠的用量来源（含工具定义等本地估算看不到的部分），
      // 拿它校准下一轮的裁剪判断。
      if (contextManager != null) {
        contextManager.recordResponse(response.getInputTokens());
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

      // 执行本轮的调用。**连续的可并发调用合批并行**（cc-haha 的 toolOrchestration
      // 同款语义，上限见 MAX_PARALLEL_TOOLS）：一次派遣多个子 agent 时它们同时跑，
      // 而不是一个跑完再跑下一个。其余调用保持串行——有副作用的工具（写文件、命令）
      // 必须按序生效，乱序执行会让「先写后读」这类依赖悄悄失效。
      //
      // 结果仍按**原调用顺序**回灌 messages：并行只影响执行，不影响结果的消息序。
      // 服务端（尤其 Anthropic 的 tool_result 配对）对顺序敏感，乱序回灌会 400。
      for (int i = 0; i < calls.size(); ) {
        if (isCancelled(cancellationToken)) {
          return finish(listener, AgentEvent.failed("已取消"), lastOutput, toolCallCount, turnIndex);
        }
        if (toolCallLimit > 0 && toolCallCount >= toolCallLimit) {
          String message = "已达到工具调用次数上限 " + toolCallLimit + "。\n" + lastOutput;
          return finish(listener, AgentEvent.failed(message), lastOutput, toolCallCount, turnIndex);
        }

        // 找出一段连续的「可并发且并发安全」的调用。
        int batchEnd = i;
        if (isConcurrencySafe(calls.get(i))) {
          while (batchEnd < calls.size()
              && isConcurrencySafe(calls.get(batchEnd))
              && (toolCallLimit <= 0 || toolCallCount + (batchEnd - i) < toolCallLimit)) {
            batchEnd++;
          }
        }

        if (batchEnd - i <= 1) {
          // 单条：走串行路径（与原行为完全一致——单次调用是绝大多数场景）。
          ToolCall call = calls.get(i);
          emit(listener, AgentEvent.toolStarted(call));
          ToolResult result = executor.execute(call, toolContext);
          toolCallCount++;
          emit(listener, AgentEvent.toolFinished(call, result));
          messages.add(
              new ToolModelMessage(
                  result.getContent(),
                  call.getId(),
                  call.getName(),
                  result.isError(),
                  toolResultImageJson(result)));
          i++;
          continue;
        }

        // 并行批：全部 toolStarted 先发（UI 一次看到 N 张运行中的卡片），
        // 然后并发执行，最后按序发 toolFinished 并回灌。
        List<ToolCall> batch = new ArrayList<>(calls.subList(i, batchEnd));
        for (ToolCall call : batch) {
          emit(listener, AgentEvent.toolStarted(call));
        }
        List<ToolResult> results = executeParallel(batch, toolContext, cancellationToken);
        for (int j = 0; j < batch.size(); j++) {
          ToolCall call = batch.get(j);
          ToolResult result = results.get(j);
          toolCallCount++;
          emit(listener, AgentEvent.toolFinished(call, result));
          messages.add(
              new ToolModelMessage(
                  result.getContent(),
                  call.getId(),
                  call.getName(),
                  result.isError(),
                  toolResultImageJson(result)));
        }
        i = batchEnd;
      }
    }
  }

  /** 并行批的大小上限。与 cc-haha 的 CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY 同为 10。 */
  private static final int MAX_PARALLEL_TOOLS = 10;

  /** 该调用是否可参与并行批：工具声明并发安全，且注册表能解析到它。 */
  private boolean isConcurrencySafe(ToolCall call) {
    if (call == null) {
      return false;
    }
    BaseTool tool = registry.get(ToolRegistry.canonicalName(call.getName()));
    // 未知工具不并行：它马上会被执行器拒绝，串行路径的报错顺序更可预期。
    return tool != null && tool.isConcurrencySafe();
  }

  /**
   * 并发执行一批调用，**返回顺序与入参一致**。
   *
   * <p>用固定线程池而不是 {@code parallelStream()}：池大小可控（{@link #MAX_PARALLEL_TOOLS}），
   * 且执行器（工具）共享的状态（权限、diff 记录）在并发下的行为不依赖 ForkJoin 的
   * 公共池策略。单批执行完即关池——池不跨批复用，避免运行期间常驻线程。
   *
   * <p>单个工具抛异常不会拖垮整批：捕获后转成 error 结果（与串行路径的
   * {@link ToolExecutor} 约定一致——错误回灌给模型，而不是中断对话）。
   */
  private List<ToolResult> executeParallel(
      List<ToolCall> batch, ToolContext toolContext, ModelCancellationToken cancellationToken) {
    int size = Math.min(batch.size(), MAX_PARALLEL_TOOLS);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(size);
    try {
      List<java.util.concurrent.Future<ToolResult>> futures = new ArrayList<>(size);
      for (int j = 0; j < size; j++) {
        ToolCall call = batch.get(j);
        futures.add(
            pool.submit(
                () -> {
                  if (isCancelled(cancellationToken)) {
                    return ToolResult.error("已取消");
                  }
                  try {
                    return executor.execute(call, toolContext);
                  } catch (RuntimeException e) {
                    ExceptionUtils.restoreInterrupt(e);
                    return ToolResult.of(
                        call.getId(),
                        call.getName(),
                        "工具执行失败: " + ExceptionUtils.describeException(e),
                        true);
                  }
                }));
      }
      List<ToolResult> results = new ArrayList<>(batch.size());
      for (int j = 0; j < batch.size(); j++) {
        if (j < size) {
          try {
            results.add(futures.get(j).get());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            results.add(ToolResult.error("已取消"));
          } catch (java.util.concurrent.ExecutionException e) {
            results.add(ToolResult.error("工具执行失败: " + e.getMessage()));
          }
        } else {
          // 超出上限的尾部：串行补执行，保证每个调用都有结果。
          ToolCall call = batch.get(j);
          try {
            results.add(executor.execute(call, toolContext));
          } catch (RuntimeException e) {
            ExceptionUtils.restoreInterrupt(e);
            results.add(
                ToolResult.of(
                    call.getId(),
                    call.getName(),
                    "工具执行失败: " + ExceptionUtils.describeException(e),
                    true));
          }
        }
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private ModelCompletionResponse requestModel(
      ModelConfig config,
      List<ModelMessage> messages,
      List<ToolInfo> tools,
      ModelCancellationToken cancellationToken,
      AgentEvent.Listener listener,
      String reasoningEffort)
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
    // 用调用方传入的强度。空串会被 ModelRequestOptions 归一成 medium——
    // 那是「用户没选」的默认值，与「用户明确选了中」等价，符合预期。
    ModelRequestOptions options =
        new ModelRequestOptions(reasoningEffort == null ? "" : reasoningEffort, false, nativeToolList);

    // 把重试通知转成事件发给 UI：UI 收到后必须丢弃本轮已渲染的部分输出，
    // 否则重发产生的新回答会与旧的部分内容在列表里并存。
    com.tom.rv2ide.ai.protocol.ModelClient.RetryListener retryListener =
        listener == null
            ? null
            : (attempt, maxAttempts, delayMs, cause) ->
                emit(
                    listener,
                    AgentEvent.streamRetrying(
                        attempt,
                        maxAttempts,
                        delayMs,
                        cause == null ? "" : cause.getMessage()));

    return modelClient.stream(
        config, messages, callback, cancellationToken, options, retryListener);
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

  /**
   * 把工具结果里的图片编码成 {@link ToolModelMessage} 的 rawInputJson。
   *
   * <p>无图片（绝大多数工具）或编码失败时返回 {@code null}，使消息退回纯文本形态。
   */
  private static String toolResultImageJson(ToolResult result) {
    if (result == null || !result.hasImage()) {
      return null;
    }
    try {
      return com.tom.rv2ide.ai.protocol.ImageInputPayload.imageResultJson(
          result.getImageMimeType(), result.getImageBase64());
    } catch (org.json.JSONException e) {
      return null;
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

  /**
   * 该请求失败是否为「上下文超出服务端限制」。
   *
   * <p>匹配各服务商的实际报错措辞（对齐 cc-haha {@code errors.ts} 的归一化关键词表）：
   * <ul>
   *   <li>OpenAI 系：{@code context_length_exceeded}、{@code maximum context length}</li>
   *   <li>Anthropic 系：{@code prompt is too long}、{@code input length and `max_tokens` exceed}</li>
   *   <li>通用兜底：{@code too many tokens}、{@code reduce the length}、{@code context window}</li>
   * </ul>
   *
   * <p>全部小写后包含匹配。宁可少匹配（漏判只是回到原来的失败路径）也不要多匹配——
   * 把其他 400（参数错误等）误判成超限会让裁剪白白丢弃历史，问题依旧。
   */
  static boolean isContextOverflowError(ModelCompletionException e) {
    if (e == null || e.getMessage() == null) {
      return false;
    }
    String message = e.getMessage().toLowerCase(java.util.Locale.ROOT);
    return message.contains("context_length_exceeded")
        || message.contains("maximum context length")
        // 刻意不匹配裸 "context length"：它会让 "invalid context length" /
        // "context length must be positive" 这类参数错误被误判成超限，
        // 白白裁掉一半历史再重试一次（用户代价：丢上下文 + 多一次请求）。
        || message.contains("prompt is too long")
        || message.contains("input length and")
        || message.contains("too many tokens")
        || message.contains("reduce the length")
        || message.contains("context window");
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
