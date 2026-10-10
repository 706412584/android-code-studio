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

package com.tom.rv2ide.ai.tool;

import java.util.List;

/**
 * 编译诊断端口：把 IDE 语言服务器（LSP）的静态分析结果暴露给 AI 工具层。
 *
 * <p><b>为什么用中性类型而不是直接返回 LSP 模型</b>：本模块是纯 JVM、零 Android 依赖的
 * （见 {@code core/ai-tool/build.gradle.kts} 只依赖 {@code ai-tool-api}），
 * 而 {@code DiagnosticItem} / {@code DiagnosticResult} 位于 {@code core/lsp-models}。
 * 把 LSP 类型引进来会让本模块依赖整个 LSP 栈，也会破坏可在 JVM 上单测的前提。
 * 因此这里只定义纯数据的 {@link Item} / {@link Report}，由 app 层的适配器完成转换。
 *
 * <p><b>这是 ACS 相对通用编码 agent 的独有能力</b>：通用 agent 只能靠跑一遍编译器
 * 才知道自己改对没有（分钟级）；IDE 内置的语言服务器已经持有建立好的符号索引，
 * 一次 {@code analyze} 是秒级，且给出的是带行列号与严重级别的结构化诊断。
 */
public interface DiagnosticsPort {

  /**
   * 对单个文件做静态分析。
   *
   * <p><b>超时为什么要显式传入</b>：两种调用场景的等待预算相差一个数量级——
   * 独立的 {@code diagnostics} 工具可以等 20s（首次分析要建符号索引），
   * 而挂在编辑工具上的搭车诊断必须严格有界（2s），否则每次编辑都会被拖住。
   * 与其在实现里猜，不如让调用方声明自己的预算。
   *
   * @param absolutePath 文件的绝对路径
   * @param timeoutMs 本次分析的时间上限（毫秒）；实现**必须**在有界时间内返回
   * @return 诊断报告；文件不受支持或无可用语言服务器时返回 {@link Report#unavailable}
   */
  Report analyze(String absolutePath, long timeoutMs);

  /**
   * 本端口当前是否可用（是否已连接语言服务器）。
   *
   * <p>不可用时 {@link #unavailableReason()} 必须给出可执行的说明，
   * 让模型知道「该去项目里打开文件」而不是反复重试。
   */
  boolean isAvailable();

  /** 端口不可用的原因；可用时返回空串。 */
  String unavailableReason();

  /** 一条诊断。行列均从 0 开始（LSP 约定）。 */
  final class Item {
    private final int line;
    private final int column;
    private final int severity;
    private final String message;
    private final String code;

    public Item(int line, int column, int severity, String message, String code) {
      this.line = line;
      this.column = column;
      this.severity = severity;
      this.message = message == null ? "" : message;
      this.code = code == null ? "" : code;
    }

    public int getLine() {
      return line;
    }

    public int getColumn() {
      return column;
    }

    /**
     * 严重级别。
     *
     * <p>取值与 LSP 对齐：1=Error，2=Warning，3=Info，4=Hint。
     * 用 int 而非枚举是为了不让本模块依赖 LSP 模型的枚举定义。
     */
    public int getSeverity() {
      return severity;
    }

    public String getMessage() {
      return message;
    }

    /** 诊断码（如 {@code unused}）；无码时为空串。 */
    public String getCode() {
      return code;
    }

    /** 级别的可读名，供格式化输出。 */
    public String severityName() {
      switch (severity) {
        case 1:
          return "ERROR";
        case 2:
          return "WARNING";
        case 3:
          return "INFO";
        case 4:
          return "HINT";
        default:
          return "UNKNOWN";
      }
    }
  }

  /**
   * 一次分析的结果。
   *
   * <p>三种状态互斥，由工厂方法保证：
   * <ul>
   *   <li>{@link #unavailable} —— 没有可用的语言服务器（原因见 {@link #getUnavailableReason()}）
   *   <li>{@link #failed} —— 服务器存在但分析过程出错
   *   <li>{@link #of} —— 分析完成，{@link #getItems()} 可能为空（即无诊断 = 干净）
   * </ul>
   *
   * <p><b>为什么把「不可用」与「无诊断」分开</b>：两者对模型的含义完全相反——
   * 前者是「查不了」，后者是「查过了，没问题」。混成一个空列表会让模型把
   * 「语言服务器没启动」误读成「代码干净」，这正是静默失败最有害的形态。
   */
  final class Report {
    private final List<Item> items;
    private final String unavailableReason;
    private final String failureReason;

    private Report(List<Item> items, String unavailableReason, String failureReason) {
      this.items = items;
      this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
      this.failureReason = failureReason == null ? "" : failureReason;
    }

    /** 分析完成（可能有 0 条诊断）。 */
    public static Report of(List<Item> items) {
      return new Report(items == null ? java.util.Collections.emptyList() : items, null, null);
    }

    /** 无可用语言服务器。 */
    public static Report unavailable(String reason) {
      return new Report(java.util.Collections.emptyList(), reason, null);
    }

    /** 语言服务器存在但分析失败。 */
    public static Report failed(String reason) {
      return new Report(java.util.Collections.emptyList(), null, reason);
    }

    public List<Item> getItems() {
      return items;
    }

    public String getUnavailableReason() {
      return unavailableReason;
    }

    public String getFailureReason() {
      return failureReason;
    }

    public boolean isUnavailable() {
      return !unavailableReason.isEmpty();
    }

    public boolean isFailed() {
      return !failureReason.isEmpty();
    }
  }
}
