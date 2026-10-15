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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import com.tom.rv2ide.ai.agent.AgentEvent;
import com.tom.rv2ide.ai.agent.AgentRunResult;
import com.tom.rv2ide.ai.agent.AgentSession;
import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.protocol.ModelClient;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.tool.FileDeleteTool;
import com.tom.rv2ide.ai.tool.FileEditTool;
import com.tom.rv2ide.ai.tool.FileReadTool;
import com.tom.rv2ide.ai.tool.FileWriteTool;
import com.tom.rv2ide.ai.tool.GlobTool;
import com.tom.rv2ide.ai.tool.ListDirectoryTool;
import com.tom.rv2ide.ai.tool.SubAgentRunner;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolNameFilter;
import com.tom.rv2ide.ai.tool.ToolPermissionService;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.ai.tool.ToolSettingsPort;
import java.util.List;

/**
 * 用嵌套的 {@link AgentSession} 实现 {@link SubAgentRunner}。
 *
 * <p><b>子 agent 的工具集是刻意裁剪的</b>：
 * <ul>
 *   <li>{@code explore} 模式只注册读类工具（读文件、glob、列目录）。它拿不到写工具，
 *       因此「只读」不是靠提示词约束，而是**结构上做不到**——提示词可能被忽略，
 *       缺少工具不会。
 *   <li>两种模式都**不注册 {@code agent} 工具本身**：子 agent 不应再派子 agent，
 *       否则深度控制只能靠计数，而「不能委派」是更简单也更可靠的边界。
 *   <li>不注册 shell / 构建 / 安装等工具：它们的副作用范围远超「完成一个子任务」，
 *       而子 agent 的任务描述通常不足以让用户判断该不该放行。
 * </ul>
 *
 * <p><b>子 agent 的对话不进主会话日志</b>：它的价值正是上下文隔离。把中间过程写进
 * 主日志会让下次运行的提示词里出现一堆「子 agent 读了什么」的噪声，
 * 反而破坏了隔离带来的收益。
 */
public final class SubAgentRunnerImpl implements SubAgentRunner {

  /**
   * 子 agent 的单次工具调用上限。
   *
   * <p><b>2026-10-15 由 40 改为不限制</b>：40 轮的上限对「读几十个文件」的调查类任务
   * 明显偏小——实测用户在复杂排查任务里被强制中断（「40 轮就强制关闭」）。
   * 子任务是否"聚焦"由任务描述与提示词约束，而不是靠一个数字硬切。
   *
   * <p>仍有的兜底：父级取消传播（用户在父会话点停止会连带取消子 agent），以及
   * 每次工具调用自身的超时——不会出现失控燃烧。（2026-10-15 起 AgentSession
   * 的默认总时长预算已关闭，见 {@code DEFAULT_TIME_BUDGET_MS}。）
   */
  private static final int SUB_AGENT_TOOL_CALL_LIMIT = -1;

  private final AgentModelConfigs.ProviderEndpoint endpoint;
  private final String modelId;
  /** 子 agent 用的模型槽位；决定从服务商记录里读哪个槽位的上下文声明。 */
  private final String slot;
  private final String workspacePath;
  private final ToolSettingsPort settings;
  private final ModelCancellationToken parentCancellation;
  private final com.tom.rv2ide.ai.tool.DiffStore diffStore;

  /**
   * 父会话 id。子 agent 的危险工具授权与父会话归入同一桶，避免子 agent 重复弹窗；
   * 同时保证授权不会因「新会话」而被错误地重置。
   */
  private final String conversationId;

  public SubAgentRunnerImpl(
      AgentModelConfigs.ProviderEndpoint endpoint,
      String modelId,
      String workspacePath,
      ToolSettingsPort settings,
      ModelCancellationToken parentCancellation,
      com.tom.rv2ide.ai.tool.DiffStore diffStore) {
    this(
        endpoint,
        modelId,
        ProviderConfig.SLOT_MAIN,
        workspacePath,
        settings,
        parentCancellation,
        diffStore,
        null);
  }

