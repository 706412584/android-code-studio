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

package com.tom.rv2ide.ai.tool;

import java.util.Collections;
import java.util.List;

/**
 * IDE 原生代码动作端口：把语言服务器的「类型系统感知的代码生成/重构」暴露给 AI 工具层。
 *
 * <p><b>与 CodeFormatPort 的差异</b>：格式化是「文件级、无位置」；代码动作需要
 * **位置**（大多数动作在光标处符号上生效）与 **action 名**（同一位置可能有多种动作）。
 *
 * <p><b>为什么用中性类型</b>：本模块零 Android/LSP 依赖（见 core/ai-tool/build.gradle.kts），
 * 而动作执行需要 JavaCompilerService / Rewrite / TextEdit（位于 java/lsp 与 core/lsp-models）。
 * 由 app 层适配器完成「拿编译器 → 构造 Rewrite → 应用 edits → 写盘」。
 */
public interface CodeActionsPort {

  /** 一次代码动作的执行结果。 */
  final class Result {
    private final boolean changed;
    private final int changedChars;
    private final List<String> changedFiles;   // 相对工作区的展示路径
    private final String summary;              // 例如「已插入 3 个抽象方法」
    private final String unavailableReason;
    private final String failureReason;
    private final boolean timedOut;
    private final boolean unsupportedAction;   // action 名不在本端口支持集合内

    private Result(
        boolean changed,
        int changedChars,
        List<String> changedFiles,
        String summary,
        String unavailableReason,
        String failureReason,
        boolean timedOut,
        boolean unsupportedAction) {
      this.changed = changed;
      this.changedChars = changedChars;
      this.changedFiles =
          changedFiles == null ? Collections.<String>emptyList() : Collections.unmodifiableList(changedFiles);
      this.summary = summary == null ? "" : summary;
      this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
      this.failureReason = failureReason == null ? "" : failureReason;
      this.timedOut = timedOut;
      this.unsupportedAction = unsupportedAction;
    }

    public static Result done(boolean changed, int changedChars,
                              List<String> changedFiles, String summary) {
      return new Result(changed, changedChars, changedFiles, summary, null, null, false, false);
    }

    public static Result unavailable(String reason) {
      return new Result(false, 0, null, null, reason, null, false, false);
    }

    public static Result failed(String reason) {
      return new Result(false, 0, null, null, null, reason, false, false);
    }

    public static Result timedOut(String reason) {
      return new Result(false, 0, null, null, null, reason, true, false);
    }

    public static Result unsupported(String reason) {
      return new Result(false, 0, null, null, null, reason, false, true);
    }

    public boolean isChanged() {
      return changed;
    }

    public int getChangedChars() {
      return changedChars;
    }

    public List<String> getChangedFiles() {
      return changedFiles;
    }

    public String getSummary() {
      return summary;
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

    public boolean isTimedOut() {
      return timedOut;
    }

    public boolean isUnsupportedAction() {
      return unsupportedAction;
    }
  }

  /**
   * 在文件的指定位置执行一个代码动作（**就地写回**）。
   *
   * @param absolutePath 文件绝对路径
   * @param action       action 名（见 {@link #supportedActions()}），如 "implement_abstract_methods"
   * @param line         行号（**0 基**，与 LSP 一致；工具层负责从模型的 1 基转换）
   * @param column       列号（0 基）
   * @param argument     动作的可选参数（如 add_import 的 "java.util.List"；无则空串）
   * @param timeoutMs    时间上限；实现必须**在有界时间内返回**
   * @return 执行结果
   */
  Result perform(String absolutePath, String action, int line, int column,
                 String argument, long timeoutMs);

  /** 本端口支持的动作名清单（工具层据此做前置校验，避免与适配器各维护一份）。 */
  List<String> supportedActions();

  /** 该文件类型是否支持代码动作（仅 .java）。 */
  boolean isSupported(String filePath);

  /** 是否有可用的语言服务器。 */
  boolean isAvailable();

  /** 不可用原因；可用时返回空串。 */
  String unavailableReason();
}
