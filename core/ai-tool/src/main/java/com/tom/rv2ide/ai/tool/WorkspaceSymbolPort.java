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
 * 工作区符号搜索端口：按名称查找 Java 符号的声明位置。
 *
 * <p>与 {@link LspNavigationPort} 的分工：后者给定 file:line 做导航（跳定义/找引用），
 * 本端口按**名字**做全局搜索——回答「MainActivity 定义在哪个文件」。
 *
 * <p>状态机与 {@link DiagnosticsPort} 同构：unavailable / failed / timedOut / of
 * 四态互斥，绝不用空列表表达失败。
 */
public interface WorkspaceSymbolPort {

  /** 符号种类。中性枚举（不依赖任何 LSP 模型）。 */
  enum Kind {
    /** class */
    CLASS,
    /** interface */
    INTERFACE,
    /** enum */
    ENUM,
    /** @interface */
    ANNOTATION,
    /** 方法（含构造器） */
    METHOD,
    /** 字段（含常量） */
    FIELD
  }

  /**
   * 按名称搜索符号声明。
   *
   * @param query 符号名；简单名（MainActivity）或全限定名（com.foo.MainActivity）均可
   * @param kinds 种类过滤；null 或空集合表示不过滤
   * @param limit 结果条数上限（&gt;0）
   * @param timeoutMs 时间预算（毫秒）；实现**必须**在有界时间内返回，
   *        超预算时返回已找到的部分并置 truncated
   * @return 见 {@link Result}
   */
  Result search(String query, java.util.Set<Kind> kinds, int limit, long timeoutMs);

  /** 本端口当前是否可用（项目是否已打开）。 */
  boolean isAvailable();

  /** 不可用原因；可用时返回空串。必须给出可执行说明（「去打开项目」），不能只说失败。 */
  String unavailableReason();

  /** 一条符号声明。行列从 0 起（LSP 约定，与 DiagnosticsPort.Item 一致）。 */
  final class Symbol {
    private final String name;      // 简单名，如 "MainActivity" / "parseConfig"
    private final String owner;     // 所属类型全限定名；类型自身为空串
    private final Kind kind;
    private final String filePath;  // 绝对路径
    private final int line;         // 0 基
    private final int column;       // 0 基

    public Symbol(String name, String owner, Kind kind, String filePath, int line, int column) {
      this.name = name == null ? "" : name;
      this.owner = owner == null ? "" : owner;
      this.kind = kind;
      this.filePath = filePath == null ? "" : filePath;
      this.line = line;
      this.column = column;
    }

    public String getName() {
      return name;
    }

    public String getOwner() {
      return owner;
    }

    public Kind getKind() {
      return kind;
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

  /** 一次搜索的结果。 */
  final class Result {
    private final List<Symbol> symbols;
    private final boolean truncated;
    private final String truncatedReason;
    private final String unavailableReason;
    private final String failureReason;
    private final boolean timedOut;

    private Result(
        List<Symbol> symbols,
        boolean truncated,
        String truncatedReason,
        String unavailableReason,
        String failureReason,
        boolean timedOut) {
      this.symbols =
          symbols == null ? Collections.<Symbol>emptyList() : Collections.unmodifiableList(symbols);
      this.truncated = truncated;
      this.truncatedReason = truncatedReason == null ? "" : truncatedReason;
      this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
      this.failureReason = failureReason == null ? "" : failureReason;
      this.timedOut = timedOut;
    }

    /** 搜索完成（可能有 0 条结果——那就是「确实没有这个符号」）。 */
    public static Result of(List<Symbol> symbols) {
      return new Result(symbols, false, "", "", "", false);
    }

    /** 搜索完成但有截断（达到 limit 或时间预算）。 */
    public static Result ofTruncated(List<Symbol> symbols, String reason) {
      return new Result(symbols, true, reason, "", "", false);
    }

    /** 项目未打开 / 无可用语言服务器。 */
    public static Result unavailable(String reason) {
      return new Result(null, false, "", reason, "", false);
    }

    /** 搜索过程出错（非超时）。 */
    public static Result failed(String reason) {
      return new Result(null, false, "", "", reason, false);
    }

    /** 整体超时（GatedAnalyzer 兜底触发，通常伴随 0 结果）。 */
    public static Result timedOut(String reason) {
      return new Result(null, false, "", "", reason, true);
    }

    public List<Symbol> getSymbols() {
      return symbols;
    }

    public boolean isTruncated() {
      return truncated;
    }

    /** 截断原因（如「已达 50 条上限，可能还有更多」）；未截断时为空串。 */
    public String getTruncatedReason() {
      return truncatedReason;
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
