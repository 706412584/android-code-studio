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

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * {@link WorkspaceSymbolTool} 的 kind 解析与结果格式化。
 *
 * <p>抽出来的理由与 {@link DiagnosticsMessages} 相同：同一条符号的文案必须完全一致，
 * 且让工具层单测能直接断言解析与格式化，不经过完整 {@code execute}。
 */
final class WorkspaceSymbolMessages {

  private WorkspaceSymbolMessages() {}

  /** 解析 kind 参数（小写枚举名）；无法识别时返回 null，由调用方报错。 */
  static WorkspaceSymbolPort.Kind parseKind(String raw) {
    if (raw == null) {
      return null;
    }
    switch (raw.trim().toLowerCase(Locale.US)) {
      case "class":
        return WorkspaceSymbolPort.Kind.CLASS;
      case "interface":
        return WorkspaceSymbolPort.Kind.INTERFACE;
      case "enum":
        return WorkspaceSymbolPort.Kind.ENUM;
      case "annotation":
        return WorkspaceSymbolPort.Kind.ANNOTATION;
      case "method":
        return WorkspaceSymbolPort.Kind.METHOD;
      case "field":
        return WorkspaceSymbolPort.Kind.FIELD;
      default:
        return null;
    }
  }

  /** kind 的中文名，用于结果行展示。 */
  static String kindName(WorkspaceSymbolPort.Kind kind) {
    if (kind == null) {
      return "未知";
    }
    switch (kind) {
      case CLASS:
        return "类";
      case INTERFACE:
        return "接口";
      case ENUM:
        return "枚举";
      case ANNOTATION:
        return "注解";
      case METHOD:
        return "方法";
      case FIELD:
        return "字段";
      default:
        return "未知";
    }
  }

  /**
   * 一行符号：`path:line:col  name  (种类[, 所属类])`。
   *
   * <p>行列转 1 基（与 {@code file_read}/{@code grep} 一致），模型可直接拿去读文件；
   * 端口给的是 0 基（LSP 约定）。
   */
  static String formatSymbol(ToolContext context, WorkspaceSymbolPort.Symbol symbol) {
    String path;
    try {
      path = FileToolPathPolicy.displayPath(context.getHomePath(), new File(symbol.getFilePath()));
    } catch (IOException e) {
      path = symbol.getFilePath();
    }
    StringBuilder sb = new StringBuilder();
    sb.append(path)
        .append(':')
        .append(symbol.getLine() + 1)
        .append(':')
        .append(symbol.getColumn() + 1)
        .append("  ")
        .append(symbol.getName())
        .append("  (")
        .append(kindName(symbol.getKind()));
    if (!symbol.getOwner().isEmpty()) {
      sb.append(", ").append(symbol.getOwner());
    }
    sb.append(')');
    return sb.toString();
  }
}
