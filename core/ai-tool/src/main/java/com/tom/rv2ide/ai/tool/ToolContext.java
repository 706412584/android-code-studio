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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ImageDataProvider;
import java.util.Collections;
import java.util.List;

/**
 * 单次工具执行所需的上下文。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code ToolContext} 有 16 个字段，混合了工作区信息、
 * 7 个数据层仓库、子 agent 运行器、字符串解析器等，导致工具层与 Room、SSH、UI 强耦合。
 *
 * <p>这里只保留工具执行真正需要的 5 项。被移除的能力及替代方式：
 * <ul>
 *   <li>子 agent 相关（{@code AgentRunner} / {@code AgentResultStore}）— 本移植不含子 agent
 *   <li>SSH 相关（{@code SshFileTreeStore}）— 由 shell 后端抽象替代
 *   <li>{@code TodoStateStore} / {@code LearningContextStore} — 不在本移植范围
 *   <li>{@code StringResolver} — 工具不再依赖 Android 字符串资源，错误文案改用常量
 * </ul>
 *
 * <p>本类不引用任何 Android 类型，因此工具模块可在 JVM 上单元测试。
 */
public final class ToolContext {

  /** 工作区根目录的绝对路径。工具只能在此目录（及其 {@link #getExtraWriteRoots()}）内操作。 */
  private final String homePath;

  /**
   * 编辑器**当前打开**的项目根目录；未打开项目时为空串。
   *
   * <p><b>为什么需要它</b>：会话可以绑定到与编辑器不同的项目（跨项目会话）。文件类
   * 工具按 {@link #homePath}（会话 cwd）隔离，但构建/变体类工具走全局单例的 Gradle
   * 构建服务——那个服务只服务于编辑器打开的项目。两者不一致时若不拦，构建会
   * **静默作用于错误的项目**（数据损坏级）。见 {@link #getProjectMismatchReason()}。
   */
  private final String openProjectPath;

  /** 额外允许写入的根目录，用于技能目录等白名单场景。 */
  private final List<String> extraWriteRoots;

  /** 为 true 时跳过 {@link com.tom.rv2ide.ai.tool.builtin.FileToolPathPolicy} 的工作区限制。 */
  private final boolean bypassPathProtection;

  /** 当前 tool call 的 id，用于把执行进度与结果关联回对话。 */
  private final String toolCallId;

  /**
   * 产生本次工具调用的会话 id。
   *
   * <p><b>为什么工具上下文需要它</b>：危险工具的一次性授权（「本次运行内允许」）按会话隔离，
   * 否则并行会话之间会互相放行——在 A 项目批准的危险命令会在 B 项目静默执行。
   * 空串表示无会话（单测、后台一次性调用），此时退化为不按会话隔离。
   */
  private final String conversationId;

  /** 执行过程中的进度回调，可为 null。 */
  private final ProgressListener progressListener;

  private final ToolSettingsPort settings;

  /**
   * 图片解码/缩放实现；由 app 层注入，未注入时为 null。
   *
   * <p>工具模块是纯 Java、零 Android 依赖的 java-library，不能直接引用
   * {@code android.graphics}。读取图片时把缩放这一步委托给该端口。
   */
  private final ImageDataProvider imageDataProvider;

  /**
   * 图片生成端点配置；未配置时为 null。
   *
   * <p>由 app 层在构建上下文时注入（读「能力」页偏好），工具层不感知 Android 存储。
   */
  private final ImageGenerationEndpoint imageGeneration;

  /** 视频生成端点配置；未配置时为 null（能力页「视频生成」块下发）。 */
  private final VideoGenerationEndpoint videoGeneration;

  /**
   * 编译诊断端口（IDE 语言服务器）；未注入时为 null。
   *
   * <p>由 app 层在构建上下文时注入——诊断需要 LSP 栈，而本模块零 Android 依赖。
   */
  private final DiagnosticsPort diagnostics;