  public SubAgentRunnerImpl(
      AgentModelConfigs.ProviderEndpoint endpoint,
      String modelId,
      String workspacePath,
      ToolSettingsPort settings,
      ModelCancellationToken parentCancellation,
      com.tom.rv2ide.ai.tool.DiffStore diffStore,
      String conversationId) {
    this(
        endpoint,
        modelId,
        ProviderConfig.SLOT_MAIN,
        workspacePath,
        settings,
        parentCancellation,
        diffStore,
        conversationId);
  }

  public SubAgentRunnerImpl(
      AgentModelConfigs.ProviderEndpoint endpoint,
      String modelId,
      String slot,
      String workspacePath,
      ToolSettingsPort settings,
      ModelCancellationToken parentCancellation,
      com.tom.rv2ide.ai.tool.DiffStore diffStore) {
    this(endpoint, modelId, slot, workspacePath, settings, parentCancellation, diffStore, null);
  }

  public SubAgentRunnerImpl(
      AgentModelConfigs.ProviderEndpoint endpoint,
      String modelId,
      String slot,
      String workspacePath,
      ToolSettingsPort settings,
      ModelCancellationToken parentCancellation,
      com.tom.rv2ide.ai.tool.DiffStore diffStore,
      String conversationId) {
    this.endpoint = endpoint;
    this.modelId = modelId;
    this.slot = slot == null ? ProviderConfig.SLOT_MAIN : slot;
    this.workspacePath = workspacePath;
    this.settings = settings;
    this.parentCancellation = parentCancellation;
    this.diffStore = diffStore;
    this.conversationId = conversationId == null ? "" : conversationId;
  }

  @Override
  public Result run(Request request) throws Exception {
    if (endpoint == null) {
      return new Result(false, "未配置服务商，无法运行子 agent。", 0, 0);
    }

    // 子 agent 用自己那一档槽位的上下文声明（当前与主 agent 同为 SLOT_MAIN）。
    ModelConfig config =
        AgentModelConfigs.build(endpoint, modelId, SUB_AGENT_TOOL_CALL_LIMIT, slot);

    ToolRegistry registry = buildSubRegistry(request.getMode(), request.getToolFilter());
    ToolPermissionService permissions = new ToolPermissionService(settings, registry);
    ToolExecutor executor =
        new ToolExecutor(registry, permissions, diffStore == null ? null : new com.tom.rv2ide.ai.tool.DiffRecorder(diffStore));

    List<com.tom.rv2ide.ai.tool.api.ToolInfo> tools = new java.util.ArrayList<>(registry.getAll());
    ModelClient modelClient = new ModelClient();
    boolean nativeTools = modelClient.supportsNativeTools(config);

    // 角色专用提示词优先：内置/自定义角色 agent 带着自己的职责与约束来，
    // 通用子 agent 提示词（「只专注完成这一个任务」）会稀释角色焦点。
    String systemPrompt =
        request.getSystemPrompt().isEmpty()
            ? buildSubPrompt(request.getMode(), tools, nativeTools)
            : buildRolePrompt(request.getSystemPrompt(), tools, nativeTools);

    // 取消要向下传播：父级被取消时子 agent 必须一起停，否则用户点了取消却仍在烧额度。
    // 用一个受父级影响的独立令牌：子级取消不影响父级，父级取消连带子级。
    ModelCancellationToken childCancellation = new ModelCancellationToken();
    if (parentCancellation != null) {
      parentCancellation.onCancel(childCancellation::cancel);
    }

    ToolContext toolContext =
        ToolContext.builder()
            .homePath(workspacePath)
            .settings(settings)
            // 继承父会话 id：子 agent 的危险工具授权与父会话归入同一桶，
            // 既不重复弹窗，也不会跨会话泄漏。
            .conversationId(conversationId)
            .imageDataProvider(AndroidImageDataProvider.INSTANCE)
            // 子 agent 的长操作同样要能被停止：父级取消 → 子令牌取消 → 正在跑的
            // shell 命令/构建在等待循环里看到它并终止。不接的话，主循环停了、
            // 子 agent 还在烧额度，正是「停止要等很久才生效」的来源之一。
            .cancellation(childCancellation::isCancelled)
            // IDE 能力端口（编辑器会话/代码动作/构建变体/符号搜索）：当前
            // buildSubRegistry 不注册这 4 类工具，所以今天用不到；但只要将来把
            // 其中任一加入子 registry，没有端口就会「工具已注册但返回未接入」的
            // 静默失败。适配器本身无状态、按调用现查，注入成本为零，这里一并接上。
            .editorSession(new ReportedEditorSessionPort())
            .codeActions(subCodeActionsPort())
            .buildVariant(new ReportedBuildVariantPort())
            .workspaceSymbol(new ReportedWorkspaceSymbolPort())
            .build();

    // 子会话事件折叠成步骤行回传父级（见 SubAgentStepReporter 的隔离原则）。
    // 请求未携带进度回调时不建监听器——没有观察者的事件翻译纯属浪费。
    AgentEvent.Listener childListener =
        request.getProgress() == null
            ? null
            : new SubAgentStepReporter(request.getProgress());

    AgentSession session = new AgentSession(modelClient, registry, executor);
    AgentRunResult result =
        session.run(
            config,
            systemPrompt,
            request.getTask(),
            toolContext,
            childCancellation,
            childListener);

    return new Result(
        !result.isFailed(), result.getOutput(), result.getTurns(), result.getToolCallCount());
  }

