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

import android.content.Context;
import com.tom.rv2ide.ai.agent.AgentEvent;
import com.tom.rv2ide.ai.agent.AgentPromptBuilder;
import com.tom.rv2ide.ai.agent.AgentRunResult;
import com.tom.rv2ide.ai.agent.AgentSession;
import com.tom.rv2ide.ai.agent.conversation.ConversationEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationHistory;
import com.tom.rv2ide.ai.agent.conversation.ConversationLog;
import com.tom.rv2ide.ai.agent.conversation.ConversationStore;
import com.tom.rv2ide.ai.agent.conversation.ConversationSummary;
import com.tom.rv2ide.ai.agent.conversation.FileConversationStore;
import com.tom.rv2ide.ai.agent.conversation.SessionMetaEntry;
import com.tom.rv2ide.ai.agent.conversation.ToolResultEntry;
import com.tom.rv2ide.ai.agent.conversation.UserMessageEntry;
import com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry;
import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.protocol.ModelClient;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.tool.DiffStore;
import com.tom.rv2ide.ai.tool.FileDeleteTool;
import com.tom.rv2ide.ai.tool.FileEditTool;
import com.tom.rv2ide.ai.tool.FileReadTool;
import com.tom.rv2ide.ai.tool.FileWriteTool;
import com.tom.rv2ide.ai.tool.GlobTool;
import com.tom.rv2ide.ai.tool.ListDirectoryTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolPermissionService;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ShellExecuteTool;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.artificial.agent.tool.GradleBuildTool;
import com.tom.rv2ide.artificial.agent.tool.InstallApkTool;
import com.tom.rv2ide.artificial.agent.tool.LaunchAppTool;
import com.tom.rv2ide.artificial.agent.tool.LogcatReadTool;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 助手在 app 层的入口：装配工具、模型配置与循环，对外暴露一次请求的执行。
 *
 * <p>职责边界：
 * <ul>
 *   <li>负责把纯 Java 的工具/协议/循环栈与 Android 世界接起来（工作区路径、偏好设置）
 *   <li>不负责 UI —— 通过 {@link AgentEvent.Listener} 把过程事件交给调用方渲染
 *   <li>不持有 Activity，可安全地在后台线程运行
 * </ul>
 *
 * <p>会话持久化：每轮结束后把助手消息与工具结果追加到 JSONL 日志，下次请求折叠为
 * 历史传入循环。落盘在监听器里完成，循环本身不知道存储的存在。
 */
public final class AgentOrchestrator {

  /** 单次对话允许的工具调用次数上限。防止模型陷入循环消耗额度。 */
  private static final int DEFAULT_TOOL_CALL_LIMIT = 100;

  private final Context appContext;
  private final AgentToolSettings settings;
  private final DiffStore diffStore;
  private final ConversationStore conversationStore;
  private final AgentPromptBuilder promptBuilder = new AgentPromptBuilder();

  /** 当前工作区根目录，由 {@link #setWorkspace} 设置。 */
  private File workspace;

  /** 当前会话 id；为 null 时 {@link #run} 会新建一个。 */
  private String activeConversationId;

  private volatile ModelCancellationToken activeCancellation;

  public AgentOrchestrator(Context context, DiffStore diffStore) {
    this(context, diffStore, new FileConversationStore(defaultConversationDir(context)));
  }

  public AgentOrchestrator(Context context, DiffStore diffStore, ConversationStore conversationStore) {
    this.appContext = context.getApplicationContext();
    this.settings = new AgentToolSettings(appContext);
    this.diffStore = diffStore;
    this.conversationStore = conversationStore;
  }

