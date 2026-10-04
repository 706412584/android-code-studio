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
import com.tom.rv2ide.ai.tool.api.ErrorLog;
import com.tom.rv2ide.artificial.agents.Agents;
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
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ExecutorToolInvoker;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolInvoker;
import com.tom.rv2ide.ai.tool.ToolInvokerAware;
import com.tom.rv2ide.ai.tool.ToolPermissionService;
import com.tom.rv2ide.ai.tool.HttpRequestTool;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ShellExecuteTool;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.artificial.agent.tool.GradleBuildTool;
import com.tom.rv2ide.artificial.secrets.ApiKey;
import com.tom.rv2ide.artificial.agent.tool.InstallApkTool;
import com.tom.rv2ide.artificial.agent.tool.LaunchAppTool;
import com.tom.rv2ide.artificial.agent.tool.LogcatReadTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneActionCaptureTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneBaselineTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneClearDataTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneClickTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneClickViewTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneCurrentActivityTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneGlobalActionTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneInputTextTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneLongPressTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneMemInfoTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneScreenshotCompareTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneScreenshotTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneSwipeTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneTestScenarioTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneViewHierarchyTool;
import com.tom.rv2ide.artificial.agent.tool.PhoneWaitForTool;
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
   * 用户自定义 agent 存储。
   *
   * <p>每次运行读取：用户改完定义后下一次运行即生效，不必重启。
   */
  private final com.tom.rv2ide.ai.agent.command.CustomAgentStore customAgentStore;

  /**
   * 内置子 agent 的用户覆盖存储。
   *
   * <p>与 {@link #customAgentStore} 并列：自定义 agent 由用户从零定义；内置 agent 随应用
   * 提供（审查/探索/定位/写测试/写文档），用户可改提示词与工具集并一键恢复默认。
   */
  private final com.tom.rv2ide.ai.agent.builtin.BuiltinAgentStore builtinAgentStore;

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

  /**
   * skill 注册表。
   *
   * <p>构造时加载一次：skill 是磁盘上的静态文档，运行期间不会变化；每次运行重新扫描
   * 目录既无必要（用户改完文件重启应用即可）也浪费 I/O。
   */
  private final com.tom.rv2ide.ai.tool.skill.SkillRegistry skillRegistry;

  /** 当前工作区根目录，由 {@link #setWorkspace} 设置。 */
  private File workspace;

  /** 当前会话 id；为 null 时 {@link #run} 会新建一个。 */
  private String activeConversationId;

  /**
   * 正在运行的会话的取消令牌，按会话 id 隔离。
   *
   * <p><b>为什么是 Map 而不是单个字段</b>：多会话可以同时跑（每个会话一个 job），
   * 单个 {@code activeCancellation} 会被后启动的运行覆盖，用户点「停止」时会杀掉
   * 错误的会话。按会话隔离后，{@link #cancel(String)} 精确命中目标。
   */
  private final java.util.Map<String, ModelCancellationToken> activeCancellations =
      new java.util.concurrent.ConcurrentHashMap<>();

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
    // 先把内置 skill 播种到用户目录，再加载——顺序不能反，否则首次启动时
    // 注册表扫到的是空目录，内置 skill 要等下次构造才出现。
    File skillsDir = defaultSkillsDir(appContext);
    seedBuiltinSkills(appContext, skillsDir);
    this.skillRegistry = com.tom.rv2ide.ai.tool.skill.SkillRegistry.load(skillsDir);
    this.chatModeStore = new PrefsChatModeStore(appContext);
    this.customAgentStore =
        new com.tom.rv2ide.ai.agent.command.CustomAgentStore(defaultCustomAgentsFile(appContext));
    this.builtinAgentStore =
        new com.tom.rv2ide.ai.agent.builtin.BuiltinAgentStore(defaultBuiltinAgentsFile(appContext));
    this.promptBuilder = new AgentPromptBuilder("ACS AI Agent", new PrefsPromptTemplateStore(appContext));
  }

  /** 自定义 agent 文件：{@code filesDir/ai/custom_agents.json}。 */
  private static File defaultCustomAgentsFile(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "custom_agents.json");
  }

  /** 内置 agent 覆盖文件：{@code filesDir/ai/builtin_agents.json}（只存与默认不同的覆盖）。 */
  private static File defaultBuiltinAgentsFile(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "builtin_agents.json");
  }

  /** 自定义 agent 存储，供设置界面管理。 */
  public com.tom.rv2ide.ai.agent.command.CustomAgentStore getCustomAgentStore() {
    return customAgentStore;
  }

  /** 内置 agent 存储（默认值 + 用户覆盖），供设置界面管理。 */
  public com.tom.rv2ide.ai.agent.builtin.BuiltinAgentStore getBuiltinAgentStore() {
    return builtinAgentStore;
  }

  /** 记忆文件：{@code filesDir/ai/memories.json}。 */
  private static File defaultMemoryFile(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "memories.json");
  }

  /** skill 目录：{@code filesDir/ai/skills}。 */
  private static File defaultSkillsDir(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "skills");
  }

  /**
   * 把内置 skill 播种到用户 skill 目录。
   *
   * <p><b>为什么用「只补缺失的」而不是「每次覆盖」</b>：用户与 AI 都可能改这些文件
   * （修正一处过时的说明、按自己项目补充细节）。每次覆盖会把他们的改动冲掉。反过来，
   * 只补缺失的意味着**升级后新增的内置 skill 会装上，但已有同名文件不会被更新**——
   * 这是刻意的取舍：宁可让用户手工删掉旧文件以获取新版，也不能默默丢弃他的编辑。
   *
   * <p>失败一律忽略：播种是锦上添花，不该因为它让 AI 功能起不来。
   */
  private static void seedBuiltinSkills(Context context, File skillsDir) {
    try {
      String[] entries = context.getAssets().list("skills");
      if (entries == null || entries.length == 0) {
        return;
      }
      if (!skillsDir.isDirectory() && !skillsDir.mkdirs()) {
        return;
      }
      for (String name : entries) {
        File targetDir = new File(skillsDir, name);
        File target = new File(targetDir, "SKILL.md");
        // 已存在就跳过——见上面的取舍说明。
        if (target.isFile()) {
          continue;
        }
        String assetPath = "skills/" + name + "/SKILL.md";
        if (!assetExists(context, assetPath)) {
          continue;
        }
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
          continue;
        }
        try (java.io.InputStream in = context.getAssets().open(assetPath);
            java.io.OutputStream out = new java.io.FileOutputStream(target)) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
          }
        }
      }
    } catch (Exception e) {
      com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "播种内置 skill 失败", e, null);
    }
  }

  /** assets 里是否存在该条目（assets 的 open 会抛异常，用它判断）。 */
  private static boolean assetExists(Context context, String path) {
    try (java.io.InputStream in = context.getAssets().open(path)) {
      return in != null;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  /** skill 注册表，供设置界面查看。 */
  public com.tom.rv2ide.ai.tool.skill.SkillRegistry getSkillRegistry() {
    return skillRegistry;
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
    // 立刻持久化：用户从历史列表选了某条，下次重开应用就该停在这条。
    persistActiveConversation();
  }

  /**
   * 读取某个会话的历史消息，供界面回放。
   *
   * <p>走的是与「续接对话」完全相同的那条路径（{@code read → fold}），因此界面上看到的
   * 历史与模型实际收到的上下文**必然一致**。若另写一套解析，两者迟早会漂移——比如
   * 界面显示了一条被压缩覆盖掉的消息，而模型那边其实已经看不到它了。
   *
   * <p>压缩条目是回溯性标记（出现在被它覆盖的条目**之后**），fold 内部会做两趟处理，
   * 这里不需要额外关心。
   *
   * @return 该会话的消息列表；会话不存在或读取失败时返回空列表（不抛异常——
   *     回放失败不该让整个界面报错，用户看到空列表比看到崩溃好）
   */
  public List<ModelMessage> loadConversationMessages(String conversationId) {
    // loadEntries 已内部吞掉 IO/运行时异常并返回空列表，这里无需再包一层 try。
    List<ConversationLog.EntryLocation> entries = loadEntries(conversationId);
    if (entries.isEmpty()) {
      return new ArrayList<>();
    }
    return ConversationHistory.fold(entries);
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
    return newConversation(null);
  }

  /**
   * 在指定项目目录下新建会话并切过去。
   *
   * <p>{@code cwd} 决定这个会话属于哪个项目（见 {@link #resolveRunWorkspace}）：传 null
   * 表示用当前视图工作区。多项目并行时，用户可以在「新建会话」时直接指定目标项目，
   * 之后这个会话的工具调用就固定在那个项目里，与视图当前显示哪个项目无关。
   *
   * @param cwd 会话绑定的项目绝对路径；null 时用当前工作区
   * @return 新会话摘要
   */
  public ConversationSummary newConversation(String cwd) throws IOException {
    String effectiveCwd = cwd;
    if (effectiveCwd == null || effectiveCwd.isEmpty()) {
      effectiveCwd = workspace == null ? "" : workspace.getAbsolutePath();
    }
    ConversationSummary summary =
        conversationStore.create(
            null,
            effectiveCwd,
            "",
            settings.getPermissionMode());
    activeConversationId = summary.getId();
    persistActiveConversation();
    return summary;
  }

  /**
   * 列出可作为会话 cwd 的候选项目目录。
   *
   * <p>来源与主屏「最近项目」一致（{@link com.tom.rv2ide.templates.preferences.WizardPreferences}），
   * 过滤掉已不存在的目录。用于「新建会话时选择已有项目」。
   */
  public List<File> listSelectableProjects() {
    List<File> result = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    // 当前工作区排第一：多数情况下用户就是在当前项目里开新会话。
    if (workspace != null && workspace.isDirectory()) {
      result.add(workspace);
      seen.add(workspace.getAbsolutePath());
    }
    for (String path :
        com.tom.rv2ide.templates.preferences.WizardPreferences.INSTANCE.getRecentProjects(
            appContext)) {
      if (path == null || path.isEmpty() || !seen.add(path)) {
        continue;
      }
      File dir = new File(path);
      if (dir.isDirectory()) {
        result.add(dir);
      }
    }
    return result;
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
      // 同步清掉持久化记录。不清的话下次启动会恢复到一个已删除的 id，
      // 虽然 restoreLastConversationForWorkspace 有 exists 校验兜底，
      // 但让存储里留着悬空引用没有意义。
      if (workspace != null) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .remove(lastConversationKey(workspace))
            .apply();
      }
    }
  }

  /**
   * 上下文用量回调。
   *
   * <p>由 UI 注入（悬浮助手的上下文圆环）。不放进 `run()` 的参数表：它跨越多次运行
   * 持续存在，属于「这个 orchestrator 的观察者」而不是「这一次运行的输入」——
   * 放进参数表会让每个调用点都要重复传一遍同一个对象。
   *
   * <p>回调在 agent 循环线程上触发，实现方需自行切主线程。
   */
  private volatile RunContextManager.UsageListener contextUsageListener;

  public void setContextUsageListener(RunContextManager.UsageListener listener) {
    this.contextUsageListener = listener;
  }

  /**
   * 当前会话的上下文窗口大小；未配置时为 0。
   *
   * <p>用于 UI 在没有运行过（因此还没有用量回调）时先判断「圆环该不该显示」。
   */
  public int contextSizeForActiveConfig() {
    ModelConfig config = resolveConfigForCompaction();
    return config == null ? 0 : ConversationCompaction.contextSizeOf(config);
  }

  /**
   * 设置工作区根目录。工具只能在此目录内操作。
   *
   * <p><b>切换工作区时恢复该工作区上次打开的会话</b>：用户进入项目后期待接着上次继续，
   * 而不是每次面对一个空会话、还要去历史列表里翻。持久化的键含工作区绝对路径——
   * 用全局单一「上次会话」会让两个项目互相覆盖，用户切回 A 项目却看到 B 的对话。
   */
  public void setWorkspace(File workspace) {
    this.workspace = workspace;
    restoreLastConversationForWorkspace();
  }

  /**
   * 取回本工作区上次打开的会话 id。
   *
   * <p>只存 id 不存内容——会话内容本来就在 {@link ConversationStore} 里，
   * 重复存储会产生两份真相。
   *
   * <p><b>两道校验都必须做</b>：
   *
   * <ol>
   *   <li>会话**仍然存在**——用户可能已在历史列表里删了它，指向已删除的 id 会让后续写入失败。
   *   <li>会话的 cwd **就是本工作区**——不能只信这个键。用户可以从全局会话列表里
   *       打开别的项目的会话（UI 会先提示，但允许继续），那时 {@link #openConversation}
   *       会把本工作区的键指向那个会话的 id。此后重开应用，本工作区就会续接另一个
   *       项目的历史：模型看到的上下文属于别的项目，而工具作用于本项目。
   * </ol>
   *
   * <p>cwd 校验失败时**清掉这个键**再走兜底，而不是留着——留着一个指向别的项目的
   * 记录，下次启动还要再判一次，且用户永远看不到问题所在。
   */
  private void restoreLastConversationForWorkspace() {
    if (workspace == null) {
      return;
    }
    // 只列一次：下面两道校验（存在性 + 归属）与兜底挑选都基于同一份快照，
    // 否则最多要读三遍全部会话文件。
    List<ConversationSummary> all = listConversationsQuietly();
    String want = workspace.getAbsolutePath();

    android.content.SharedPreferences prefs =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext);
    String key = lastConversationKey(workspace);
    String saved = prefs.getString(key, null);
    if (saved != null && !saved.isEmpty()) {
      for (ConversationSummary summary : all) {
        if (summary.getId().equals(saved)) {
          if (want.equals(summary.getCwd())) {
            activeConversationId = saved;
            return;
          }
          // 存在，但属于别的项目——见上面 KDoc 里的第 2 条。
          break;
        }
      }
      // 走到这里说明记录不可用（会话已删、文件损坏、或指向别的项目）。
      // 清掉它：留着一个坏记录，下次启动还要再判一次，用户也永远看不到问题所在。
      prefs.edit().remove(key).apply();
    }
    // 兜底：没有记录（首次升级到本版本的用户）、记录已失效、或记录指向别的项目时，
    // 取本工作区最近修改的会话。否则老用户升级后会看到空会话、还得去历史列表里翻
    // ——而会话其实一条都没丢。
    restoreMostRecentConversation(all);
  }

  /** {@link #listConversations()} 的不抛异常版本：读不出来时按「没有会话」处理。 */
  private List<ConversationSummary> listConversationsQuietly() {
    try {
      return conversationStore.list();
    } catch (IOException | RuntimeException e) {
      return new ArrayList<>();
    }
  }

  /**
   * 兜底恢复：从给定快照里取本工作区最近修改的、非空的会话。
   *
   * <p>只在本工作区**从未显式记录过**可用会话时走这条路（升级到本版本的老用户，
   * 或记录指向了别的项目）。
   *
   * <p>cwd 直接取自摘要（{@link ConversationSummary#getCwd()}）——早先摘要不含它，
   * 这里得对每个会话调一次 {@code conversationStore.read()} 反查，是 O(n) 次文件读；
   * cwd 本就在 fold 摘要时经手的 meta 条目里，顺手带出来即可。
   */
  private void restoreMostRecentConversation(List<ConversationSummary> all) {
    String want = workspace.getAbsolutePath();
    ConversationSummary best = null;
    for (ConversationSummary summary : all) {
      if (summary.getMessageCount() <= 0) {
        continue;
      }
      // 必须按 cwd 过滤。不过滤的话：项目 A 从未用过 agent 时，打开 A 会恢复到
      // 项目 B 的会话，紧接着 persistActiveConversation() 把 A 的键指向 B 的 id——
      // 此后在 A 里的对话会追加进 B 的会话文件，模型拿到的历史属于另一个项目。
      if (!want.equals(summary.getCwd())) {
        continue;
      }
      if (best == null || summary.getModifiedAt() > best.getModifiedAt()) {
        best = summary;
      }
    }
    if (best == null) {
      return;
    }
    activeConversationId = best.getId();
    // 立刻落盘，之后就走精确路径。
    persistActiveConversation();
  }

  /** 记住本工作区当前打开的会话。 */
  private void persistActiveConversation() {
    if (workspace == null || activeConversationId == null || activeConversationId.isEmpty()) {
      return;
    }
    androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        .edit()
        .putString(lastConversationKey(workspace), activeConversationId)
        .apply();
  }

  /**
   * 按工作区隔离的偏好键。
   *
   * <p>工作区路径直接进键名：SharedPreferences 的键允许任意字符串，
   * 路径里的 `/` 不影响查找。
   */
  private static String lastConversationKey(File workspace) {
    return "ai_agent_last_conversation:" + workspace.getAbsolutePath();
  }

  public File getWorkspace() {
    return workspace;
  }

  /**
   * 当前任务清单（供界面渲染任务卡片）。
   *
   * <p>返回副本而不是内部列表：界面在别的线程读，直接给出内部引用会在
   * {@code todo_update} 写入时产生并发修改。
   */
  public List<com.tom.rv2ide.ai.tool.TodoItem> getTodos() {
    try {
      return todoStore.getItems();
    } catch (RuntimeException e) {
      return new ArrayList<>();
    }
  }

  public AgentToolSettings getSettings() {
    return settings;
  }

  /** 构建工具注册表：文件工具 + shell 执行 + 运行测试闭环四件套。 */
  public ToolRegistry buildRegistry() {
    return buildRegistry(true, null);
  }

  /**
   * 构建工具注册表，可选是否装配外部 MCP 工具。
   *
   * <p><b>为什么要有 {@code includeMcp=false} 这条路径</b>：UI 只为「工具卡片的分类色」
   * 调用本方法（{@code FloatingAssistantView.attach}），而装配 MCP 工具会发起网络请求。
   * 那条调用发生在主线程，且 MCP 客户端只有 {@code run()} 的 finally 会释放——
   * UI 调一次就泄漏一条长连接。UI 传 false，只装内置工具即可（MCP 工具的分类退化为默认色，
   * 无关紧要）。
   *
   * @param includeMcp 是否装配 MCP 工具
   * @param mcpClientsOut 非空时，本次新建的 MCP 客户端登记到这里，由调用方负责释放；
   *     为 null 时（UI 路径）不装配 MCP
   */
  public ToolRegistry buildRegistry(
      boolean includeMcp, java.util.List<com.tom.rv2ide.ai.tool.mcp.McpClient> mcpClientsOut) {
    ToolRegistry registry = new ToolRegistry();

    // 文件操作
    registry.register(new FileReadTool());
    registry.register(new FileWriteTool());
    registry.register(new FileEditTool());
    registry.register(new FileDeleteTool());
    registry.register(new GlobTool());
    registry.register(new ListDirectoryTool());

    // shell 执行（后端由配置决定：Termux 或 Shizuku）。
    //
    // 后端注册表**只建一次并共享**给所有需要执行命令的工具（shell_execute 与 phone_*）。
    // 每次 new 一个的话，各工具会各自解析出可能不同的 active 后端，且 setActiveId 的
    // 效果只落在自己那份上——用户切后端后，一部分工具还走旧后端，行为不一致且难排查。
    ShellBackendRegistry shellBackends = buildShellBackends();
    registry.register(new ShellExecuteTool(shellBackends));

    // 运行测试闭环：构建 → 安装 → 启动 → 读日志
    registry.register(new GradleBuildTool(this::lookupBuildService));
    registry.register(new InstallApkTool(appContext));
    registry.register(new LaunchAppTool(appContext));
    registry.register(new LogcatReadTool(appContext));

    // ---- 真机测试工具集（经 Shizuku 走 adb 级权限，不需要无障碍服务）----
    // 全部复用上面那一份 shell 后端注册表，与 shell_execute 看到同一个 active 后端。

    // 观察类（只读，不改变设备）：截屏 / 节点树 / 前台 Activity / 内存与渲染
    registry.register(new PhoneScreenshotTool(appContext, shellBackends));
    registry.register(new PhoneViewHierarchyTool(shellBackends));
    registry.register(new PhoneCurrentActivityTool(shellBackends));
    registry.register(new PhoneMemInfoTool(appContext, shellBackends));

    // 交互类（SYSTEM；needsConfirmation=true，与 shell_execute 同级）。
    // 这些工具能驱动任意应用（点开终端、输入命令、回车执行），若免确认就是绕过
    // shell_execute 确认门的一条通道，因此必须逐次确认；需要无打扰跑闭环请切 AUTO 模式。
    registry.register(new PhoneClickTool(shellBackends));
    registry.register(new PhoneClickViewTool(shellBackends));
    registry.register(new PhoneSwipeTool(shellBackends));
    registry.register(new PhoneLongPressTool(shellBackends));
    registry.register(new PhoneInputTextTool(shellBackends));
    registry.register(new PhoneGlobalActionTool(shellBackends));
    registry.register(new PhoneWaitForTool(shellBackends));
    // 清数据：不可逆，且作用于任意包名，因此额外按包名限定授权粒度（见 ToolPermissionRule）。
    registry.register(new PhoneClearDataTool(shellBackends));

    // 动作级截图回归 / 基线 / 多步场景：内部会调用其它工具来驱动界面。
    //
    // 它们**不接收注册表**。早先的写法是构造时传入 registry、execute 时直接
    // registry.get(name).execute(...)，这绕过了 ToolExecutor 的权限判定与确认门：
    // 一个场景里若嵌了 shell_execute / file_delete / phone_clear_data，
    // 那些工具自身的 needsConfirmation() 不会被复查，用户只在确认框里看到一大坨 steps JSON。
    // 现在改为装配方在执行器构建后注入 ToolInvoker（见 wireToolInvokers）。
    registry.register(new PhoneBaselineTool(appContext));
    registry.register(new PhoneActionCaptureTool(appContext));
    registry.register(new PhoneScreenshotCompareTool(appContext));
    registry.register(new PhoneTestScenarioTool(appContext));

    // 任务计划：把模型的计划外化成可见状态，使长任务不丢进度。
    registry.register(new TodoUpdateTool(todoStore));

    // 长期记忆：跨会话保留的项目约定与用户偏好。
    registry.register(new com.tom.rv2ide.ai.tool.memory.MemoryUpdateTool(memoryStore));

    // skill：按需加载的说明文档。提示词里只放名字与一句话说明（渐进披露）。
    registry.register(new com.tom.rv2ide.ai.tool.skill.SkillTool(skillRegistry));
    // skill 的写入端：让模型能自己沉淀踩过的坑，形成能力自增长。
    // 与上面那个是一对——只有读没有写，踩过的坑就无处可记，下次重踩。
    registry.register(new com.tom.rv2ide.ai.tool.skill.SkillWriteTool(skillRegistry));

    // 网络：先搜索定位页面，再抓取正文。
    AppHttpPort http = new AppHttpPort();
    registry.register(new WebFetchTool(http));
    registry.register(new WebSearchTool(new RssSearchProvider(http)));
    // 通用 HTTP：带自定义方法/头/体，并返回状态码与非 2xx 的错误体（web_fetch 只做 GET 且
    // 非 2xx 当失败）。支持 POST/PUT/DELETE 等可变方法，故为 SYSTEM 且需确认。
    registry.register(new HttpRequestTool(http));

    // 外部 MCP server 提供的工具。只有 run() 路径（mcpClientsOut != null）才装配：
    // UI 的分类色查询不该触发网络请求，也不该创建无人释放的连接。
    if (includeMcp && mcpClientsOut != null) {
      registerMcpTools(registry, http, mcpClientsOut);
    }

    return registry;
  }

  /**
   * 给编排类工具注入子调用入口，使它们的子调用经过 {@link ToolExecutor} 的权限判定。
   *
   * <p>只对实现了 {@link ToolInvokerAware} 的工具注入。未注入时工具会明确报错，
   * 而不是退回直接 {@code BaseTool.execute}——那样会静默绕过权限门。
   */
  private static void wireToolInvokers(ToolRegistry registry, ToolExecutor executor) {
    ToolInvoker invoker = new ExecutorToolInvoker(executor);
    for (BaseTool tool : registry.getAll()) {
      if (tool instanceof ToolInvokerAware) {
        ((ToolInvokerAware) tool).setToolInvoker(invoker);
      }
    }
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
  private void registerMcpTools(
      ToolRegistry registry,
      AppHttpPort http,
      java.util.List<com.tom.rv2ide.ai.tool.mcp.McpClient> clientsOut) {
    java.util.List<McpServers.Server> servers;
    try {
      servers = new McpServers(appContext).enabled();
    } catch (RuntimeException e) {
      return;
    }
    if (servers.isEmpty()) {
      return;
    }

    // 客户端登记到调用方传入的列表（每次运行一份），由 run() 的 finally 释放。
    // 不能登记到 orchestrator 的共享字段：并发两个会话时，一个运行结束会把另一个
    // 仍在使用的连接一并关掉。UI 路径（clientsOut 恒为 run 传入的本地列表）不涉及。
    java.util.Set<String> usedNames = new java.util.HashSet<>();
    for (ToolInfo existing : registry.getAll()) {
      usedNames.add(existing.getName());
    }

    for (McpServers.Server server : servers) {
      try {
        com.tom.rv2ide.ai.tool.mcp.McpClient client =
            new com.tom.rv2ide.ai.tool.mcp.McpClient(
                http,
                server.url,
                java.util.Collections.<String, String>emptyMap(),
                com.tom.rv2ide.ai.tool.mcp.McpClient.Transport.fromId(server.type));
        java.util.List<com.tom.rv2ide.ai.tool.mcp.McpToolInfo> tools;
        try {
          tools = client.listTools();
        } catch (Exception e) {
          // 拉列表失败时必须就地释放：SSE 传输此时可能已经建好长连接。
          client.close();
          throw e;
        }
        // 拉成功后才登记：失败的 client 已在上面关掉，登记它只会在收尾时再关一次。
        clientsOut.add(client);
        for (com.tom.rv2ide.ai.tool.mcp.McpToolInfo info : tools) {
          com.tom.rv2ide.ai.tool.mcp.McpToolAdapter adapter =
              new com.tom.rv2ide.ai.tool.mcp.McpToolAdapter(client, info, server.displayName());
          // 名字冲突时跳过而不是覆盖：覆盖会让内置工具静默消失，比缺少一个远程工具更糟。
          if (!usedNames.add(adapter.getName())) {
            continue;
          }
          registry.register(adapter);
        }
      } catch (Exception e) {
        ErrorLog.record("mcp", "拉取 MCP 工具列表失败: " + server.url, e, null);
      }
    }
  }

  /**
   * 释放本次运行建立的 MCP 连接。
   *
   * <p>幂等：重复调用只是对已关闭的客户端再关一次（{@code close} 本身可重入）。
   *
   * <p>只释放**本次运行**建立的连接（列表由 run 持有）。不能用 orchestrator 上的共享
   * 列表：并发两个会话时，一个运行结束会掐断另一个仍在使用的长连接。
   */
  private static void closeMcpClients(
      java.util.List<com.tom.rv2ide.ai.tool.mcp.McpClient> clients) {
    if (clients == null) {
      return;
    }
    for (com.tom.rv2ide.ai.tool.mcp.McpClient client : clients) {
      try {
        client.close();
      } catch (RuntimeException ignored) {
        // 释放失败没有补救手段，且此刻多半正在处理别的失败。
      }
    }
    clients.clear();
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
   * 解析需要用户自填 baseUrl 的服务商的端点地址。
   *
   * <p>只有 {@code custom} 与 {@code localllm} 两个预设的 baseUrl 是空的（其余预设都自带
   * 官方地址）。漏传的后果不是「走默认地址」，而是 {@code endpointFor} 判定 baseUrl 为空后
   * 直接返回 null，调用方再把 null 报成配置错误——用户明明填了密钥却被提示密钥有问题。
   *
   * <p>做成静态方法而非各处自行判断：这段逻辑原先在 {@link
   * com.tom.rv2ide.handlers.AgentRequestHandler} 与悬浮助手里各写了一份，而两份都只处理了
   * {@code localllm}、都漏了 {@code custom}。同一个判断写两遍就会漂移。
   *
   * @return 自定义 baseUrl；该服务商不需要自填时返回 {@code null}
   */
  public static String customBaseUrlFor(Context context, String providerId) {
    if (providerId == null) {
      return null;
    }
    switch (providerId) {
      case "custom":
        return ApiKey.INSTANCE.getCustomBaseUrl();
      case "localllm":
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString("local_llm_base_url", null);
      default:
        return null;
    }
  }

  /**
   * 判断端点解析失败的**真实**原因，用于给出可操作的错误信息。
   *
   * @return 面向用户的原因描述（不含服务商名前缀）
   */
  private static String diagnoseEndpointFailure(String providerId, String customBaseUrl) {
    ProviderPresets.Preset preset = ProviderPresets.find(providerId);
    if (preset == null) {
      return "不在预设表中，无法识别。";
    }
    String baseUrl = preset.getBaseUrl();
    if (customBaseUrl != null && !customBaseUrl.trim().isEmpty()) {
      baseUrl = customBaseUrl.trim();
    }
    if (baseUrl.isEmpty()) {
      return "未配置端点地址（baseUrl），请在 AI 设置中填写。";
    }
    String apiKey = AgentModelConfigs.API_KEY_LOOKUP.keyFor(providerId);
    if (apiKey == null || apiKey.isEmpty()) {
      return "未配置有效的 API 密钥。";
    }
    return "配置无法解析。";
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
    return run(providerId, modelId, userRequest, customBaseUrl, listener, null, null);
  }

  /**
   * 带附件与推理强度的运行重载。
   *
   * @param rawInputJson 多模态输入（图片）的原始 JSON；null 表示纯文本
   * @param reasoningEffort 推理强度（off/low/medium/high，空表示默认）
   */
  public AgentRunResult run(
      String providerId,
      String modelId,
      String userRequest,
      String customBaseUrl,
      AgentEvent.Listener listener,
      String rawInputJson,
      String reasoningEffort) {
    return runInConversation(
        null, providerId, modelId, userRequest, customBaseUrl, listener, rawInputJson,
        reasoningEffort);
  }

  /**
   * 在**指定会话**里运行一次请求。
   *
   * <p><b>为什么必须显式传会话 id</b>：多会话并行时，视图会在运行期间把
   * {@link #activeConversationId} 切到用户当前查看的另一个会话。若 run 隐式使用它，
   * 一个后台运行会在中途「发现」自己换成了别的会话，于是把历史与结果写进错误的文件
   * ——这正是多会话要防的串扰。显式传入后，整个运行期间会话 id 固定不变。
   *
   * @param conversationId 目标会话；null 时用当前活动会话（无则新建）
   */
  public AgentRunResult run(
      String conversationId,
      String providerId,
      String modelId,
      String userRequest,
      String customBaseUrl,
      AgentEvent.Listener listener,
      String rawInputJson,
      String reasoningEffort) {
    return runInConversation(
        conversationId, providerId, modelId, userRequest, customBaseUrl, listener, rawInputJson,
        reasoningEffort);
  }

  private AgentRunResult runInConversation(
      String requestedConversationId,
      String providerId,
      String modelId,
      String userRequest,
      String customBaseUrl,
      AgentEvent.Listener listener,
      String rawInputJson,
      String reasoningEffort) {

    // 先确定本次运行属于哪个会话，再据其 cwd 解析工作区。顺序不能反：工具作用的工作区
    // 必须**跟随会话**，而不是跟随「视图当前打开的项目」——后者会让「历史属于 A 项目、
    // 工具却作用于 B 项目」的错配重新出现（见 restoreMostRecentConversation 的注释）。
    String conversationId = ensureConversation(requestedConversationId);
    String sessionCwd = cwdOf(conversationId);
    File runWorkspace = resolveRunWorkspace(conversationId);
    if (runWorkspace == null) {
      // 区分两种 null：会话绑定的项目目录失效（不回退，见 resolveRunWorkspace），
      // 与压根没设工作区。前者要说清是哪个目录没了，否则用户不知道该恢复什么。
      if (sessionCwd != null && !sessionCwd.isEmpty()) {
        return new AgentRunResult(
            "该会话绑定的项目目录已不存在：" + sessionCwd + "。请重新打开或迁移该项目后再试。",
            0,
            0,
            true);
      }
      return new AgentRunResult("未设置工作区，无法执行。", 0, 0, true);
    }
    if (!runWorkspace.exists()) {
      return new AgentRunResult("工作区不存在: " + runWorkspace, 0, 0, true);
    }
    // 只重置**本会话**的危险工具运行内授权：并发会话各有各的桶，
    // 全局清理会让别的会话已批准的规则丢失、重复弹窗。
    settings.beginRun(conversationId);

    AgentModelConfigs.ProviderEndpoint endpoint =
        AgentModelConfigs.endpointFor(providerId, customBaseUrl);
    if (endpoint == null) {
      // endpointFor 返回 null 有多种原因，必须分开报——否则用户会照着错误提示去改
      // 根本没问题的东西。曾经这里统一说「未配置有效的 API 密钥」，而真实原因是
      // 自定义端点没传 baseUrl，排查方向被完全带偏。
      return new AgentRunResult(
          "服务商 " + providerId + " " + diagnoseEndpointFailure(providerId, customBaseUrl),
          0,
          0,
          true);
    }

    // 主运行用主槽位：请求路径只带模型名、不带槽位，modelId 即当前主模型。
    // 显式传 SLOT_MAIN 才能把记录里声明的 [1m] 翻译成 contextSize，否则勾选不生效。
    ModelConfig config =
        AgentModelConfigs.build(
            endpoint, modelId, DEFAULT_TOOL_CALL_LIMIT, ProviderConfig.SLOT_MAIN);

    // 本次运行的 MCP 客户端列表：随本次运行建立、随本次运行释放。不能放 orchestrator
    // 共享字段——并发两个会话时，一个运行结束会把另一个仍在使用的长连接关掉。
    java.util.List<com.tom.rv2ide.ai.tool.mcp.McpClient> mcpClients =
        new java.util.ArrayList<>();
    ToolRegistry registry = buildRegistry(true, mcpClients);
    ToolPermissionService permissions = new ToolPermissionService(settings, registry);
    ToolExecutor executor =
        new ToolExecutor(registry, permissions, diffStore == null ? null : newDiffRecorder());

    // 编排类工具（场景 / 连拍 / 基线 / 截图对比）的子调用必须过权限判定，
    // 因此在这里——执行器建好之后——把调用入口注入它们。执行器依赖注册表、
    // 注册表里又有这些工具，构成环，所以只能在环外补这一针。
    wireToolInvokers(registry, executor);

    // 子 agent：把「需要读很多文件」的调查隔离到独立上下文里。
    //
    // 注册在这里而不是 buildRegistry 里，因为它需要本次运行的 endpoint / 模型 / 取消令牌。
    // 取消令牌必须在 AgentSession 之前创建：子 agent 要挂在它上面才能随父级一起停——
    // 否则用户点了取消，主循环停了而子 agent 仍在烧额度。
    ModelCancellationToken cancellation = new ModelCancellationToken();
    // 按会话登记取消令牌：并发两个会话时，各自只停自己的。
    activeCancellations.put(conversationId, cancellation);

    SubAgentRunnerImpl subAgentRunner =
        new SubAgentRunnerImpl(
            endpoint,
            modelId,
            runWorkspace.getAbsolutePath(),
            settings,
            cancellation,
            diffStore,
            // 子 agent 继承父会话 id：危险工具授权与父会话同桶，避免重复弹窗。
            conversationId);
    registry.register(new com.tom.rv2ide.ai.tool.AgentTool(subAgentRunner));

    // 内置子 agent：固定的角色提示词 + 受限工具集（审查/探索/定位/写测试/写文档）。
    // 模型可直接派遣它们，不必每次从零描述角色约束。
    for (com.tom.rv2ide.ai.agent.builtin.BuiltinAgent builtin : builtinAgentStore.enabled()) {
      BuiltinAgentTool tool = new BuiltinAgentTool(builtin, subAgentRunner, 0);
      if (registry.get(tool.getName()) == null) {
        registry.register(tool);
      }
    }

    // 用户自定义 agent：固定的角色提示词，模型只需给出要处理什么。
    for (com.tom.rv2ide.ai.agent.command.CustomAgent custom : customAgentStore.usable()) {
      CustomAgentTool tool = new CustomAgentTool(custom, subAgentRunner, 0);
      // 名字冲突时跳过：覆盖会让另一个自定义 agent 静默消失。
      if (registry.get(tool.getName()) == null) {
        registry.register(tool);
      }
    }

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
            runWorkspace.getAbsolutePath(),
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

    // skill 清单：**只有名字与一句话说明**，正文按需用 skill 工具加载。
    // 这正是渐进披露的关键——几十个 skill 的常驻成本只有几百 token，
    // 全部展开会把上下文占满，而其中绝大多数与当前任务无关。
    String skillList = skillRegistry.renderForPrompt();
    if (!skillList.isEmpty()) {
      systemPrompt =
          systemPrompt
              + "\n\n[ 可用 skill ]\n"
              + skillList
              + "\n\n与当前任务相关时，用 skill 工具加载其完整内容。";
    }

    ToolContext toolContext =
        ToolContext.builder()
            .homePath(runWorkspace.getAbsolutePath())
            .settings(settings)
            // 会话 id 随上下文下发：危险工具的运行内授权按会话隔离。
            .conversationId(conversationId)
            // 读图时的缩放实现。工具模块零 Android 依赖，因此由这里注入。
            .imageDataProvider(AndroidImageDataProvider.INSTANCE)
            .build();

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
    // 把用量推给 UI（输入区的上下文圆环）。监听器在调用线程上执行，因此实现里
    // 只做「切主线程 + 更新一个 View」，不做 I/O。
    RunContextManager contextManager =
        new RunContextManager(
            new TokenUsageTracker(ConversationCompaction.contextSizeOf(config)),
            overhead,
            contextUsageListener);

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
          contextManager,
          rawInputJson,
          reasoningEffort);
    } finally {
      // 只移除本次运行登记的那一个令牌：并发会话各有各的，不能整体清空。
      activeCancellations.remove(conversationId);
      // 丢弃本会话的运行内危险工具授权记忆（「本次运行内允许」随运行结束失效），
      // 避免条目随会话数累积。
      settings.clearRunApprovedRules(conversationId);
      // MCP 的 SSE 传输是一条长连接，必须随本次运行结束而释放，
      // 否则每跑一次对话就多一条悬挂的连接（以及一个守护读线程）。
      // 只释放本次运行建立的连接，不影响并发会话仍在使用的连接。
      closeMcpClients(mcpClients);
    }
  }

  /**
   * 手动压缩当前会话（对应 `/compact` 命令）。
   *
   * <p><b>与 {@link #maybeCompact} 的区别</b>：那个由 token 硬阈值自动触发、只压到刚好
   * 低于阈值；这个由用户显式发起，因此**压到最低限度**——用户点它就是要腾出尽可能多的
   * 空间，压一半等于没解决问题。
   *
   * <p>与自动压缩共用 {@link ConversationCompaction#select} 与 {@link ContextCompactor}，
   * 只是传入的预算不同。不另写一套：两条路径产出的压缩条目格式必须一致，否则
   * {@link ConversationHistory#fold} 会解不出来。
   *
   * @return 面向用户的简短结果描述（压掉多少条 / 摘要多少 token）；无可压缩内容或失败时
   *     返回 null，由调用方给出提示。**不抛异常**——压缩是优化，不该让界面崩。
   */
  public String compactActiveConversation() {
    String conversationId = activeConversationId;
    if (conversationId == null || conversationId.isEmpty()) {
      return null;
    }
    ModelConfig config = resolveConfigForCompaction();
    if (config == null) {
      return null;
    }
    List<ConversationLog.EntryLocation> entries;
    try {
      entries = conversationStore.read(conversationId);
    } catch (IOException | RuntimeException e) {
      return null;
    }
    if (entries == null || entries.isEmpty()) {
      return null;
    }
    List<ModelMessage> history = ConversationHistory.fold(entries);
    if (history.isEmpty()) {
      return null;
    }
    // 手动压缩压到「最小保留预算」：保留最近的若干轮以便对话能续上，其余全部摘要。
    //
    // 取自动压缩预算的 1/4：自动压缩只需压到刚好低于阈值（否则每次都要重压），
    // 而用户点手动压缩是明确要腾空间，压得越干净越好；但不能压到 0——
    // 完全不留最近上下文会让模型立刻丢失用户正在做的事。
    int autoBudget = ConversationCompaction.historyBudget(config, 0);
    int keepBudget = autoBudget == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, autoBudget / 4);
    ConversationCompaction.Selection selection =
        ConversationCompaction.select(entries, keepBudget);
    if (selection.isEmpty()) {
      return null;
    }
    ContextCompactor.Summarizer summarizer = buildSummarizer(config);
    if (summarizer == null) {
      return null;
    }
    String summary = ContextCompactor.compact(selection.getMessages(), summarizer);
    if (summary == null || summary.trim().isEmpty()) {
      return null;
    }
    try {
      conversationStore.append(
          conversationId,
          CompactionEntry.create(
              null, System.currentTimeMillis(), summary.trim(), selection.getUpToOrdinal()));
    } catch (IOException e) {
      ErrorLog.record("agent", "手动压缩写入失败", e, null);
      return null;
    }
    return selection.getMessages().size() + " 条消息 → 摘要 " + TokenEstimator.estimate(summary) + " token";
  }

  /** 取压缩用的模型配置；拿不到时返回 null（调用方据此提示失败）。 */
  private ModelConfig resolveConfigForCompaction() {
    try {
      Agents agents = new Agents(appContext);
      String providerId = agents.getProvider();
      String modelId = agents.getAgent();
      if (providerId == null || providerId.isEmpty() || modelId == null || modelId.isEmpty()) {
        return null;
      }
      AgentModelConfigs.ProviderEndpoint endpoint =
          AgentModelConfigs.endpointFor(providerId, customBaseUrlFor(appContext, providerId));
      if (endpoint == null) {
        return null;
      }
      return AgentModelConfigs.build(
          endpoint, modelId, DEFAULT_TOOL_CALL_LIMIT, ProviderConfig.SLOT_MAIN);
    } catch (RuntimeException e) {
      return null;
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

  /**
   * @return 当前会话 id；若尚无会话则新建一个。
   *
   * <p>无工作区时 cwd 记为**空串**而不是抛异常或跳过：没有打开项目也能用 AI 助手
   * （问纯知识问题、看代码片段），此时会话不属于任何项目。空 cwd 的会话不会被
   * {@code restoreMostRecentConversation} 的 cwd 匹配命中，这是**有意**的——它本就
   * 不属于任何项目，重开项目时不该被自动续接。原先是直接 {@code workspace.getAbsolutePath()}
   * 解引用，未打开项目时会抛 NPE（{@code newConversation()} 有守卫，这里漏了）。
   */
  private String ensureConversation() {
    return ensureConversation(null);
  }

  /**
   * @param requested 期望的会话 id；非空且存在时直接用它（多会话并行时由视图显式指定）。
   *     为 null 或已失效时回退到当前活动会话，再没有才新建。
   * @return 会话 id；新建落盘失败时返回空串（退化为无历史的内存会话）
   */
  private String ensureConversation(String requested) {
    if (requested != null && !requested.isEmpty() && conversationStore.exists(requested)) {
      return requested;
    }
    String existing = activeConversationId;
    if (existing != null && conversationStore.exists(existing)) {
      return existing;
    }
    try {
      ConversationSummary created =
          conversationStore.create(
              null,
              workspace == null ? "" : workspace.getAbsolutePath(),
              "",
              settings.getPermissionMode());
      activeConversationId = created.getId();
      persistActiveConversation();
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

  /**
   * 请求取消指定会话正在执行的循环。
   *
   * <p>按会话 id 精确取消：并发多个会话时，停当前显示的会话不该误杀后台会话。
   * 会话 id 为空（未开始任何会话）或该会话没在跑时是 no-op。
   */
  public void cancel(String conversationId) {
    if (conversationId == null || conversationId.isEmpty()) {
      return;
    }
    ModelCancellationToken cancellation = activeCancellations.get(conversationId);
    if (cancellation != null) {
      cancellation.cancel();
    }
  }

  /** 取消当前活动会话的循环。等价于 {@code cancel(activeConversationId)}。 */
  public void cancel() {
    cancel(activeConversationId);
  }

  /**
   * 解析本次运行实际作用的项目目录。
   *
   * <p><b>为什么工具的工作区必须跟随会话、而不是跟随视图的 {@link #workspace}</b>：
   * 会话列表是全局的，用户可以从当前项目里打开一条属于别的项目的会话。此前 {@code run()}
   * 无条件用视图的 workspace，于是「历史属于 A 项目、工具却作用于 B 项目」——文件被写到
   * 错误的项目里。跨项目确认弹框只能提醒，拦不住这条数据损坏路径。
   *
   * <p>会话的 cwd 存在 {@link com.tom.rv2ide.ai.agent.conversation.SessionMetaEntry} 里，
   * 因此这里以它为准：会话属于哪个项目，工具就作用于哪个项目。这同时让「跨项目保护」
   * 从「弹框阻止」升级为「按会话正确归属」——不再需要拦截用户。
   *
   * <p>会话没有 cwd（未打开项目时建的纯问答会话）时回退到视图工作区，保持旧行为。
   *
   * <p><b>会话有 cwd、但目录已不存在时返回 null，不回退视图工作区</b>：那正是「历史属于
   * A、工具却作用于 B」的数据损坏路径。跨项目确认框已被移除（改为「按会话正确归属」），
   * 因此这里不能再悄悄把工具指向另一个项目——宁可报错让用户显式处理。
   *
   * @return 本次运行的工作区；会话绑定的目录失效或两者都不可用时返回 null（调用方报错）
   */
  private File resolveRunWorkspace(String conversationId) {
    String cwd = cwdOf(conversationId);
    if (cwd != null && !cwd.isEmpty()) {
      File dir = new File(cwd);
      if (dir.isDirectory()) {
        return dir;
      }
      // 会话绑定的项目目录已被删除/移动。不能回退到视图工作区（见 KDoc）。
      return null;
    }
    return workspace;
  }

  /**
   * 读取会话绑定的 cwd；读不到返回 null。
   *
   * <p>只读**这一个**会话文件（首条 {@code SessionMetaEntry} 就带 cwd），而不是遍历
   * 全部会话做 O(n) 次文件读——本方法在每次 run 前都会调用。
   */
  private String cwdOf(String conversationId) {
    if (conversationId == null || conversationId.isEmpty()
        || !conversationStore.exists(conversationId)) {
      return null;
    }
    try {
      for (ConversationLog.EntryLocation location : conversationStore.read(conversationId)) {
        ConversationEntry entry = location.getEntry();
        if (entry instanceof com.tom.rv2ide.ai.agent.conversation.SessionMetaEntry) {
          return ((com.tom.rv2ide.ai.agent.conversation.SessionMetaEntry) entry).getCwd();
        }
      }
    } catch (IOException | RuntimeException e) {
      // 读不出来按「无 cwd」处理，回退到视图工作区。
    }
    return null;
  }

  private com.tom.rv2ide.ai.tool.DiffRecorder newDiffRecorder() {
    return new com.tom.rv2ide.ai.tool.DiffRecorder(diffStore);
  }
}