  /**
   * IDE 自身日志源；未注入时为 null。
   *
   * <p>用于让 AI 读到工具链内部被吞掉的错误（协议层/工具层异常）。
   * 与 {@code logcat_read} 目标不同——那个读被测应用。
   */
  private final IdeLogSource ideLog;

  /** LSP 导航端口（跳定义/找引用）；未注入时为 null。 */
  private final LspNavigationPort lspNavigation;

  /** 代码格式化端口；未注入时为 null。 */
  private final CodeFormatPort codeFormat;

  /**
   * 后台命令执行端口；未注入时为 null。
   *
   * <p>为 null 时 {@code shell_execute} 的 {@code run_in_background} 参数不可用，
   * {@code task_output} / {@code task_stop} 会明确报「未接入」——而不是假装成功。
   */
  private final BackgroundShellPort backgroundShell;

  /**
   * 文档同步端口；未注入时为 null。
   *
   * <p>为 null 时写盘后不通知编辑器（旧行为），不影响写盘本身。
   */
  private final DocumentSyncPort documentSync;

  /** 终端会话读取端口；未注入时为 null（终端工具明确报「未接入」）。 */
  private final TerminalPort terminal;

  /**
   * 本次运行的取消信号；未注入时为 null（不接取消）。
   *
   * <p>长操作（shell 命令、Gradle 构建、codegraph 查询）在等待循环里查询它，
   * 用户点「停止」后立即终止底层进程——否则停止只停住 agent 循环，正在跑的
   * 命令仍会跑满（实测点停止后 sleep 45 照跑满）。
   */
  private final CancellationSignal cancellation;

  /** 取消信号：长操作据此判断是否应尽快终止。 */
  public interface CancellationSignal {
    boolean isCancelled();
  }

  /** 编辑器会话端口（读状态/打开定位/保存全部）；未注入时为 null。 */
  private final EditorSessionPort editorSession;

  /** IDE 原生代码动作端口；未注入时为 null。 */
  private final CodeActionsPort codeActions;

  /** 构建变体端口（列出/切换）；未注入时为 null。 */
  private final BuildVariantPort buildVariant;

  /** 工作区符号搜索端口；未注入时为 null。 */
  private final WorkspaceSymbolPort workspaceSymbol;

  /** 图像处理管线端口（解码/编码/缩放）；未注入时为 null（image_tool 明确报「未接入」）。 */
  private final ImageProcessingPort imageProcessing;

  /** OCR 端口；未注入时为 null（phone_ocr 明确报「未接入」）。 */
  private final OcrPort ocr;

  /** uiautomator2 代理端口；未注入时为 null（phone_uiautomator 明确报「未接入」）。 */
  private final U2ProxyPort u2Proxy;

  /** 跨会话/跨子代理发消息端口；未注入时为 null（send_message 明确报「未接入」）。 */
  private final SessionMessagePort sessionMessage;

  private ToolContext(Builder builder) {
    this.homePath = builder.homePath == null ? "" : builder.homePath;
    this.openProjectPath = builder.openProjectPath == null ? "" : builder.openProjectPath;
    this.extraWriteRoots =
        builder.extraWriteRoots == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(builder.extraWriteRoots);
    this.bypassPathProtection = builder.bypassPathProtection;
    this.toolCallId = builder.toolCallId == null ? "" : builder.toolCallId;
    this.conversationId = builder.conversationId == null ? "" : builder.conversationId;
    this.progressListener = builder.progressListener;
    this.settings = builder.settings == null ? ToolSettingsPort.defaults() : builder.settings;
    this.imageDataProvider = builder.imageDataProvider;
    this.imageGeneration = builder.imageGeneration;
    this.videoGeneration = builder.videoGeneration;
    this.diagnostics = builder.diagnostics;
    this.ideLog = builder.ideLog;
    this.lspNavigation = builder.lspNavigation;
    this.codeFormat = builder.codeFormat;
    this.backgroundShell = builder.backgroundShell;
    this.documentSync = builder.documentSync;
    this.terminal = builder.terminal;
    this.cancellation = builder.cancellation;
    this.editorSession = builder.editorSession;
    this.codeActions = builder.codeActions;
    this.buildVariant = builder.buildVariant;
    this.workspaceSymbol = builder.workspaceSymbol;
    this.imageProcessing = builder.imageProcessing;
    this.ocr = builder.ocr;
    this.u2Proxy = builder.u2Proxy;
    this.sessionMessage = builder.sessionMessage;
  }

