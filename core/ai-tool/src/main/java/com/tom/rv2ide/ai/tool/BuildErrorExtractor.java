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

import java.util.ArrayList;
import java.util.List;

/**
 * 从构建日志里提取对模型有用的错误片段。
 *
 * <p><b>为什么需要</b>：`TaskExecutionResult` 只带 `Failure` 枚举（如 `BUILD_FAILED`），
 * 不含任何错误文本。模型据此无法自我修正，只能靠反复试错去猜——真机实测一次构建失败
 * 烧掉 30 次工具调用才定位到根因。
 *
 * <p><b>取哪一段</b>：Gradle 输出结构为「任务进度 → {@code FAILURE:} → 错误详情 →
 * {@code * Try:} → BUILD FAILED」，根因在 {@code FAILURE:} 之后。因此优先从最后一个
 * {@code FAILURE:} 标记起截取；找不到标记时退回尾部若干行（错误总在末尾）。
 * 全量输出可达数万行，塞进上下文会挤掉真正有用的信息。
 *
 * <p>纯 Java、无 Android 依赖，因此这个启发式可被单测锁定——它的分支一旦静默退化
 * （如标记变更导致总是走兜底），模型侧的表现就是「又只能盲猜了」。
 */
public final class BuildErrorExtractor {

  /** 输出文本上限，避免撑爆模型上下文。 */
  public static final int DEFAULT_MAX_CHARS = 4000;

  /** 兜底时保留的行数。 */
  public static final int DEFAULT_TAIL_LINES = 80;

  /** Gradle 打印错误详情前的标记。 */
  private static final String FAILURE_MARKER = "FAILURE:";

  private BuildErrorExtractor() {}

  /**
   * 提取错误片段。
   *
   * @param lines 构建输出，按时间正序；可为 null
   * @param maxChars 文本上限；{@code <= 0} 用 {@link #DEFAULT_MAX_CHARS}
   * @param tailLines 无 {@code FAILURE:} 标记时保留的尾部行数；{@code <= 0} 用默认值
   * @return 可直接放进工具结果的文本；输入为空时返回空串
   */
  public static String extract(List<String> lines, int maxChars, int tailLines) {
    if (lines == null || lines.isEmpty()) {
      return "";
    }
    int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
    int tail = tailLines <= 0 ? DEFAULT_TAIL_LINES : tailLines;

    int start = lastIndexOfFailure(lines);
    List<String> relevant;
    String header;
    if (start >= 0) {
      relevant = lines.subList(start, lines.size());
      header = "--- 构建输出（自最后的 " + FAILURE_MARKER + " 起）---\n";
    } else {
      relevant = tailOf(lines, tail);
      header = "--- 构建输出（末尾 " + relevant.size() + " 行）---\n";
    }

    StringBuilder body = new StringBuilder();
    for (String line : relevant) {
      if (line == null) {
        continue;
      }
      body.append(line).append('\n');
      if (body.length() >= limit) {
        body.append("...(输出过长，已截断)\n");
        break;
      }
    }
    return header + body;
  }

  /** 用默认上限提取。 */
  public static String extract(List<String> lines) {
    return extract(lines, DEFAULT_MAX_CHARS, DEFAULT_TAIL_LINES);
  }

  /** 最后一个 {@code FAILURE:} 标记的下标；没有则返回 -1。 */
  private static int lastIndexOfFailure(List<String> lines) {
    for (int i = lines.size() - 1; i >= 0; i--) {
      String line = lines.get(i);
      if (line != null && line.contains(FAILURE_MARKER)) {
        return i;
      }
    }
    return -1;
  }

  private static List<String> tailOf(List<String> lines, int maxLines) {
    if (lines.size() <= maxLines) {
      return new ArrayList<>(lines);
    }
    return new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
  }
}
