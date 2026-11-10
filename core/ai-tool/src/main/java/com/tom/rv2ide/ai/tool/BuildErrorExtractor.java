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

  /**
   * 向上回溯查找编译诊断行时最多看多少行。
   *
   * <p>诊断行出现在 {@code FAILURE:} **之前**，与它之间还隔着几条别的行（实测：
   * {@code e: file:///...MainActivity.kt:75:9 ...} → {@code > Task :app:compileDebugKotlin FAILED}
   * → {@code FAILURE: ...}）。因此不能按「连续行」回溯，只能在窗口内按前缀筛。
   * 窗口给足即可：编译失败后构建立即中止，诊断离标记很近。
   */
  private static final int DIAGNOSTIC_LOOKBACK_LINES = 300;

  /**
   * 编译诊断行的前缀。
   *
   * <p><b>为什么必须严格前缀，不能用 {@code contains("error")}</b>：{@code FAILURE:} 之前
   * 还有大量任务进度行（{@code > Task :app:preBuild}）以及上一轮构建的遗留输出，宽匹配会把
   * 它们一并捞回来——而「裁掉任务进度、只留根因」正是本类存在的意义（既有测试
   * {@code startsFromLastFailureMarker} 就钉着「不应出现 Task 进度行」）。Kotlin 用
   * {@code e: } / {@code w: }，javac 用 {@code error: } / {@code warning: }。
   */
  private static final String[] DIAGNOSTIC_PREFIXES = {"e: ", "w: ", "error: ", "warning: "};

  /** javac 风格诊断的另一种形态：`MainActivity.java:23: error: cannot find symbol`。 */
  private static final String[] DIAGNOSTIC_INFIXES = {": error: ", ": warning: "};

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
    if (start < 0) {
      List<String> relevant = tailOf(lines, tail);
      StringBuilder body = new StringBuilder();
      body.append("--- 构建输出（末尾 ").append(relevant.size()).append(" 行）---\n");
      appendLines(body, relevant, limit);
      return body.toString();
    }

    StringBuilder out = new StringBuilder();

    // 先放诊断行。Kotlin 的编译错误出现在 FAILURE: **之前**，只从 FAILURE: 起截取会把它
    // 整段丢掉，模型拿到的只剩「Compilation error. See log for more details」——一句不含
    // 任何定位信息的空话，于是只能自己手动跑 ./gradlew 去抓真实报错（实测真机会话
    // 7e0dd4b9：报错行 `MainActivity.kt:75:9 Unresolved reference 'stroke'` 明明就在
    // 同一份输出里，却没能到达模型）。javac 风格的诊断在标记之后，仍由下面那段覆盖。
    List<String> diagnostics = diagnosticsBeforeFailure(lines, start);
    if (!diagnostics.isEmpty()) {
      out.append("--- 编译诊断（FAILURE: 之前）---\n");
      // 诊断预算限制在三分之一：真凶是它，但 FAILURE: 段（含 What went wrong）不能被挤掉，
      // 否则模型知道「哪一行错了」却不知道「哪个任务失败、整体是什么状况」。
      appendLines(out, diagnostics, Math.max(1, limit / 3));
    }

    // FAILURE: 段保持不变。注意 appendLines 的预算是「该 builder 的总长」，因此两段合起来
    // 仍受 maxChars 约束，不会因为多了一段就把输出撑大一倍。
    out.append("--- 构建输出（自最后的 ").append(FAILURE_MARKER).append(" 起）---\n");
    appendLines(out, lines.subList(start, lines.size()), limit);
    return out.toString();
  }

  /** 用默认上限提取。 */
  public static String extract(List<String> lines) {
    return extract(lines, DEFAULT_MAX_CHARS, DEFAULT_TAIL_LINES);
  }

  /**
   * 取本次失败在 {@code FAILURE:} **之前**的编译诊断行。
   *
   * <p>回溯窗口内若遇到更早的 {@code FAILURE:} 就停下——那之前的诊断属于上一轮构建，
   * 与本次无关（既有用例 {@code usesLastFailureMarkerWhenSeveralExist} 钉着这点）。
   *
   * <p>只取标记之前的：标记之后的诊断行已含在 FAILURE: 段里，重复输出只会白占上下文。
   */
  private static List<String> diagnosticsBeforeFailure(List<String> lines, int failureIndex) {
    int lowerBound = Math.max(0, failureIndex - DIAGNOSTIC_LOOKBACK_LINES);
    for (int i = failureIndex - 1; i >= lowerBound; i--) {
      String line = lines.get(i);
      if (line != null && line.contains(FAILURE_MARKER)) {
        lowerBound = i + 1;
        break;
      }
    }
    List<String> diagnostics = new ArrayList<>();
    for (int i = lowerBound; i < failureIndex; i++) {
      String line = lines.get(i);
      if (isDiagnosticLine(line)) {
        diagnostics.add(line);
      }
    }
    return diagnostics;
  }

  /** 该行是否是一条编译器诊断（严格前缀 / 形态匹配，见 {@link #DIAGNOSTIC_PREFIXES}）。 */
  private static boolean isDiagnosticLine(String line) {
    if (line == null) {
      return false;
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    for (String prefix : DIAGNOSTIC_PREFIXES) {
      if (trimmed.startsWith(prefix)) {
        return true;
      }
    }
    for (String infix : DIAGNOSTIC_INFIXES) {
      if (trimmed.contains(infix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 逐行追加，直到该 builder 的总长达到 {@code maxTotalLength} 即截断并标记。
   *
   * <p>判据是<b>总长</b>而非本次追加量：这样把它用在已含内容的 builder 上时，
   * 预算自然成为「整段输出的上限」，调用方无需自己折算剩余额度。
   */
  private static void appendLines(StringBuilder body, List<String> lines, int maxTotalLength) {
    for (String line : lines) {
      if (line == null) {
        continue;
      }
      body.append(line).append('\n');
      if (body.length() >= maxTotalLength) {
        body.append("...(输出过长，已截断)\n");
        break;
      }
    }
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
