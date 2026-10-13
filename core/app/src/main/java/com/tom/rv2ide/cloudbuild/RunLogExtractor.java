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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.cloudbuild;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 run 的日志 zip 中提取「失败 job 的可读日志片段」。
 *
 * <p><b>为什么需要它</b>：GitHub 的 run 日志是一个 zip（每个 job 一个条目，
 * 条目名形如 {@code <job-name>/1_Set up job.txt}），且单条目可达数万行。
 * 直接把整个 zip 或整份日志丢给用户（或 AI）既超上下文也没重点。这里只取
 * **失败相关行及其上下文**，这是「看到失败原因」的核心价值。
 *
 * <p><b>什么算失败相关行</b>：GitHub 日志里错误行的形态很杂（Gradle 的
 * {@code FAILURE:}、{@code > Task :x FAILED}、{@code e: } 编译错误、
 * {@code ##[error]} 注解、{@code Exception} 堆栈）。这里按一组模式匹配，
 * 并保留匹配行前后各 {@link #CONTEXT_LINES} 行上下文——单独一行
 * {@code BUILD FAILED} 说明不了什么，前后文才有诊断价值。
 */
public final class RunLogExtractor {

  /** 错误行前后保留的上下文行数。 */
  private static final int CONTEXT_LINES = 3;

  /** 单份输出最多保留的日志行数（防止极端情况下把整个日志塞给 AI）。 */
  private static final int MAX_OUTPUT_LINES = 120;

  /** 单份输出最多保留的字节数。 */
  private static final int MAX_OUTPUT_CHARS = 24_000;

  private RunLogExtractor() {
  }

  /** 提取结果：失败 job 名 + 日志片段。 */
  public static final class ExtractResult {
    public final String text;
    public final boolean foundError;

    ExtractResult(String text, boolean foundError) {
      this.text = text;
      this.foundError = foundError;
    }
  }

  /**
   * 从日志 zip 提取失败片段。
   *
   * @param logZip 下载下来的日志 zip 文件
   * @return 可读文本；zip 无法解析时返回说明性文本（而不是抛异常——
   *     日志提取失败不应让整个「查看失败原因」流程失败）
   */
  public static ExtractResult extract(File logZip) {
    if (logZip == null || !logZip.exists() || logZip.length() == 0) {
      return new ExtractResult("（日志文件不存在或为空）", false);
    }

    StringBuilder output = new StringBuilder();
    boolean foundError = false;

    try (ZipFile zip = new ZipFile(logZip)) {
      // 条目名形如 "<job-name>/1_Set up job.txt"，按 job 归组。
      // 这里遍历所有条目，对每个条目做错误行提取。
      List<String> jobNames = new ArrayList<>();
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        String entryName = entry.getName();
        // 只处理 .txt 日志（GitHub 的 zip 里可能混有其它文件）。
        if (!entryName.endsWith(".txt")) {
          continue;
        }
        String jobName = entryName.contains("/")
            ? entryName.substring(0, entryName.indexOf('/'))
            : entryName;
        if (!jobNames.contains(jobName)) {
          jobNames.add(jobName);
        }

        ExtractResult result = extractEntry(zip, entry);
        if (result.foundError) {
          if (output.length() > 0) {
            output.append("\n\n");
          }
          output.append("=== job: ").append(jobName).append(" ===\n");
          output.append(result.text);
          foundError = true;
          if (output.length() > MAX_OUTPUT_CHARS) {
            output.append("\n…（日志过长，已截断）");
            break;
          }
        }
      }
    } catch (Throwable e) {
      return new ExtractResult("（日志解析失败：" + e.getMessage() + "）", false);
    }

    if (!foundError) {
      // 没找到错误行不一定是坏事——可能是 job 失败但没有典型错误输出，
      // 也可能是提取规则没覆盖。如实说明，不要假装「没有错误」。
      return new ExtractResult(
          "未在日志中找到典型错误行。可打开运行页面查看完整日志。", false);
    }
    return new ExtractResult(output.toString(), true);
  }

  /** 提取单个 zip 条目的错误行与上下文。 */
  private static ExtractResult extractEntry(ZipFile zip, ZipEntry entry) {
    List<String> lines = new ArrayList<>();
    try (InputStream input = zip.getInputStream(entry);
         BufferedReader reader = new BufferedReader(
             new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
        // 防御：单个条目超长时提前停止（正常日志不会有 50 万行）。
        if (lines.size() > 500_000) {
          break;
        }
      }
    } catch (Throwable e) {
      return new ExtractResult("（读取日志条目失败：" + e.getMessage() + "）", false);
    }

    // 找出所有错误行的行号，再合并上下文区间。
    List<Integer> errorLineIndexes = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      if (isErrorLine(lines.get(i))) {
        errorLineIndexes.add(i);
      }
    }
    if (errorLineIndexes.isEmpty()) {
      return new ExtractResult("", false);
    }

    // 合并相邻的错误行区间（避免同一处错误输出多行被切碎）。
    StringBuilder output = new StringBuilder();
    int outputLines = 0;
    int previousEnd = -1;
    for (int index : errorLineIndexes) {
      int start = Math.max(0, index - CONTEXT_LINES);
      int end = Math.min(lines.size() - 1, index + CONTEXT_LINES);
      if (start <= previousEnd) {
        // 与上一段重叠：只补新增的行。
        start = previousEnd + 1;
      }
      if (start > end) {
        continue;
      }
      if (previousEnd >= 0 && start > previousEnd + 1) {
        output.append("…\n");
      }
      for (int i = start; i <= end; i++) {
        output.append(lines.get(i)).append('\n');
        outputLines++;
        if (outputLines >= MAX_OUTPUT_LINES) {
          output.append("…（错误行过多，已截断）\n");
          return new ExtractResult(output.toString(), true);
        }
      }
      previousEnd = end;
    }
    return new ExtractResult(output.toString(), true);
  }

  /**
   * 判断一行是否是「错误行」。
   *
   * <p>模式来自 GitHub Actions 与常见构建工具的实际输出形态，宁可多匹配
   * （上下文合并会消化误报）也不要漏——漏掉真正错误行的代价更大。
   */
  static boolean isErrorLine(String line) {
    if (line == null || line.isEmpty()) {
      return false;
    }
    // GitHub Actions 的注解形式。
    if (line.contains("##[error]")) {
      return true;
    }
    // Gradle / Maven 的失败输出。
    if (line.contains("FAILURE:") || line.contains("BUILD FAILED") || line.contains("FAILED")) {
      return true;
    }
    // Kotlin / Java 编译错误（e: file.kt:12:3 ...）。
    if (line.startsWith("e: ") || line.contains(" error: ")) {
      return true;
    }
    // 异常与堆栈。
    if (line.contains("Exception") || line.contains("Caused by:")) {
      return true;
    }
    // npm / node 错误。
    if (line.contains("npm ERR!") || line.contains("ERROR in")) {
      return true;
    }
    // 通用错误前缀（谨慎：只用行首的强模式，避免匹配到普通文本里的 "error"）。
    String trimmed = line.trim();
    return trimmed.startsWith("ERROR:") || trimmed.startsWith("Error:")
        || trimmed.startsWith("error:");
  }
}