  public String getHomePath() {
    return homePath;
  }

  /** 编辑器当前打开的项目根目录；未打开项目时为空串。 */
  public String getOpenProjectPath() {
    return openProjectPath;
  }

  /**
   * 检查「会话绑定的项目」与「编辑器当前打开的项目」是否一致。
   *
   * <p>构建类工具（{@code gradle_build} / {@code build_variant} / {@code test_coverage}）
   * 走全局单例的 Gradle 构建服务，后者只服务于编辑器打开的项目。会话绑定别的项目时，
   * 这些工具若不拦会**静默构建错误的项目**——文件工具作用于 B、构建却作用于 A，
   * 产物与改动分属两个项目。宁可报错让用户显式处理。
   *
   * <p>以下情况一律视为「无法判定/无需拦截」，返回空串：
   * <ul>
   *   <li>{@code openProjectPath} 为空（编辑器没打开项目）——错误由工具自身的
   *       「构建服务不可用」分支报出，本方法不重复；
   *   <li>{@code homePath} 为空（无会话工作区）；
   *   <li>两者相等（正常场景）。
   * </ul>
   *
   * @return 不一致时的可执行错误文案；无需拦截时为空串
   */
  public String getProjectMismatchReason() {
    if (openProjectPath.isEmpty() || homePath.isEmpty()) {
      return "";
    }
    String session = stripTrailingSeparators(homePath);
    String open = stripTrailingSeparators(openProjectPath);
    if (session.equals(open)) {
      return "";
    }
    return "本会话绑定的项目是 "
        + session
        + "，而编辑器当前打开的是 "
        + open
        + "。构建类工具（gradle_build / build_variant / test_coverage）只能作用于"
        + "编辑器打开的项目——请先打开目标项目的编辑器再构建，"
        + "或改用 shell_execute 在会话项目里直接跑 ./gradlew。";
  }