  /**
   * 构造子 agent 的工具集。
   *
   * <p>见类注释：explore 拿不到写工具，是结构上的只读。
   *
   * <p>角色白名单（{@code filter}）进一步收窄：未列出的工具根本不注册。白名单是
   * **能力边界**而非建议——提示词可能被忽略，缺少工具不会。过滤发生在模式裁剪之后，
   * 因此一个「code 模式 + 只读白名单」的角色，实际也拿不到写工具。
   */
  private ToolRegistry buildSubRegistry(Mode mode, ToolNameFilter filter) {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new FileReadTool());
    registry.register(new GlobTool());
    registry.register(new ListDirectoryTool());
    // 按内容搜索。没有它时 explore/审查子代理只能 glob 出文件名、再逐个整文件读——
    // 「哪里用了这个 API」这类问题会白读几十个文件，而主 agent 一直有 grep。
    registry.register(new com.tom.rv2ide.ai.tool.GrepTool());
    // 注意：phone_uiautomator（以及其它 phone_* 设备控制工具）**不注册**给子 agent——
    // 它们是 SYSTEM 级设备控制能力，不属于「探索/审查/写码」的职责边界。
    // 相应地，本类构造 ToolContext 时**不注入** u2Proxy 端口（注册与注入是一对：
    // 只注册不注入会静默拿到 null，这里两者都刻意缺席，不是漏接）。

