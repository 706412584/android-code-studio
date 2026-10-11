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
 * LSP 导航端口：把「跳到定义 / 找引用」暴露给 AI 工具层。
 *
 * <p>与 {@link DiagnosticsPort} 同样用中性类型（本模块不能依赖 LSP 模型），由 app 层适配。
 *
 * <p><b>为什么值得做</b>：改代码时最常问的两件事就是「这个方法/类型定义在哪」和
 * 「谁在用它」。IDE 的语言服务器**已经实现**了这两项（建立好的符号索引，秒级），
 * 而 AI 目前只能靠文本 grep 猜测——同名符号一多就会误判。
 */
public interface LspNavigationPort {

  /** 导航类型。 */
  enum Kind {
    /** 跳到光标处符号的定义。 */
    DEFINITION,
    /** 找出所有引用。 */
    REFERENCES
  }

  /**
   * 在文件的指定位置做导航。
   *
   * @param filePath 文件相对/绝对路径
   * @param line 行号（0 基，与 LSP 一致）
   * @param column 列号（0 基）
   * @param kind 定义 / 引用
   * @param timeoutMs 本次调用的时间上限（毫秒）；实现**必须**在有界时间内返回
   */
  Result navigate(String filePath, int line, int column, Kind kind, long timeoutMs);

  /** 该文件类型是否支持导航（由实现决定，避免工具层与适配器各维护一份）。 */
  boolean isSupported(String filePath);

  /** 是否有可用的语言服务器。 */
  boolean isAvailable();

  /** 不可用原因；可用时返回空串。 */
  String unavailableReason();

  /** 一处位置。行列从 0 起（LSP 约定）。 */
  final class Location {
    private final String filePath;
    private final int line;
    private final int column;

    public Location(String filePath, int line, int column) {
      this.filePath = filePath == null ? "" : filePath;
      this.line = line;
      this.column = column;
    }

    public String getFilePath() {
      return filePath;
    }

    public int getLine() {
      return line;
    }

    public int getColumn() {
      return column;
    }
  }

  /**
   * 导航结果。
   *
   * <p>与诊断一样把三种状态分开：
   * <ul>
   *   <li>{@link #empty} —— 查询成功但没有结果（例如该位置没有可跳转的符号）——这与
   *       「语言服务器不可用」对模型的含义完全不同，绝不能混为一谈
   *   <li>{@link #unavailable} —— 没有可用的语言服务器
   *   <li>{@link #failed} / {@link #timedOut} —— 执行失败 / 超时
   * </ul>
   */
  final class Result {
    private final List<Location> locations;
    private final String unavailableReason;
    private final String failureReason;
    private final boolean timedOut;

    private Result(
        List<Location> locations, String unavailableReason, String failureReason, boolean timedOut) {
      this.locations = locations == null ? java.util.Collections.emptyList() : locations;
      this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
      this.failureReason = failureReason == null ? "" : failureReason;
      this.timedOut = timedOut;
    }

    /** 查询完成（可能没有结果）。 */
    public static Result of(List<Location> locations) {
      return new Result(locations, null, null, false);
    }

    /** 没有可用的语言服务器。 */
    public static Result unavailable(String reason) {
      return new Result(java.util.Collections.emptyList(), reason, null, false);
    }

    /** 执行失败（非超时）。 */
    public static Result failed(String reason) {
      return new Result(java.util.Collections.emptyList(), null, reason, false);
    }

    /** 超时。 */
    public static Result timedOut(String reason) {
      return new Result(java.util.Collections.emptyList(), null, reason, true);
    }

    public List<Location> getLocations() {
      return locations;
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