  /** 去掉结尾的路径分隔符（`/x/y/` 与 `/x/y` 视为同一目录）。 */
  private static String stripTrailingSeparators(String path) {
    String value = path;
    while (value.length() > 1 && (value.endsWith("/") || value.endsWith("\\"))) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  public List<String> getExtraWriteRoots() {
    return extraWriteRoots;
  }

  public boolean isBypassPathProtection() {
    return bypassPathProtection;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  /** 产生本次工具调用的会话 id；空串表示无会话。 */
  public String getConversationId() {
    return conversationId;
  }

  public ProgressListener getProgressListener() {
    return progressListener;
  }

  public ToolSettingsPort getSettings() {
    return settings;
  }

  /** 图片解码/缩放实现；可能为 null（未注入时读图退化为不缩放）。 */
  public ImageDataProvider getImageDataProvider() {
    return imageDataProvider;
  }

  /** 图片生成端点配置；未配置时为 null（工具据此报「未配置」而不是发无效请求）。 */
  public ImageGenerationEndpoint getImageGeneration() {
    return imageGeneration;
  }

  /** 视频生成端点配置；未配置时为 null。 */
  public VideoGenerationEndpoint getVideoGeneration() {
    return videoGeneration;
  }

  /**
   * 编译诊断端口（IDE 语言服务器）；未注入时为 null。
   *
   * <p>为 null 时诊断工具会明确报「语言服务器不可用」，而不是返回空诊断列表——
   * 后者会被模型误读成「代码没问题」。
   */
  public DiagnosticsPort getDiagnostics() {
    return diagnostics;
  }

  /** IDE 自身日志源；未注入时为 null（工具据此报「未接入」而不是返回空日志）。 */
  public IdeLogSource getIdeLog() {
    return ideLog;
  }

  /** LSP 导航端口（跳定义/找引用）；未注入时为 null。 */
  public LspNavigationPort getLspNavigation() {
    return lspNavigation;
  }

  /** 代码格式化端口；未注入时为 null。 */
  public CodeFormatPort getCodeFormat() {
    return codeFormat;
  }

  /** 后台命令执行端口；未注入时为 null。 */
  public BackgroundShellPort getBackgroundShell() {
    return backgroundShell;
  }

  /** 文档同步端口；未注入时为 null。 */
  public DocumentSyncPort getDocumentSync() {
    return documentSync;
  }

  /** 终端会话读取端口；未注入时为 null。 */
  public TerminalPort getTerminal() {
    return terminal;
  }

  /** 本次运行的取消信号；未注入时为 null（长操作据此判断是否应终止）。 */
  public CancellationSignal getCancellation() {
    return cancellation;
  }

  /** 编辑器会话端口；未注入时为 null（编辑器工具报「未接入」）。 */
  public EditorSessionPort getEditorSession() {
    return editorSession;
  }

  /** IDE 原生代码动作端口；未注入时为 null。 */
  public CodeActionsPort getCodeActions() {
    return codeActions;
  }

  /** 构建变体端口；未注入时为 null。 */
  public BuildVariantPort getBuildVariant() {
    return buildVariant;
  }

  /** 工作区符号搜索端口；未注入时为 null。 */
  public WorkspaceSymbolPort getWorkspaceSymbol() {
    return workspaceSymbol;
  }

  /** 图像处理管线端口；未注入时为 null（image_tool 明确报「未接入」）。 */
  public ImageProcessingPort getImageProcessing() {
    return imageProcessing;
  }

  /** OCR 端口；未注入时为 null（phone_ocr 明确报「未接入」）。 */
  public OcrPort getOcr() {
    return ocr;
  }

  /** uiautomator2 代理端口；未注入时为 null（phone_uiautomator 明确报「未接入」）。 */
  public U2ProxyPort getU2Proxy() {
    return u2Proxy;
  }

  /** 跨会话/跨子代理发消息端口；未注入时为 null（send_message 明确报「未接入」）。 */
  public SessionMessagePort getSessionMessage() {
    return sessionMessage;
  }

  /**
   * 返回一个仅替换 {@code toolCallId} 的副本。
   *
   * <p>执行器在派发到具体工具前用当前调用的 id 覆盖上下文，使工具内部的进度与错误
   * 能关联回正确的调用，而调用方传入的其它字段（工作区、权限设置等）保持不变。
   */
  public ToolContext withToolCallId(String newToolCallId) {
    return builder()
        .homePath(this.homePath)
        .openProjectPath(this.openProjectPath)
        .extraWriteRoots(this.extraWriteRoots)
        .bypassPathProtection(this.bypassPathProtection)
        .toolCallId(newToolCallId)
        .conversationId(this.conversationId)
        .progressListener(this.progressListener)
        .settings(this.settings)
        .imageDataProvider(this.imageDataProvider)
        .imageGeneration(this.imageGeneration)
        .videoGeneration(this.videoGeneration)
        .diagnostics(this.diagnostics)
        .ideLog(this.ideLog)
        .lspNavigation(this.lspNavigation)
        .codeFormat(this.codeFormat)
        .backgroundShell(this.backgroundShell)
        .documentSync(this.documentSync)
        .terminal(this.terminal)
        .cancellation(this.cancellation)
        .editorSession(this.editorSession)
        .codeActions(this.codeActions)
        .buildVariant(this.buildVariant)
        .workspaceSymbol(this.workspaceSymbol)
        .imageProcessing(this.imageProcessing)
        .ocr(this.ocr)
        .u2Proxy(this.u2Proxy)
        .sessionMessage(this.sessionMessage)
        .build();
  }

  /**
   * 返回一个仅替换 {@code progressListener} 的副本。
   *
   * <p>调用方（编排层）在事件流建好后用它接线进度端口——构建顺序决定进度监听器
   * 无法在 builder 链上一次性给出（事件流依赖持久化监听器，后者又依赖本上下文之外
   * 的会话信息）。其它字段原样保留。
   */
  public ToolContext withProgressListener(ProgressListener newProgressListener) {
    return builder()
        .homePath(this.homePath)
        .openProjectPath(this.openProjectPath)
        .extraWriteRoots(this.extraWriteRoots)
        .bypassPathProtection(this.bypassPathProtection)
        .toolCallId(this.toolCallId)
        .conversationId(this.conversationId)
        .progressListener(newProgressListener)
        .settings(this.settings)
        .imageDataProvider(this.imageDataProvider)
        .imageGeneration(this.imageGeneration)
        .videoGeneration(this.videoGeneration)
        .diagnostics(this.diagnostics)
        .ideLog(this.ideLog)
        .lspNavigation(this.lspNavigation)
        .codeFormat(this.codeFormat)
        .backgroundShell(this.backgroundShell)
        .documentSync(this.documentSync)
        .terminal(this.terminal)
        .cancellation(this.cancellation)
        .editorSession(this.editorSession)
        .codeActions(this.codeActions)
        .buildVariant(this.buildVariant)
        .workspaceSymbol(this.workspaceSymbol)
        .imageProcessing(this.imageProcessing)
        .ocr(this.ocr)
        .u2Proxy(this.u2Proxy)
        .sessionMessage(this.sessionMessage)
        .build();
  }

  /** 报告工具执行进度。无监听者时静默忽略。 */
  public void reportProgress(String message) {
    ProgressListener listener = this.progressListener;
    if (listener != null) {
      // 带上本上下文的工具调用 id：并行执行（一次派遣多个子 agent）时多个工具的
      // 进度交错到达，订阅方靠这个 id 把步骤行归到正确的卡片上。
      listener.onProgress(this.toolCallId, message);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** 工具执行进度的接收者。 */
  public interface ProgressListener {
    void onProgress(String message);

    /**
     * 带工具调用 id 的进度（并行执行时用于归卡）。
     *
     * <p>默认实现退化为 {@link #onProgress(String)}：已有的 lambda 实现只关心文本，
     * 无需改动；需要区分来源的实现（orchestrator 的事件桥）覆写本方法。
     *
     * @param callId 产生进度的工具调用 id；可能为空（无上下文的测试路径）
     */
    default void onProgress(String callId, String message) {
      onProgress(message);
    }
  }

  public static final class Builder {
    private String homePath;
    private String openProjectPath;
    private List<String> extraWriteRoots;
    private boolean bypassPathProtection;
    private String toolCallId;
    private String conversationId;
    private ProgressListener progressListener;
    private ToolSettingsPort settings;
    private ImageDataProvider imageDataProvider;
    private ImageGenerationEndpoint imageGeneration;
    private VideoGenerationEndpoint videoGeneration;
    private DiagnosticsPort diagnostics;
    private IdeLogSource ideLog;
    private LspNavigationPort lspNavigation;
    private CodeFormatPort codeFormat;
    private BackgroundShellPort backgroundShell;
    private DocumentSyncPort documentSync;
    private TerminalPort terminal;
    private CancellationSignal cancellation;
    private EditorSessionPort editorSession;
    private CodeActionsPort codeActions;
    private BuildVariantPort buildVariant;
    private WorkspaceSymbolPort workspaceSymbol;
    private ImageProcessingPort imageProcessing;
    private OcrPort ocr;
    private U2ProxyPort u2Proxy;
    private SessionMessagePort sessionMessage;

    public Builder homePath(String value) {
      this.homePath = value;
      return this;
    }

    /** 编辑器当前打开的项目根（构建类工具的归属校验用；未打开项目时留空）。 */
    public Builder openProjectPath(String value) {
      this.openProjectPath = value;
      return this;
    }

    public Builder extraWriteRoots(List<String> value) {
      this.extraWriteRoots = value;
      return this;
    }

    public Builder bypassPathProtection(boolean value) {
      this.bypassPathProtection = value;
      return this;
    }

    public Builder toolCallId(String value) {
      this.toolCallId = value;
      return this;
    }

    /** 产生本次调用的会话 id，用于危险工具授权的按会话隔离。 */
    public Builder conversationId(String value) {
      this.conversationId = value;
      return this;
    }

    public Builder progressListener(ProgressListener value) {
      this.progressListener = value;
      return this;
    }

    public Builder settings(ToolSettingsPort value) {
      this.settings = value;
      return this;
    }

    public Builder imageDataProvider(ImageDataProvider value) {
      this.imageDataProvider = value;
      return this;
    }

    public Builder imageGeneration(ImageGenerationEndpoint value) {
      this.imageGeneration = value;
      return this;
    }

    public Builder videoGeneration(VideoGenerationEndpoint value) {
      this.videoGeneration = value;
      return this;
    }

    /** 注入编译诊断端口（IDE 语言服务器适配器）。 */
    public Builder diagnostics(DiagnosticsPort value) {
      this.diagnostics = value;
      return this;
    }

    /** 注入 IDE 自身日志源。 */
    public Builder ideLog(IdeLogSource value) {
      this.ideLog = value;
      return this;
    }

    /** 注入 LSP 导航端口（跳定义/找引用）。 */
    public Builder lspNavigation(LspNavigationPort value) {
      this.lspNavigation = value;
      return this;
    }

    /** 注入代码格式化端口。 */
    public Builder codeFormat(CodeFormatPort value) {
      this.codeFormat = value;
      return this;
    }

    /** 注入后台命令执行端口。 */
    public Builder backgroundShell(BackgroundShellPort value) {
      this.backgroundShell = value;
      return this;
    }

    /** 注入文档同步端口。 */
    public Builder documentSync(DocumentSyncPort value) {
      this.documentSync = value;
      return this;
    }

    /** 注入终端会话读取端口。 */
    public Builder terminal(TerminalPort value) {
      this.terminal = value;
      return this;
    }

    /** 注入本次运行的取消信号。长操作（shell/构建）据此在用户点停止后尽快终止。 */
    public Builder cancellation(CancellationSignal value) {
      this.cancellation = value;
      return this;
    }

    /** 注入编辑器会话端口（editor_state / file_open / save_all）。 */
    public Builder editorSession(EditorSessionPort value) {
      this.editorSession = value;
      return this;
    }

    /** 注入 IDE 原生代码动作端口（code_actions）。 */
    public Builder codeActions(CodeActionsPort value) {
      this.codeActions = value;
      return this;
    }

    /** 注入构建变体端口（build_variant）。 */
    public Builder buildVariant(BuildVariantPort value) {
      this.buildVariant = value;
      return this;
    }

    /** 注入工作区符号搜索端口（workspace_symbol）。 */
    public Builder workspaceSymbol(WorkspaceSymbolPort value) {
      this.workspaceSymbol = value;
      return this;
    }

    /** 注入图像处理管线端口（image_tool）。 */
    public Builder imageProcessing(ImageProcessingPort value) {
      this.imageProcessing = value;
      return this;
    }

    /** 注入 OCR 端口（phone_ocr 使用）。 */
    public Builder ocr(OcrPort value) {
      this.ocr = value;
      return this;
    }

    /** 注入 uiautomator2 代理端口（phone_uiautomator 使用）。 */
    public Builder u2Proxy(U2ProxyPort value) {
      this.u2Proxy = value;
      return this;
    }

    /** 注入跨会话/跨子代理发消息端口（send_message 走它）。 */
    public Builder sessionMessage(SessionMessagePort value) {
      this.sessionMessage = value;
      return this;
    }

    public ToolContext build() {
      return new ToolContext(this);
    }
  }
}
