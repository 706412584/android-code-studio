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

/**
 * 运行一个子 agent（嵌套的 agent 循环）。
 *
 * <p><b>为什么是接口</b>：{@code ai-tool} 不能依赖 {@code ai-agent}（后者依赖前者，
 * 会形成循环），而「跑一个 agent 循环」正是 {@code ai-agent} 的能力。接口放在这里、
 * 实现放在 app 层，是这套分层里既有的做法（同 {@code HttpPort} / {@code ToolSettingsPort}）。
 *
 * <p><b>子 agent 的价值在于上下文隔离</b>。主 agent 派一个子任务出去调查「这个模块的
 * 依赖关系」，子 agent 可能读二十个文件、跑几次搜索——这些过程**不会进入主对话**，
 * 只有它最后的结论回来。主 agent 的上下文因此保持干净，可以继续做别的。若把同样的
 * 调查放在主循环里做，二十个文件的全文会永久占据上下文窗口。
 */
public interface SubAgentRunner {

  /** 子 agent 的工作模式。 */
  enum Mode {
    /**
     * 只读调查：可以读文件、搜索、查文档，但不得修改任何东西。
     *
     * <p>用于「搞清楚这块代码怎么工作」「找出所有调用点」这类任务。
     */
    EXPLORE,

    /**
     * 可写执行：能改文件、执行命令。
     *
     * <p>用于「把这块重构掉」这类需要落地改动的子任务。
     */
    CODE;

    /** 按名字解析；未知或 null 回退到 {@link #EXPLORE}。 */
    public static Mode fromName(String name) {
      if (name == null) {
        return EXPLORE;
      }
      String value = name.trim().toLowerCase(java.util.Locale.ROOT);
      if ("code".equals(value) || "sub-coding".equals(value) || "subcoding".equals(value)) {
        return CODE;
      }
      return EXPLORE;
    }

    public String wireName() {
      return this == CODE ? "code" : "explore";
    }
  }

  /** 一次子 agent 请求。 */
  final class Request {
    private final String task;
    private final Mode mode;

    /** 当前嵌套深度（调用方所在层）。子 agent 内部再调用时会 +1。 */
    private final int depth;

    /** 角色专用系统提示词；null 表示使用通用的子 agent 提示词。 */
    private final String systemPrompt;

    /** 工具名白名单；{@link ToolNameFilter#unrestricted()} 表示不限制。 */
    private final ToolNameFilter toolFilter;

    /**
     * 步骤进度接收者；null 表示不报告。
     *
     * <p><b>为什么是「步骤」而不是完整事件流</b>：子 agent 的中间过程刻意不进主对话
     * （见类注释），但「它此刻在做什么」用户有权看到——一个跑了十几步的子 agent
     * 若界面毫无动静，看起来和卡死没有区别。步骤行只报关键节点（工具调用、轮次），
     * 不转发文本与推理增量：那些是子 agent 的私有上下文，转发会破坏隔离带来的收益。
     */
    private final ProgressListener progress;

    public Request(String task, Mode mode, int depth) {
      this(task, mode, depth, null, ToolNameFilter.unrestricted(), null);
    }

    /**
     * 带角色提示词与工具白名单的请求（内置/自定义角色 agent 使用）。
     *
     * @param systemPrompt 角色专用提示词；null 或空则回退到通用子 agent 提示词
     * @param toolFilter 工具白名单；null 视作不限制。执行方**必须**据此裁剪注册的工具——
     *     这是能力边界，不是建议
     */
    public Request(
        String task, Mode mode, int depth, String systemPrompt, ToolNameFilter toolFilter) {
      this(task, mode, depth, systemPrompt, toolFilter, null);
    }

    /**
     * 完整构造。
     *
     * @param progress 步骤进度接收者；null 表示不报告。实现方**必须**容忍回调抛异常
     *     （进度是尽力而为的旁路，不应让子 agent 运行失败），且回调可能来自任意线程
     */
    public Request(
        String task,
        Mode mode,
        int depth,
        String systemPrompt,
        ToolNameFilter toolFilter,
        ProgressListener progress) {
      this.task = task == null ? "" : task;
      this.mode = mode == null ? Mode.EXPLORE : mode;
      this.depth = depth;
      this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
      this.toolFilter = toolFilter == null ? ToolNameFilter.unrestricted() : toolFilter;
      this.progress = progress;
    }

    /** 交给子 agent 的任务描述。 */
    public String getTask() {
      return task;
    }

    public Mode getMode() {
      return mode;
    }

    public int getDepth() {
      return depth;
    }

    /** 角色专用提示词；空串表示使用通用的子 agent 提示词。 */
    public String getSystemPrompt() {
      return systemPrompt;
    }

    /** 工具白名单；永不返回 null。 */
    public ToolNameFilter getToolFilter() {
      return toolFilter;
    }

    /** 步骤进度接收者；可能为 null（调用方不关心进度）。 */
    public ProgressListener getProgress() {
      return progress;
    }
  }

  /**
   * 子 agent 步骤进度的接收者。
   *
   * <p>回调在子 agent 的运行线程上触发。实现方**不得**在此做重活或阻塞——
   * 它是旁路通道，慢下来会拖住子 agent 本身。
   */
  public interface ProgressListener {
    /** 一行面向用户的步骤描述（如「子 agent 步骤 3/40 · file_read: app/build.gradle.kts」）。 */
    void onProgress(String message);
  }

  /** 子 agent 的执行结果。 */
  final class Result {
    private final boolean success;
    private final String output;
    private final int turns;
    private final int toolCallCount;

    public Result(boolean success, String output, int turns, int toolCallCount) {
      this.success = success;
      this.output = output == null ? "" : output;
      this.turns = turns;
      this.toolCallCount = toolCallCount;
    }

    public boolean isSuccess() {
      return success;
    }

    /** 子 agent 的最终结论。这是唯一回到主对话的内容。 */
    public String getOutput() {
      return output;
    }

    public int getTurns() {
      return turns;
    }

    public int getToolCallCount() {
      return toolCallCount;
    }
  }

  /**
   * 运行子 agent。
   *
   * <p>实现方需负责：把子 agent 的工具调用与中间输出**隔离**在自己的上下文里，
   * 只把最终结论写进 {@link Result#getOutput()}；并在父级取消时一并取消子级。
   */
  Result run(Request request) throws Exception;
}
