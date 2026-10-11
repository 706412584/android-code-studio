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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 诊断结果的格式化与文案：被独立工具 {@link DiagnosticsTool} 与编辑工具的搭车诊断共用。
 *
 * <p>抽出来的理由不是「复用好看」，而是**同一条诊断的文案必须完全一致**——
 * 否则模型会看到同一份诊断有两种措辞，可能据此判断「这是两回事」。
 */
final class DiagnosticsMessages {

  private DiagnosticsMessages() {}

  /** error=1, warning=2, info=3, hint=4；无法识别时回落到 warning。 */
  static int parseSeverity(String raw) {
    if (raw == null) {
      return 2;
    }
    switch (raw.trim().toLowerCase(Locale.US)) {
      case "error":
      case "e":
        return 1;
      case "info":
      case "i":
        return 3;
      case "hint":
      case "h":
        return 4;
      case "warning":
      case "warn":
      case "w":
      default:
        return 2;
    }
  }

  static String severityName(int severity) {
    switch (severity) {
      case 1:
        return "错误";
      case 3:
        return "信息";
      case 4:
        return "提示";
      case 2:
      default:
        return "警告";
    }
  }

  /** 按最低级别过滤（级别数值越小越严重；≤0 视为未知级别，丢弃）。 */
  static List<DiagnosticsPort.Item> filterBySeverity(
      List<DiagnosticsPort.Item> items, int minSeverity) {
    List<DiagnosticsPort.Item> result = new ArrayList<>();
    if (items == null) {
      return result;
    }
    for (DiagnosticsPort.Item item : items) {
      if (item.getSeverity() > 0 && item.getSeverity() <= minSeverity) {
        result.add(item);
      }
    }
    return result;
  }

  /** 严重者在前，同级按行号。 */
  static List<DiagnosticsPort.Item> sorted(List<DiagnosticsPort.Item> items) {
    List<DiagnosticsPort.Item> copy = new ArrayList<>(items);
    Collections.sort(
        copy,
        (a, b) -> {
          int c = Integer.compare(a.getSeverity(), b.getSeverity());
          return c != 0 ? c : Integer.compare(a.getLine(), b.getLine());
        });
    return copy;
  }

  /** 一行诊断：`12:5 [ERROR] message (code)`（行号列号从 1 计）。 */
  static String formatItem(DiagnosticsPort.Item item) {
    StringBuilder sb = new StringBuilder();
    sb.append(item.getLine() + 1)
        .append(':')
        .append(item.getColumn() + 1)
        .append(" [")
        .append(item.severityName())
        .append("] ")
        .append(item.getMessage());
    if (!item.getCode().isEmpty()) {
      sb.append(" (").append(item.getCode()).append(')');
    }
    return sb.toString();
  }

  /**
   * 把某文件的诊断格式化成可追加到工具结果里的文本。
   *
   * @param displayPath 展示用路径
   * @param items 已过滤/已排序的诊断；空列表返回空串（调用方据此判断「干净」）
   * @param maxItems 最多列出多少条；超出部分只报数量
   */
  static String block(String displayPath, List<DiagnosticsPort.Item> items, int maxItems) {
    if (items == null || items.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("该文件有 ").append(items.size()).append(" 条编译诊断（")
        .append(displayPath).append("）：\n");
    int shown = Math.min(items.size(), Math.max(1, maxItems));
    for (int i = 0; i < shown; i++) {
      sb.append("  ").append(formatItem(items.get(i))).append('\n');
    }
    if (items.size() > shown) {
      sb.append("  …另有 ").append(items.size() - shown).append(" 条（如需全部请用 diagnostics 工具）\n");
    }
    return sb.toString();
  }
}
