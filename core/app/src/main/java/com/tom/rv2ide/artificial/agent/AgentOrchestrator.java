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
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolPermissionService;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ShellExecuteTool;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.artificial.agent.tool.GradleBuildTool;
import com.tom.rv2ide.artificial.secrets.ApiKey;
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
   * 用户自定义 agent 存储。
   *
   * <p>每次运行读取：用户改完定义后下一次运行即生效，不必重启。
   */
  private final com.tom.rv2ide.ai.agent.command.CustomAgentStore customAgentStore;

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

  private volatile ModelCancellationToken activeCancellation;

  /**
   * 本次运行创建的 MCP 客户端。
   *
   * <p><b>为什么要在编排器上持有</b>：SSE 传输握着一条长连接，而注册表与 adapter 都
   * 没有「运行结束」这个事件可挂。不在这里统一释放，每跑一次对话就泄漏一条连接。
   * 客户端由同一 server 的多个 adapter 共享，因此也不能由 adapter 自己关。
   *
   * <p>用 {@code synchronizedList} 而非并发集合：写入只发生在 {@code buildRegistry}
   * 的循环里，读取只在 {@code run} 的 finally，规模是个位数。
   */
  private final java.util.List<com.tom.rv2ide.ai.tool.mcp.McpClient> activeMcpClients =
      java.util.Collections.synchronizedList(
          new java.util.ArrayList<com.tom.rv2ide.ai.tool.mcp.McpClient>());

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
    this.promptBuilder = new AgentPromptBuilder("ACS AI Agent", new PrefsPromptTemplateStore(appContext));
  }

  /** 自定义 agent 文件：{@code filesDir/ai/custom_agents.json}。 */
  private static File defaultCustomAgentsFile(Context context) {
    return new File(new File(context.getFilesDir(), "ai"), "custom_agents.json");
  }

  /** 自定义 agent 存储，供设置界面管理。 */
  public com.tom.rv2ide.ai.agent.command.CustomAgentStore getCustomAgentStore() {
    return customAgentStore;
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
    ConversationSummary summary =
        conversationStore.create(
            null,
            workspace == null ? "" : workspace.getAbsolutePath(),
            "",
            settings.getPermissionMode());
    activeConversationId = summary.getId();
    persistActiveConversation();
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

    // skill：按需加载的说明文档。提示词里只放名字与一句话说明（渐进披露）。
    registry.register(new com.tom.rv2ide.ai.tool.skill.SkillTool(skillRegistry));
    // skill 的写入端：让模型能自己沉淀踩过的坑，形成能力自增长。
    // 与上面那个是一对——只有读没有写，踩过的坑就无处可记，下次重踩。
    registry.register(new com.tom.rv2ide.ai.tool.skill.SkillWriteTool(skillRegistry));

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

    // 注意：这里**不能**顺手 closeMcpClients()。buildRegistry 不只被 run() 调用，
    // 悬浮助手也调它（只为了拿工具分类），而 run() 在会话进行中不会再次调用它——
    // 因此「走到这里就说明上一轮结束了」这个前提不成立，在此释放会掐断正在使用的连接。
    // 释放只放在 run() 的 finally 里。
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
        activeMcpClients.add(client);
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
   * <p>只在 {@code run} 的 finally 里调用。见 {@code registerMcpTools} 里的说明：
   * {@code buildRegistry} 会在会话进行中被再次调用，不能在那里释放。
   */
  private void closeMcpClients() {
    java.util.List<com.tom.rv2ide.ai.tool.mcp.McpClient> clients;
    synchronized (activeMcpClients) {
      clients = new java.util.ArrayList<>(activeMcpClients);
      activeMcpClients.clear();
    }
    for (com.tom.rv2ide.ai.tool.mcp.McpClient client : clients) {
      try {
        client.close();
      } catch (RuntimeException ignored) {
        // 释放失败没有补救手段，且此刻多半正在处理别的失败。
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
      // endpointFor 返回 null 有多种原因，必须分开报——否则用户会照着错误提示去改
      // 根本没问题的东西。曾经这里统一说「未配置有效的 API 密钥」，而真实原因是
      // 自定义端点没传 baseUrl，排查方向被完全带偏。
      return new AgentRunResult(
          "服务商 " + providerId + " " + diagnoseEndpointFailure(providerId, customBaseUrl),
          0,
          0,
          true);
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

    SubAgentRunnerImpl subAgentRunner =
        new SubAgentRunnerImpl(
            endpoint,
            modelId,
            workspace.getAbsolutePath(),
            settings,
            cancellation,
            diffStore);
    registry.register(new com.tom.rv2ide.ai.tool.AgentTool(subAgentRunner));

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
            .homePath(workspace.getAbsolutePath())
            .settings(settings)
            // 读图时的缩放实现。工具模块零 Android 依赖，因此由这里注入。
            .imageDataProvider(AndroidImageDataProvider.INSTANCE)
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
      activeCancellation = null;
      // MCP 的 SSE 传输是一条长连接，必须随本次运行结束而释放，
      // 否则每跑一次对话就多一条悬挂的连接（以及一个守护读线程）。
      closeMcpClients();
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
      return AgentModelConfigs.build(endpoint, modelId, DEFAULT_TOOL_CALL_LIMIT);
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
