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

import com.tom.rv2ide.ai.agent.AgentEvent;
import com.tom.rv2ide.ai.agent.AgentPromptBuilder;
import com.tom.rv2ide.ai.agent.AgentRunResult;
import com.tom.rv2ide.ai.agent.AgentSession;
import com.tom.rv2ide.ai.agent.context.RunContextManager;
import com.tom.rv2ide.ai.agent.context.TokenUsageTracker;
import com.tom.rv2ide.ai.agent.conversation.ConversationHistory;
import com.tom.rv2ide.ai.agent.conversation.ConversationLog;
import com.tom.rv2ide.ai.agent.conversation.ConversationStore;
import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.protocol.ModelClient;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.ai.tool.ToolSettingsPort;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次会话运行的执行器：把内核的 {@link AgentSession} 包成「Android 可调用」的形态。
 *
 * <p><b>为什么要有这一层</b>：{@code AgentSession.run(...)} 有 10 个参数、5 个重载，
 * 且要求调用方自己准备：模型配置、系统提示词、历史消息、工具上下文、取消令牌、事件监听、
 * 上下文管理器。这些在本 App 里都是固定组合，散在界面层会让 Activity 变成一个 200 行的
 * 装配工厂——而装配逻辑与界面无关，放这里才能单测。
 *
 * <p><b>事件在哪个线程到达</b>：模型流式回调在 HTTP 线程，因此 {@link Listener#onEvent}
 * **可能在任意线程**被调用。界面层必须自己切主线程（本类不切，也不该切——
 * 那样会让「事件到达顺序」与「渲染顺序」之间多一层不可控的队列）。
 */
public final class ConversationRunner {

  /** 事件与结果的接收者。实现方需自行处理线程切换。 */
  public interface Listener {
    /** 收到一个内核事件。**可能来自非主线程**。 */
    void onEvent(AgentEvent event);

    /** 运行结束（含失败与取消）。**可能来自非主线程**。 */
    void onFinished(AgentRunResult result, Throwable error);
  }

  private final ModelSettings modelSettings;
  private final ToolRegistry toolRegistry;
  private final ToolExecutor toolExecutor;
  private final ConversationStore conversationStore;
  private final File workspace;
  private final ToolSettingsPort toolSettings;
  private final AgentPromptBuilder promptBuilder;

  /**
   * 对话模式提供者：返回 {@code chat / plan / agent / control} 之一。
   *
   * <p>做成回调而不是构造参数：用户可能在设置里改模式，而 runner 是长生命周期对象
   * （跟随 Activity）。每次运行读一次，改动立即对下一次运行生效。
   */
  private final java.util.function.Supplier<String> chatModeId;

  /** 当前运行实例；null 表示空闲。 */
  private volatile RunHandle active;

  /**
   * 上下文用量回调：{@code (已用 token, 上下文窗口大小)}。
   *
   * <p>由宿主注入（顶栏的占用环与 token 统计要用）。**可为 null**——
   * 没有界面消费时不必付出这个开销。可能来自非主线程，实现方自行切线程。
   */
  private volatile UsageListener usageListener;

  /** 上下文用量回调。 */
  public interface UsageListener {
    void onUsage(int usedTokens, int contextWindowSize);
  }

  public void setUsageListener(UsageListener listener) {
    this.usageListener = listener;
  }

  public ConversationRunner(
      ModelSettings modelSettings,
      ToolRegistry toolRegistry,
      ToolExecutor toolExecutor,
      ConversationStore conversationStore,
      File workspace,
      ToolSettingsPort toolSettings) {
    this(
        modelSettings,
        toolRegistry,
        toolExecutor,
        conversationStore,
        workspace,
        toolSettings,
        () -> "agent");
  }

  /**
   * 完整构造（带对话模式）。
   *
   * @param chatModeId 每次运行前调用一次，返回 {@code chat/plan/agent/control}；
   *   null 或未知值按 {@code agent}（内核 {@code ChatMode.DEFAULT}）处理
   */
  public ConversationRunner(
      ModelSettings modelSettings,
      ToolRegistry toolRegistry,
      ToolExecutor toolExecutor,
      ConversationStore conversationStore,
      File workspace,
      ToolSettingsPort toolSettings,
      java.util.function.Supplier<String> chatModeId) {
    this.modelSettings = modelSettings;
    this.toolRegistry = toolRegistry;
    this.toolExecutor = toolExecutor;
    this.conversationStore = conversationStore;
    this.workspace = workspace;
    this.toolSettings = toolSettings == null ? ToolSettingsPort.defaults() : toolSettings;
    this.promptBuilder = new AgentPromptBuilder("Assistant");
    this.chatModeId = chatModeId == null ? () -> "agent" : chatModeId;
  }

  /** 是否正在运行。 */
  public boolean isRunning() {
    RunHandle handle = active;
    return handle != null && handle.running.get();
  }

  /**
   * 发起一次运行（带附件元数据）。
   *
   * @param conversationId 会话 id（决定历史与持久化落点）
   * @param userText 用户输入
   * @param attachmentsJson 多模态输入 JSON（图片）；null 表示纯文本
   * @param attachmentMeta 附件元数据，随用户消息落盘（重开会话时气泡上的缩略图靠它）；
   *     null 或空表示无附件。**与 attachmentsJson 是两件事**：这个只管界面回放，
   *     真正送给模型的是 attachmentsJson。
   * @param listener 事件接收者
   * @return 本次运行的句柄；配置不完整时返回 null（调用方应提示用户去设置）
   */
  public RunHandle start(
      String conversationId,
      String userText,
      String attachmentsJson,
      List<com.tom.rv2ide.ai.protocol.AttachmentMeta> attachmentMeta,
      Listener listener) {
    if (isRunning()) {
      // 并发运行会让同一会话的历史交错写入（两条 run 各自 fold 出不同的起点），
      // 结果是对话内容重复或丢失。宁可拒绝也不静默排队——队列语义由界面层决定。
      return null;
    }
    ModelConfig config = modelSettings.toModelConfig();
    if (config == null) {
      return null;
    }
    RunHandle handle = new RunHandle();
    active = handle;
    Thread worker =
        new Thread(
            () ->
                execute(
                    handle,
                    conversationId,
                    userText,
                    attachmentsJson,
                    attachmentMeta,
                    config,
                    listener),
            "assistant-run");
    worker.setDaemon(true);
    handle.thread = worker;
    worker.start();
    return handle;
  }

  /**
   * 无附件元数据的重载（保留给不需要回放缩略图的调用方）。
   */
  public RunHandle start(
      String conversationId, String userText, String attachmentsJson, Listener listener) {
    return start(conversationId, userText, attachmentsJson, null, listener);
  }

  /**
   * 把待发附件拼成内核要的多模态 JSON。
   *
   * <p><b>为什么不放在界面层拼</b>：格式由 {@code ImageInputPayload.rawInputJson} 定义，
   * 那是内核的线格式；界面层照抄一份就会在两边分别演化。放这里，界面只负责传图片数据。
   *
   * <p><b>只带图片</b>：普通文件（含目录）没有多模态表示，它们通过文本里的路径清单
   * 传达给模型（见界面层的附件文案）。空列表返回 null —— 内核据此走纯文本路径，
   * 而不是发一个「有附件字段但为空」的请求。
   *
   * @param prompt 用户输入（写进 JSON 的 prompt 字段）
   * @param images 图片：mimeType + base64
   * @return 多模态 JSON；没有图片时 null
   */
  public static String buildAttachmentsJson(
      String prompt, List<com.tom.rv2ide.ai.protocol.ImageInputPayload.ImagePart> images) {
    if (images == null || images.isEmpty()) {
      return null;
    }
    try {
      return com.tom.rv2ide.ai.protocol.ImageInputPayload.rawInputJson(prompt, images);
    } catch (org.json.JSONException e) {
      // 构造失败不该让整条消息发不出去：退回纯文本，用户至少还能对话。
      return null;
    }
  }

  /** 取消当前运行。幂等；无运行时不做事。 */
  public void cancel() {
    RunHandle handle = active;
    if (handle != null) {
      handle.cancellation.cancel();
    }
  }

  /** 一次运行的句柄。 */
  public static final class RunHandle {
    final ModelCancellationToken cancellation = new ModelCancellationToken();
    final AtomicBoolean running = new AtomicBoolean(true);
    Thread thread;

    public boolean isRunning() {
      return running.get();
    }

    public void cancel() {
      cancellation.cancel();
    }
  }

  private void execute(
      RunHandle handle,
      String conversationId,
      String userText,
      String attachmentsJson,
      List<com.tom.rv2ide.ai.protocol.AttachmentMeta> attachmentMeta,
      ModelConfig config,
      Listener listener) {
    Throwable error = null;
    AgentRunResult result = null;
    try {
      AgentSession session = new AgentSession(new ModelClient(), toolRegistry, toolExecutor);

      // 历史：从落盘的 JSONL 折叠出模型消息。fold 会清洗「调用与结果不成对」的坏数据
      // （流在参数中途断开留下的残缺 JSON 会让之后每一次请求都 400）。
      List<ModelMessage> history = null;
      try {
        List<ConversationLog.EntryLocation> entries = conversationStore.read(conversationId);
        if (entries != null && !entries.isEmpty()) {
          history = ConversationHistory.fold(entries);
        }
      } catch (Exception ignored) {
        // 历史读失败不该阻断本次运行：当成新会话继续，用户的这句话仍能发出去。
        // 代价是丢失上下文，但比「点了发送什么都不发生」好。
        history = null;
      }

      // 系统提示词：工具清单按「可请求」集合给（延迟加载的工具不在其中，
      // 它们由 tool_search 激活后出现）。
      ModelClient modelClient = new ModelClient();
      boolean nativeTools = modelClient.supportsNativeTools(config);
      String systemPrompt =
          promptBuilder.build(
              workspace == null ? "" : workspace.getAbsolutePath(),
              new java.util.ArrayList<>(toolRegistry.getRequestableTools()),
              nativeTools,
              null,
              com.tom.rv2ide.ai.agent.prompt.ChatMode.fromId(chatModeId.get()),
              new AgentPromptBuilder.ModelInfo(
                  config.getId(),
                  config.getModelId(),
                  config.getProtocolType().getLabel()));

      // 工具上下文：工作区 = 允许访问的文件范围。
      ToolContext toolContext =
          ToolContext.builder()
              .homePath(workspace == null ? "" : workspace.getAbsolutePath())
              .openProjectPath(workspace == null ? "" : workspace.getAbsolutePath())
              .conversationId(conversationId)
              .settings(toolSettings)
              // 工具层的直连进度回调。内核已经经 AgentEvent.PROGRESS 把进度送到界面，
              // 这里不需要再做一次——留空实现而不是传 null，是为了让「工具调了进度回调」
              // 这条路径有确定行为（null 时工具会自己判空，但那样每个工具都要判一次）。
              .progressListener(
                  new ToolContext.ProgressListener() {
                    @Override
                    public void onProgress(String message) {}

                    @Override
                    public void onProgress(String callId, String message) {}
                  })
              .build();

      // 上下文管理器：裁剪在循环内做（消息随轮次增长），压缩在入口做。
      //
      // 顺带把用量回调接出来：TokenUsageTracker 一直都在统计（compaction 的触发条件
      // 就靠它），但此前没传 usageListener，于是顶栏的占用环与 token 数恒为 0——
      // 数据有，只是没人取。
      int contextSize = config.getContextSize();
      RunContextManager contextManager =
          contextSize > 0
              ? new RunContextManager(
                  new TokenUsageTracker(contextSize),
                  estimateOverhead(systemPrompt),
                  (used, total) -> {
                    UsageListener sink = usageListener;
                    if (sink != null) {
                      sink.onUsage(used, total);
                    }
                  })
              : null;

      // 落盘包装：**所有事件先写 JSONL 再转发给界面**。
      //
      // 不做这一步的后果是会话文件只有 meta + title 两行，对话内容全丢——
      // 实测就是如此（引擎跑通了、界面也显示了，但重开会话是空的）。
      // 事件流的顺序不能反：先落盘再转发，才能保证「界面看到的内容」一定能被回放出来。
      ConversationPersistingListener persisting =
          new ConversationPersistingListener(
              conversationId,
              userText,
              listener::onEvent,
              // 附件元数据随用户消息一起落盘：不落的话重开会话时气泡上的缩略图全没了
              // （模型那边不受影响——图片走 attachmentsJson 的 base64，与这里无关）。
              attachmentMeta,
              conversationStore,
              null);

      result =
          session.run(
              config,
              systemPrompt,
              userText,
              history,
              toolContext,
              handle.cancellation,
              persisting,
              contextManager,
              attachmentsJson,
              null);
    } catch (Throwable t) {
      // 内核内部已经处理了 OOM 与模型异常（转成 FAILED 事件）；能到这里的是
      // 装配期异常（配置非法、工作区不可写等）。如实上报，不吞。
      error = t;
    } finally {
      handle.running.set(false);
      active = null;
      listener.onFinished(result, error);
    }
  }

  /**
   * 估算系统提示词 + 工具定义的 token 成本。
   *
   * <p>用 4 字符/token 的粗估：中文实际约 1.5-2 字符/token，英文约 4。粗估偏大是
   * 安全方向（预留更多空间），偏小会让裁剪不足而在发送时超限。
   */
  private static int estimateOverhead(String systemPrompt) {
    return systemPrompt == null ? 0 : systemPrompt.length() / 3;
  }
}
