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
import com.tom.rv2ide.ai.agent.context.ContextCompactor;
import com.tom.rv2ide.ai.agent.context.RunContextManager;
import com.tom.rv2ide.ai.agent.context.TokenEstimator;
import com.tom.rv2ide.ai.agent.context.TokenUsageTracker;
import com.tom.rv2ide.ai.agent.conversation.CompactionEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationCompaction;
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
import com.tom.rv2ide.ai.protocol.ModelCompletionResponse;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.DiffStore;
import com.tom.rv2ide.ai.tool.FileDeleteTool;
import com.tom.rv2ide.ai.tool.FileEditTool;
import com.tom.rv2ide.ai.tool.FileReadTool;
import com.tom.rv2ide.ai.tool.FileWriteTool;
import com.tom.rv2ide.ai.tool.GlobTool;
import com.tom.rv2ide.ai.tool.ListDirectoryTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.TodoStateStore;
import com.tom.rv2ide.ai.tool.TodoUpdateTool;
import com.tom.rv2ide.ai.tool.WebFetchTool;
import com.tom.rv2ide.ai.tool.WebSearchTool;
import com.tom.rv2ide.ai.tool.RssSearchProvider;
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

  /**
   * 每次运行最多注入多少条记忆。
   *
   * <p>取 8：记忆的价值在于「精准补充」，注入过多会占满上下文并稀释当前任务的焦点。
   * 宁少勿滥——检索不到相关记忆时不如不注入。
   */
  private static final int MAX_MEMORIES_PER_RUN = 8;

  private final Context appContext;
  private final AgentToolSettings settings;
  private final DiffStore diffStore;
  private final ConversationStore conversationStore;

  /**
   * 提示词构建器。
   *
   * <p>持有模板存储（而非每次新建），使用户在设置里改完提示词后**下一次运行即生效**——
   * 不必重启应用，也不必重新编译。
   */
  private final AgentPromptBuilder promptBuilder;

  /** 对话模式存储，跨运行保留。 */
  private final PrefsChatModeStore chatModeStore;

  /**
   * 待办列表存储，跨运行保留。
   *
   * <p>必须持久化：待办是模型「记住自己做到哪一步」的依据，只在内存里的话进程被回收后
   * 模型会从头再来一遍，用户看到的是重复劳动。
   */
  private final TodoStateStore todoStore;

  /**
   * 长期记忆存储，跨会话保留。
   *
   * <p>与待办的区别：待办是本次任务的进度（任务结束即无用），记忆是跨任务的知识
   * （项目约定、用户偏好、环境特性）。
   */
  private final com.tom.rv2ide.ai.tool.memory.MemoryStore memoryStore;

  /** 当前工作区根目录，由 {@link #setWorkspace} 设置。 */
  private File workspace;

  /** 当前会话 id；为 null 时 {@link #run} 会新建一个。 */
  private String activeConversationId;

  private volatile ModelCancellationToken activeCancellation;

  public AgentOrchestrator(Context context, DiffStore diffStore) {
    this(context, diffStore, new FileConversationStore(defaultConversationDir(context)));
  }

  /** 用默认存储（持久化会话 + 持久化 diff）构造。 */
  public AgentOrchestrator(Context context) {
    this(context, defaultDiffStore(context));
  }

  public AgentOrchestrator(Context context, DiffStore diffStore, ConversationStore conversationStore) {
    this.appContext = context.getApplicationContext();
    this.settings = new AgentToolSettings(appContext);
    this.diffStore = diffStore;
    this.conversationStore = conversationStore;
    this.todoStore = new com.tom.rv2ide.ai.tool.FileTodoStateStore(defaultTodoFile(appContext));
    this.memoryStore = new com.tom.rv2ide.ai.tool.memory.MemoryStore(defaultMemoryFile(appContext));
    this.chatModeStore = new PrefsChatModeStore(appContext);
    this.promptBuilder = new AgentPromptBuilder("ACS AI Agent", new PrefsPromptTemplateStore(appContext));
  }

  /** 记忆文件：{@code filesDir/ai/memories.json}。 */
  private static File defaultMemoryFile(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "memories.json");
  }

  /** 长期记忆存储，供设置界面查看与删除。 */
  public com.tom.rv2ide.ai.tool.memory.MemoryStore getMemoryStore() {
    return memoryStore;
  }

  /** 对话模式存储，供设置界面读写。 */
  public PrefsChatModeStore getChatModeStore() {
    return chatModeStore;
  }

  /** 待办文件：{@code filesDir/ai/todos.json}。 */
  private static File defaultTodoFile(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "todos.json");
  }

  /** 会话日志目录：{@code filesDir/ai/conversations}。 */
  private static File defaultConversationDir(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "conversations");
  }

  /**
   * 默认的 diff 存储：{@code filesDir/ai/diffs.jsonl}，跨进程存活。
   *
   * <p>刻意用文件实现而非 {@link com.tom.rv2ide.ai.tool.InMemoryDiffStore}：后者的记录
   * 只活在内存里，用户关掉应用再打开就无法回滚之前的改动。回滚的价值恰恰在于「事后反悔」，
   * 而事后往往就是下一次打开应用。
   */
  public static DiffStore defaultDiffStore(Context context) {
    return new com.tom.rv2ide.ai.tool.FileDiffStore(
        new File(new File(context.getFilesDir(), "ai"), "diffs.jsonl"));
  }

  /** 获取 diff 存储，供 UI 层展示改动与执行回滚。 */
  public DiffStore getDiffStore() {
    return diffStore;
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

    // 任务计划：把模型的计划外化成可见状态，使长任务不丢进度。
    registry.register(new TodoUpdateTool(todoStore));

    // 长期记忆：跨会话保留的项目约定与用户偏好。
    registry.register(new com.tom.rv2ide.ai.tool.memory.MemoryUpdateTool(memoryStore));

    // 网络：先搜索定位页面，再抓取正文。
    AppHttpPort http = new AppHttpPort();
    registry.register(new WebFetchTool(http));
    registry.register(new WebSearchTool(new RssSearchProvider(http)));

    // 外部 MCP server 提供的工具。
    registerMcpTools(registry, http);

    return registry;
  }

  /**
   * 把已配置的 MCP server 的工具注册进来。
   *
   * <p><b>失败一律跳过</b>：MCP 是可选扩展，某个 server 连不上不该让整个 AI 功能不可用。
   * 拉取失败时记一条错误日志（供排查），但不阻断本次运行。
   *
   * <p><b>每次运行重新拉取</b>：工具列表可能随 server 升级而变化，缓存会让用户改了
   * server 后看不到新工具。代价是每次运行多一次网络往返——但只在配置了 server 时才发生，
   * 且 {@link McpToolInfo} 的拉取很轻。
   */
  private void registerMcpTools(ToolRegistry registry, AppHttpPort http) {
    java.util.List<McpServers.Server> servers;
    try {
      servers = new McpServers(appContext).enabled();
    } catch (RuntimeException e) {
      return;
    }
    if (servers.isEmpty()) {
      return;
    }

    java.util.Set<String> usedNames = new java.util.HashSet<>();
    for (ToolInfo existing : registry.getAll()) {
      usedNames.add(existing.getName());
    }

    for (McpServers.Server server : servers) {
      try {
        com.tom.rv2ide.ai.tool.mcp.McpClient client =
            new com.tom.rv2ide.ai.tool.mcp.McpClient(http, server.url);
        for (com.tom.rv2ide.ai.tool.mcp.McpToolInfo info : client.listTools()) {
          com.tom.rv2ide.ai.tool.mcp.McpToolAdapter adapter =
              new com.tom.rv2ide.ai.tool.mcp.McpToolAdapter(client, info, server.displayName());
          // 名字冲突时跳过而不是覆盖：覆盖会让内置工具静默消失，比缺少一个远程工具更糟。
          if (!usedNames.add(adapter.getName())) {
            continue;
          }
          registry.register(adapter);
        }
      } catch (Exception e) {
        com.tom.rv2ide.ai.tool.api.ErrorLog.record(
            "mcp", "拉取 MCP 工具列表失败: " + server.url, e, null);
      }
    }
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

    // 子 agent：把「需要读很多文件」的调查隔离到独立上下文里。
    //
    // 注册在这里而不是 buildRegistry 里，因为它需要本次运行的 endpoint / 模型 / 取消令牌。
    // 取消令牌必须在 AgentSession 之前创建：子 agent 要挂在它上面才能随父级一起停——
    // 否则用户点了取消，主循环停了而子 agent 仍在烧额度。
    ModelCancellationToken cancellation = new ModelCancellationToken();
    activeCancellation = cancellation;

    registry.register(
        new com.tom.rv2ide.ai.tool.AgentTool(
            new SubAgentRunnerImpl(
                endpoint,
                modelId,
                workspace.getAbsolutePath(),
                settings,
                cancellation,
                diffStore)));

    List<ToolInfo> tools = new ArrayList<>(registry.getAll());
    // 协议支持原生工具调用时不注入 XML 兜底格式，否则会诱导模型改用文本调用。
    ModelClient modelClient = new ModelClient();
    boolean nativeTools = modelClient.supportsNativeTools(config);
    // 对话模式决定提示词里给出多少行动授权。
    com.tom.rv2ide.ai.agent.prompt.ChatMode chatMode = chatModeStore.get();

    // 待办状态注入提示词：只存不读等于没记——模型必须在每轮都看到「我做到哪了」，
    // 才能在几十轮工具调用之后不丢失进度。
    // 模式与模型信息同样注入，使模板能按模式/模型差异化措辞。
    String systemPrompt =
        promptBuilder.build(
            workspace.getAbsolutePath(),
            tools,
            nativeTools,
            todoStore.renderForPrompt(),
            chatMode,
            new AgentPromptBuilder.ModelInfo(
                providerId, modelId, config.getProtocolType().getLabel()));

    // 相关记忆追加在系统提示词之后。
    // 用本次用户请求作为检索词：记忆条数可能上百，全量注入会占满上下文并稀释重点，
    // 而「与当前任务相关」正是检索能提供的价值。
    String relevantMemories =
        memoryStore.renderForPrompt(userRequest, MAX_MEMORIES_PER_RUN);
    if (!relevantMemories.isEmpty()) {
      systemPrompt =
          systemPrompt + "\n\n[ 已知信息 ]\n" + relevantMemories;
    }

    ToolContext toolContext =
        ToolContext.builder()
            .homePath(workspace.getAbsolutePath())
            .settings(settings)
            .build();

    // 确保有会话：无则新建，使消息有落盘之处。
    String conversationId = ensureConversation();
    List<ConversationLog.EntryLocation> entries = loadEntries(conversationId);
    List<ModelMessage> history =
        entries.isEmpty() ? new ArrayList<>() : ConversationHistory.fold(entries);

    // 落盘与 UI 渲染共用同一事件流：先持久化（不受 UI 影响），再转发给调用方。
    PersistingListener persistingListener =
        new PersistingListener(conversationId, userRequest, listener);

    // 上下文管理（P0-2）：预算里必须扣掉系统提示词与工具定义——它们不占历史预算
    // 但确实占窗口；协议不支持原生工具时工具定义不随请求发出，也就不该扣。
    int overhead =
        TokenEstimator.estimate(systemPrompt)
            + (nativeTools ? TokenEstimator.estimateTools(tools) : 0);
    RunContextManager contextManager =
        new RunContextManager(
            new TokenUsageTracker(ConversationCompaction.contextSizeOf(config)), overhead);

    // 入口处先做一次压缩：把"每次运行都要丢弃同一段早期历史"变成"只摘要一次并落盘"。
    // 这一步在循环之外，因为压缩结果需要持久化，而循环不接触存储。
    maybeCompact(persistingListener, entries, history, config, overhead);

    try {
      AgentSession session = new AgentSession(modelClient, registry, executor);
      return session.run(
          config,
          systemPrompt,
          userRequest,
          history,
          toolContext,
          cancellation,
          persistingListener,
          contextManager);
    } finally {
      activeCancellation = null;
    }
  }

  /**
   * 若历史已超硬阈值，摘要最旧的一段并追加压缩条目。
   *
   * <p>摘要结果**必须落盘**：{@link ConversationHistory#fold} 靠 {@code CompactionEntry}
   * 跳过被覆盖的条目，只把摘要放进历史。不落盘的话下次运行会重新摘要同一段——
   * 每次都要付一次模型调用，且摘要会越来越长。
   *
   * <p>失败一律静默降级为不压缩：压缩是优化，不能因为它失败让用户发不出消息。
   */
  private void maybeCompact(
      PersistingListener persistingListener,
      List<ConversationLog.EntryLocation> entries,
      List<ModelMessage> history,
      ModelConfig config,
      int overhead) {
    if (entries == null || entries.isEmpty() || history.isEmpty()) {
      return;
    }
    TokenUsageTracker tracker =
        new TokenUsageTracker(ConversationCompaction.contextSizeOf(config));
    tracker.record(overhead + TokenEstimator.estimate(history), 0);
    if (!tracker.shouldHardCompact()) {
      return;
    }

    int budget = ConversationCompaction.historyBudget(config, overhead);
    ConversationCompaction.Selection selection = ConversationCompaction.select(entries, budget);
    if (selection.isEmpty()) {
      return;
    }

    ContextCompactor.Summarizer summarizer = buildSummarizer(config);
    if (summarizer == null) {
      return;
    }
    String summary = ContextCompactor.compact(selection.getMessages(), summarizer);
    if (summary == null || summary.trim().isEmpty()) {
      return;
    }

    persistingListener.appendEntry(
        CompactionEntry.create(
            null, System.currentTimeMillis(), summary.trim(), selection.getUpToOrdinal()));

    // 压缩是有损的：必须让用户看到"模型已经看不到某些早期对话了"，否则会困惑于
    // 模型为何遗忘先前说过的要求。走同一事件流即可——persist() 对 CONTEXT_COMPACTED
    // 无动作（CompactionEntry 已是持久化记录），只有下游 UI 需要看到它。
    persistingListener.onEvent(
        AgentEvent.contextCompacted(
            selection.getMessages().size(), TokenEstimator.estimate(summary)));
  }

  /**
   * 摘要用的模型调用。
   *
   * <p>用独立的压缩模型（若配置了且非自动）而不是主模型：摘要任务简单，用小模型省钱；
   * 但同一会话的摘要风格应稳定，因此只在配置明确指定时才换模型。
   */
  private ContextCompactor.Summarizer buildSummarizer(ModelConfig config) {
    if (config == null) {
      return null;
    }
    final ModelClient client = new ModelClient();
    final ModelConfig summarizerConfig = config.withModelId(config.getEffectiveCompressionModelId());
    return prompt -> {
      List<ModelMessage> messages = new ArrayList<>();
      messages.add(new UserModelMessage(prompt));
      ModelCompletionResponse response =
          client.complete(summarizerConfig, messages);
      return response == null ? null : response.getText();
    };
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
   * 读取既有会话的全部条目。
   *
   * <p>读取失败时返回空列表：宁可丢掉上下文，也不该让用户发不出消息。
   *
   * <p>返回**条目**而非折叠后的消息：压缩需要按序号决定边界，而序号只存在于条目层。
   */
  private List<ConversationLog.EntryLocation> loadEntries(String conversationId) {
    if (conversationId == null || conversationId.isEmpty()) {
      return new ArrayList<>();
    }
    try {
      return conversationStore.read(conversationId);
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