    if (mode == Mode.CODE) {
      registry.register(new FileWriteTool());
      registry.register(new FileEditTool());
      registry.register(new FileDeleteTool());
    }
    return registry.filtered(filter);
  }

  /** 子 agent 的代码动作端口：与主 agent 同构（写盘后经文档同步通知编辑器）。 */
  private static ReportedCodeActionsPort subCodeActionsPort() {
    ReportedCodeActionsPort port =
        new ReportedCodeActionsPort(
            id -> com.tom.rv2ide.lsp.api.ILanguageServerRegistry.getDefault().getServer(id));
    port.setDocumentSync(new ReportedDocumentSyncPort());
    return port;
  }

  /**
   * 构造角色 agent 的系统提示词。
   *
   * <p>与 {@link #buildSubPrompt} 的区别：角色提示词由预设/用户定义，是本 agent 的
   * **主要指令**；这里只在它前后补上运行环境（工作区、实际工具清单、文本调用格式），
   * 而不是用通用措辞替换它。
   */
  private String buildRolePrompt(
      String rolePrompt,
      List<com.tom.rv2ide.ai.tool.api.ToolInfo> tools,
      boolean nativeTools) {
    StringBuilder sb = new StringBuilder();
    sb.append(rolePrompt.trim()).append("\n\n");

    sb.append("[ 运行环境 ]\n");
    sb.append("你是一个被委派的子 agent，看不到主对话的历史，也无法与用户对话。\n");
    if (workspacePath != null && !workspacePath.isEmpty()) {
      sb.append("工作区根目录: ").append(workspacePath).append('\n');
      sb.append("工具路径参数请使用相对于根目录的路径。\n");
    }
    sb.append('\n');

    sb.append("[ 你可用的工具 ]\n");
    if (tools == null || tools.isEmpty()) {
      sb.append("(当前没有可用工具)\n");
    } else {
      for (com.tom.rv2ide.ai.tool.api.ToolInfo tool : tools) {
        sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append('\n');
      }
    }
    sb.append('\n');

    if (!nativeTools) {
      sb.append("[ 工具调用格式 ]\n");
      sb.append("用下面的 XML 形式表达工具调用（可以一次多个）：\n");
      sb.append("<tool_calls>\n");
      sb.append("<tool_call name=\"file_read\">\n");
      sb.append("<argument name=\"file_path\">app/build.gradle.kts</argument>\n");
      sb.append("</tool_call>\n");
      sb.append("</tool_calls>\n\n");
    }

    sb.append("[ 注意 ]\n");
    sb.append("- 你只有上面列出的工具；不在其中的操作你无法执行，不要尝试或假装执行。\n");
    sb.append("- 修改文件前先读取其当前内容，不要凭猜测覆盖。\n");
    sb.append("- 工具返回错误时，阅读错误信息并调整做法。\n");
    sb.append("- 最后必须给出一段结论：主 agent 只会看到这段文字，看不到你的过程。\n");
    return sb.toString();
  }

  /**
   * 构造子 agent 的系统提示词。
   *
   * <p><b>刻意不复用主提示词构建器</b>：子 agent 的首要约束是「完成这一个被委派的
   * 任务并给出结论」，而不是像主 agent 那样与用户协作、维护待办、跨轮保持进度。
   * 主提示词里的对话式措辞（「不要只在回复里描述你打算怎么做」「任务完成后说明你做了
   * 什么」）在子 agent 语境下会稀释焦点。这里给一段专门的、更短的说明。
   */
  private String buildSubPrompt(
      Mode mode,
      List<com.tom.rv2ide.ai.tool.api.ToolInfo> tools,
      boolean nativeTools) {
    StringBuilder sb = new StringBuilder();
    sb.append("你是 ACS AI Agent 的子 agent，负责完成一个被委派的子任务。\n\n");

    sb.append("[ 工作方式 ]\n");
    sb.append("只专注完成下面这一个任务，不要做任务之外的改动。\n");
    if (mode == Mode.EXPLORE) {
      sb.append("这是**只读调查**任务：你只有读类工具，无法修改任何文件。\n");
      sb.append("调查完成后，给出明确的结论与依据（文件路径 + 行号）。\n");
    } else {
      sb.append("这是**可写执行**任务：你可以修改文件来完成它。\n");
      sb.append("改动完成后，说明你改了什么、为什么这样改。\n");
    }
    sb.append("你无法与用户对话，也没有主对话的历史——只看任务描述本身。\n");
    sb.append("最后必须给出一段结论：主 agent 只会看到这段文字，看不到你的过程。\n\n");

    sb.append("[ 工作区 ]\n");
    if (workspacePath != null && !workspacePath.isEmpty()) {
      sb.append("根目录: ").append(workspacePath).append('\n');
      sb.append("路径参数请使用相对于根目录的路径。\n");
    }
    sb.append('\n');

    sb.append("[ 可用工具 ]\n");
    if (tools == null || tools.isEmpty()) {
      sb.append("(当前没有可用工具)\n");
    } else {
      for (com.tom.rv2ide.ai.tool.api.ToolInfo tool : tools) {
        sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append('\n');
      }
    }
    sb.append('\n');

    if (!nativeTools) {
      sb.append("[ 工具调用格式 ]\n");
      sb.append("用下面的 XML 形式表达工具调用（可以一次多个）：\n");
      sb.append("<tool_calls>\n");
      sb.append("<tool_call name=\"file_read\">\n");
      sb.append("<argument name=\"file_path\">app/build.gradle.kts</argument>\n");
      sb.append("</tool_call>\n");
      sb.append("</tool_calls>\n\n");
    }

    sb.append("[ 注意 ]\n");
    sb.append("- 修改文件前先读取其当前内容，不要凭猜测覆盖。\n");
    sb.append("- 工具返回错误时，阅读错误信息并调整做法。\n");
    sb.append("- 结论要具体：给出文件路径与关键代码位置，不要只说「已经完成」。\n");
    return sb.toString();
  }

}
