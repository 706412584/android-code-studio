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

/**
 * 代码格式化端口：把语言服务器的 {@code formatCode} 暴露给 AI 工具层。
 *
 * <p>中性类型（本模块不能依赖 LSP 模型）。app 层适配器负责「读文件 → formatCode →
 * 应用 edits → 写回」这一整套，工具层只关心「格式化这个文件，告诉我改了多少」。
 */
public interface CodeFormatPort {

  /**
   * 格式化单个文件（**就地写回**）。
   *
   * @param absolutePath 文件绝对路径
   * @param timeoutMs 时间上限；实现必须返内有界返回
   * @return 格式化结果
   */
  Result format(String absolutePath, long timeoutMs);

  /** 该文件类型是否支持格式化。 */
  boolean isSupported(String filePath);

  /** 是否有可用的语言服务器。 */
  boolean isAvailable();

  /** 不可用原因；可用时返回空串。 */
  String unavailableReason();

  /**
   * 格式化结果。
   *
   * <p>注意把「没有改动」与「不可用/失败」分开：前者是成功（代码本来就规范），
   * 后者是没做成，两者对模型的含义完全不同。
   */
  final class Result {
    private final boolean changed;
    private final int changedChars;
    private final String unavailableReason;
    private final String failureReason;
    private final boolean timedOut;

    private Result(
        boolean changed,
        int changedChars,
        String unavailableReason,
        String failureReason,
        boolean timedOut) {
      this.changed = changed;
      this.changedChars = changedChars;
      this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
      this.failureReason = failureReason == null ? "" : failureReason;
      this.timedOut = timedOut;
    }

    /** 格式化完成；{@code changed} 表示是否真的改了内容。 */
    public static Result done(boolean changed, int changedChars) {
      return new Result(changed, changedChars, null, null, false);
    }

    /** 无可用语言服务器。 */
    public static Result unavailable(String reason) {
      return new Result(false, 0, reason, null, false);
    }

    /** 执行失败（非超时）。 */
    public static Result failed(String reason) {
      return new Result(false, 0, null, reason, false);
    }

    /** 超时。 */
    public static Result timedOut(String reason) {
      return new Result(false, 0, null, reason, true);
    }

    public boolean isChanged() {
      return changed;
    }

    public int getChangedChars() {
      return changedChars;
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
  }
}