  /** 会话日志目录：{@code filesDir/ai/conversations}。 */
  private static File defaultConversationDir(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "conversations");
  }

  public ConversationStore getConversationStore() {
    return conversationStore;
  }

  /** 列出全部会话摘要（列表页用）。 */
  public List<ConversationSummary> listConversations() throws IOException {
    return conversationStore.list();
  }

  /** 切换到既有会话；之后的请求会在其历史上续接。 */
  public void openConversation(String conversationId) {
    this.activeConversationId = conversationId;
  }

  /** @return 当前会话 id，可能为 null（尚未开始任何会话）。 */
  public String getActiveConversationId() {
    return activeConversationId;
  }

  /**
   * 新建会话并切过去。
   *
   * @return 新会话摘要
   */
  public ConversationSummary newConversation() throws IOException {
    ConversationSummary summary =
        conversationStore.create(
            null,
            workspace == null ? "" : workspace.getAbsolutePath(),
            "",
            settings.getPermissionMode());
    activeConversationId = summary.getId();
    return summary;
  }

  /** 重命名会话。实现为追加标题条目，历史不变。 */
  public void renameConversation(String conversationId, String title) throws IOException {
    conversationStore.rename(conversationId, title);
  }

  /** 删除会话；若删的是当前会话则清空当前指向。 */
  public void deleteConversation(String conversationId) throws IOException {
    conversationStore.delete(conversationId);
    if (conversationId != null && conversationId.equals(activeConversationId)) {
      activeConversationId = null;
    }
  }

  /** 设置工作区根目录。工具只能在此目录内操作。 */
  public void setWorkspace(File workspace) {
    this.workspace = workspace;
  }

  public File getWorkspace() {
    return workspace;
  }

  public AgentToolSettings getSettings() {
    return settings;
  }

  /** 构建工具注册表：文件工具 + shell 执行 + 运行测试闭环四件套。 */
  public ToolRegistry buildRegistry() {
    ToolRegistry registry = new ToolRegistry();

    // 文件操作
    registry.register(new FileReadTool());
    registry.register(new FileWriteTool());
    registry.register(new FileEditTool());
    registry.register(new FileDeleteTool());
    registry.register(new GlobTool());
    registry.register(new ListDirectoryTool());

    // shell 执行（后端由配置决定：Termux 或 Shizuku）
    registry.register(new ShellExecuteTool(buildShellBackends()));

    // 运行测试闭环：构建 → 安装 → 启动 → 读日志
    registry.register(new GradleBuildTool(this::lookupBuildService));
    registry.register(new InstallApkTool(appContext));
    registry.register(new LaunchAppTool(appContext));
    registry.register(new LogcatReadTool(appContext));

    return registry;
  }

  /**
   * 装配 shell 后端注册表。
   *
   * <p>注册顺序决定回退优先级：Termux（无 adb 权限但总是可用）→ Shizuku（adb 权限，
   * 需设备端安装并授权）。用户选择的 {@code shell_backend} 优先；未选中或不可用时
   * 由注册表按顺序回退，并把实际使用的后端与回退原因如实报告给模型。
   */
  public ShellBackendRegistry buildShellBackends() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new TermuxShellBackend(appContext));
    registry.register(new ShizukuShellBackend(appContext));
    registry.setActiveId(settings.getShellBackendId());
    return registry;
  }

  /** 通过 Lookup 取构建服务；不可用时返回 null。 */
  private com.tom.rv2ide.projects.builder.BuildService lookupBuildService() {
    try {
      return com.tom.rv2ide.lookup.Lookup.getDefault()
          .lookup(com.tom.rv2ide.projects.builder.BuildService.KEY_BUILD_SERVICE);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * 执行一次用户请求。
   *
   * @param providerId 服务商标识（openai / deepseek / grok / claude / localllm）
   * @param modelId 模型名
   * @param userRequest 用户请求文本
   * @param customBaseUrl 本地模型的自定义 baseUrl，其它服务商传 null
   * @param listener 过程事件接收者，可为 null
   * @return 执行结果；配置缺失或工作区未设置时返回失败结果
   */
  public AgentRunResult run(
      String providerId,
      String modelId,
      String userRequest,
      String customBaseUrl,
      AgentEvent.Listener listener) {

    if (workspace == null) {
      return new AgentRunResult("未设置工作区，无法执行。", 0, 0, true);
    }
    if (!workspace.exists()) {
      return new AgentRunResult("工作区不存在: " + workspace, 0, 0, true);
    }
    settings.beginRun();

    AgentModelConfigs.ProviderEndpoint endpoint =
        AgentModelConfigs.endpointFor(providerId, customBaseUrl);
    if (endpoint == null) {
      return new AgentRunResult(
          "服务商 " + providerId + " 未配置有效的 API 密钥。", 0, 0, true);
    }

    ModelConfig config =
        AgentModelConfigs.build(endpoint, modelId, DEFAULT_TOOL_CALL_LIMIT);

    ToolRegistry registry = buildRegistry();
    ToolPermissionService permissions = new ToolPermissionService(settings, registry);
    ToolExecutor executor =
        new ToolExecutor(registry, permissions, diffStore == null ? null : newDiffRecorder());

    List<ToolInfo> tools = new ArrayList<>(registry.getAll());
    // 协议支持原生工具调用时不注入 XML 兜底格式，否则会诱导模型改用文本调用。
    ModelClient modelClient = new ModelClient();
    boolean nativeTools = modelClient.supportsNativeTools(config);
    String systemPrompt = promptBuilder.build(workspace.getAbsolutePath(), tools, nativeTools);

    ToolContext toolContext =
        ToolContext.builder()
            .homePath(workspace.getAbsolutePath())
            .settings(settings)
            .build();

    // 确保有会话：无则新建，使消息有落盘之处。
    String conversationId = ensureConversation();
    List<ModelMessage> history = loadHistory(conversationId);

    // 落盘与 UI 渲染共用同一事件流：先持久化（不受 UI 影响），再转发给调用方。
    AgentEvent.Listener persistingListener =
        new PersistingListener(conversationId, userRequest, listener);

    ModelCancellationToken cancellation = new ModelCancellationToken();
    activeCancellation = cancellation;

    try {
      AgentSession session = new AgentSession(modelClient, registry, executor);
      return session.run(
          config, systemPrompt, userRequest, history, toolContext, cancellation, persistingListener);
    } finally {
      activeCancellation = null;
    }
  }

  /** @return 当前会话 id；若尚无会话则新建一个。 */
  private String ensureConversation() {
    String existing = activeConversationId;
    if (existing != null && conversationStore.exists(existing)) {
      return existing;
    }
    try {
      ConversationSummary created =
          conversationStore.create(
              null,
              workspace.getAbsolutePath(),
              "",
              settings.getPermissionMode());
      activeConversationId = created.getId();
      return created.getId();
    } catch (IOException e) {
      // 落盘失败不应阻断对话本身——退化为无历史的内存会话。
      return "";
    }
  }

  /**
   * 折叠既有会话为可续接的历史。
   *
   * <p>读取或折叠失败时返回空历史：宁可丢掉上下文，也不该让用户发不出消息。
   */
  private List<ModelMessage> loadHistory(String conversationId) {
    if (conversationId == null || conversationId.isEmpty()) {
      return new ArrayList<>();
    }
    try {
      return ConversationHistory.fold(conversationStore.read(conversationId));
    } catch (IOException | RuntimeException e) {
      return new ArrayList<>();
    }
  }

  /**
   * 把事件流同时写入会话日志。
   *
   * <p>用户消息在会话开始时就落盘（而非结束时），这样即使进程被杀，用户输入也不会丢。
   * 助手消息与工具结果在各自完成时落盘。
   */
  private final class PersistingListener implements AgentEvent.Listener {

    private final String conversationId;
    private final AgentEvent.Listener downstream;

    PersistingListener(String conversationId, String userRequest, AgentEvent.Listener downstream) {
      this.conversationId = conversationId;
      this.downstream = downstream;
      appendEntry(UserMessageEntry.create(null, System.currentTimeMillis(), userRequest));
    }

    @Override
    public void onEvent(AgentEvent event) {
      persist(event);
      if (downstream != null) {
        downstream.onEvent(event);
      }
    }

    private void persist(AgentEvent event) {
      if (event == null) {
        return;
      }
      switch (event.getType()) {
        case TURN_FINISHED:
          appendEntry(
              AssistantMessageEntry.create(
                  null,
                  System.currentTimeMillis(),
                  event.getMessage(),
                  "",
                  event.getToolCalls()));
          break;
        case TOOL_FINISHED:
          if (event.getToolResult() != null) {
            appendEntry(
                ToolResultEntry.create(null, System.currentTimeMillis(), event.getToolResult()));
          }
          break;
        default:
          break;
      }
    }

    private void appendEntry(ConversationEntry entry) {
      if (conversationId == null || conversationId.isEmpty()) {
        return;
      }
      try {
        conversationStore.append(conversationId, entry);
      } catch (IOException | RuntimeException e) {
        // 单条落盘失败不应中断对话；内存中的会话仍在继续。
      }
    }
  }

  /** 请求取消当前正在执行的循环。 */
  public void cancel() {
    ModelCancellationToken cancellation = activeCancellation;
    if (cancellation != null) {
      cancellation.cancel();
    }
  }

  private com.tom.rv2ide.ai.tool.DiffRecorder newDiffRecorder() {
    return new com.tom.rv2ide.ai.tool.DiffRecorder(diffStore);
  }
}
