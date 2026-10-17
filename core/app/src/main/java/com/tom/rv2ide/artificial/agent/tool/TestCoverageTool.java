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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.KoverReportParser;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.projects.builder.BuildService;
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试覆盖率工具：跑 kover、解析报告，返回各模块覆盖率与未覆盖的类。
 *
 * <p><b>为什么需要它</b>：{@code gradle_build} 能跑测试任务，但只回「成功/失败 + 输出尾部」；
 * 覆盖率报告是 700KB 的 XML，模型读不到也不会去读。于是「AI 改完代码后哪里没测到」这件事
 * 无从回答——本工具把它变成一次调用。
 *
 * <p><b>一次跑全项目</b>：传无前缀任务名 {@code koverXmlReport}，Gradle 会在**每个**应用了
 * kover 插件的子项目上执行它（实测生成各模块的 {@code build/reports/kover/report.xml}），
 * 因此不需要先知道有哪些模块、也不用逐个调用。
 *
 * <p><b>与 kover 插件的关系</b>：本工具不引入覆盖率能力，只是「跑任务 + 读报告」的薄封装。
 * 前提是项目已在 {@code build.gradle.kts} 里 apply 了 kover（见 {@code libs.versions.toml}
 * 的 {@code kover} 条目）。未接入的项目会得到「没有报告产出」的明确提示，而不是空结果。
 *
 * <p><b>为什么是需要确认的工具</b>：它会触发 Gradle 构建（跑测试），耗时且占用构建服务，
 * 与 {@code gradle_build} 同档。
 */
public final class TestCoverageTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(TestCoverageTool.class);

  /** 报告相对模块根的路径（kover 默认输出位置）。 */
  static final String REPORT_RELATIVE_PATH = "build/reports/kover/report.xml";

  /** 默认超时：10 分钟（要跑完全项目测试）。 */
  private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L;

  /** 最大超时：30 分钟。 */
  private static final long MAX_TIMEOUT_MS = 30 * 60 * 1000L;

  /** 取消信号轮询分片（ms）。 */
  private static final long CANCEL_POLL_MS = 250L;

  /** 默认返回的未覆盖类数量上限（按缺口降序）——够定位问题即可，不必把几百个类都倒出来。 */
  private static final int DEFAULT_TOP_CLASSES = 15;

  /** 最大化返回的类数，防模型传个 99999 把上下文撑爆。 */
  private static final int MAX_TOP_CLASSES = 60;

  /** 类名展示长度上限：内外部类名可能很长，超长则截断中段。 */
  private static final int CLASS_NAME_LIMIT = 70;

  private final GradleBuildTool.BuildServiceProvider buildServiceProvider;

  public TestCoverageTool(GradleBuildTool.BuildServiceProvider buildServiceProvider) {
    this.buildServiceProvider = buildServiceProvider;
  }

  @Override
  public String getName() {
    return ToolNames.TEST_COVERAGE;
  }

  @Override
  public String getDescription() {
    return "运行测试覆盖率（kover）并返回结果：各模块的行覆盖率百分比，以及未覆盖行数最多的类清单。"
        + "改完代码后用它确认「改动的分支有没有被测试覆盖」。"
        + "需要项目已接入 kover 插件（本仓库的 ai-* 模块已接入）。"
        + "内部会跑一次 Gradle 构建（含测试），耗时较长，同一时间只能有一个构建在进行。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean needsConfirmation() {
    // 与 gradle_build 同级：触发构建、耗时、占用构建服务。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "minPercent",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "可选：低于该覆盖率的模块会标注为「不达标」。默认不判定，只报数字。"))
                .put(
                    "topClasses",
                    new JSONObject()
                        .put("type", "integer")
                        .put(
                            "description",
                            "可选：每模块最多列出多少个未覆盖的类（按缺口降序），默认 "
                                + DEFAULT_TOP_CLASSES
                                + "，上限 "
                                + MAX_TOP_CLASSES
                                + "。传 0 只看模块汇总。"))
                .put(
                    "module",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "可选：只跑并只报告某个模块的覆盖率（如 core/ai-tool）。"
                                + "不传则跑全项目所有已接入 kover 的模块。"))
                .put(
                    "timeoutMs",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "超时毫秒数，默认 " + DEFAULT_TIMEOUT_MS + "，最大 " + MAX_TIMEOUT_MS)));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    // 跨项目守卫：与 gradle_build 同因——构建服务只服务于编辑器打开的项目。
    if (context != null) {
      String mismatch = context.getProjectMismatchReason();
      if (!mismatch.isEmpty()) {
        return error(mismatch);
      }
    }
    String projectPath = context == null ? "" : context.getHomePath();
    BuildService service =
        buildServiceProvider == null ? null : buildServiceProvider.get(projectPath);
    if (service == null) {
      // 区分两种「不可用」：项目没打开 vs 该项目还没有构建连接。
      // 混为一谈会让模型反复重试同一个调用。
      if (projectPath != null && !projectPath.trim().isEmpty()) {
        return error(
            "项目 " + projectPath + " 尚未建立构建连接。"
                + "请先在 IDE 中打开该项目并等待初始化完成，"
                + "或改用 shell_execute 执行 ./gradlew。");
      }
      return error("构建服务不可用。请确认已打开一个项目并完成初始化。");
    }
    if (!service.isToolingServerStarted()) {
      return error("Gradle 工具服务尚未启动。请先在 IDE 中打开项目并等待初始化完成。");
    }
    if (service.isBuildInProgress()) {
      service.recordRejectedBuildAttempt();
      return error("已有构建正在进行，请等待其完成后再试。");
    }

    File projectDir = projectDir(context);
    if (projectDir == null) {
      return error("拿不到项目根目录，无法定位覆盖率报告。请确认已打开项目。");
    }

    String module = input.optString("module", "").trim();
    String task = coverageTask(module);
    List<String> tasks = Collections.singletonList(task);

    long timeout = (long) input.optDouble("timeoutMs", DEFAULT_TIMEOUT_MS);
    if (timeout <= 0) {
      timeout = DEFAULT_TIMEOUT_MS;
    }
    timeout = Math.min(timeout, MAX_TIMEOUT_MS);

    if (context != null) {
      context.reportProgress("运行 " + task);
    }

    // 先记下跑之前的报告时间戳：任务是 UP-TO-DATE 时不会重写文件，靠「有没有新报告」
    // 判断会让 UP-TO-DATE 的成功路径被误报成失败。因此改为「跑完之后文件存在即可用」。
    TaskExecutionResult result;
    try {
      result = runTask(service, tasks, timeout, context);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error("覆盖率运行被中断。");
    } catch (RuntimeException e) {
      return error("无法启动覆盖率任务: " + e.getMessage());
    }

    // 任务失败（测试挂了）时报告可能仍是旧的——如实说明，并把失败原因带上。
    // 覆盖率本身仍有参考价值（测试跑挂了但覆盖率数据是上一轮有效的），所以不直接返回错误。
    List<KoverReportParser.ModuleCoverage> reports = collectReports(projectDir, module);
    if (reports.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("没有找到覆盖率报告（").append(REPORT_RELATIVE_PATH).append("）。\n");
      if (result != null && !result.isSuccessful()) {
        sb.append("任务也未成功完成，原因见下：\n");
        sb.append(buildFailureNote(service, result));
      } else {
        sb.append("请确认目标模块已接入 kover 插件（build.gradle.kts 里 apply 了 org.jetbrains.kotlinx.kover）。");
      }
      return error(sb.toString());
    }

    int top = input.optInt("topClasses", DEFAULT_TOP_CLASSES);
    if (top < 0) {
      top = DEFAULT_TOP_CLASSES;
    }
    top = Math.min(top, MAX_TOP_CLASSES);
    double minPercent = input.optDouble("minPercent", -1d);

    StringBuilder sb = new StringBuilder();
    sb.append(render(reports, top, minPercent));
    if (result != null && !result.isSuccessful()) {
      // 测试失败时覆盖率数字只覆盖「跑成功的部分」，必须点明，否则模型会当成全量结果。
      sb.append("\n⚠️ 注意：本次任务未成功完成（测试可能失败），覆盖率数据仅覆盖成功的部分。\n");
      sb.append(buildFailureNote(service, result));
    }
    return ok(sb.toString());
  }

  /** 覆盖率任务名：指定模块则加前缀（Gradle 的项目路径形式 {@code :core:ai-tool}）。 */
  static String coverageTask(String module) {
    if (module == null || module.isEmpty()) {
      // 无前缀 → 所有应用了 kover 的子项目各跑一次（实测）。
      return "koverXmlReport";
    }
    String normalized = module.replace('/', ':').replace('\\', ':').trim();
    if (!normalized.startsWith(":")) {
      normalized = ":" + normalized;
    }
    return normalized + ":koverXmlReport";
  }

  /**
   * 扫描项目下各模块的 kover 报告。
   *
   * @param moduleFilter 非空时只保留该模块（如 {@code core/ai-tool}）
   */
  static List<KoverReportParser.ModuleCoverage> collectReports(File projectDir, String moduleFilter) {
    List<KoverReportParser.ModuleCoverage> out = new ArrayList<>();
    if (projectDir == null) {
      return out;
    }
    // 递归找 report.xml：模块层级随项目而变（core/x、java/lsp、tooling/x…），
    // 写死层级会漏掉一半模块。深度设上限，避免扫进 build/ 下的深层缓存副本。
    collectRecursive(projectDir, projectDir, moduleFilter, out, 0, 4);
    // 覆盖率升序（最差的排最前）——模型的注意力应该先落在缺口最大的模块上。
    out.sort((a, b) -> Double.compare(a.percent(), b.percent()));
    return out;
  }

  private static void collectRecursive(
      File projectDir,
      File dir,
      String moduleFilter,
      List<KoverReportParser.ModuleCoverage> out,
      int depth,
      int maxDepth) {
    if (dir == null || depth > maxDepth || !dir.isDirectory()) {
      return;
    }
    File report = new File(dir, REPORT_RELATIVE_PATH);
    if (report.isFile()) {
      String modulePath = relativePath(projectDir, dir);
      if (moduleFilter == null
          || moduleFilter.isEmpty()
          || modulePath.equals(normalizeModule(moduleFilter))) {
        try {
          out.add(KoverReportParser.parse(report, modulePath));
        } catch (Exception e) {
          // 单个报告损坏不应让整次查询失败：跳过它，其余模块照常返回。
          log.warn("解析覆盖率报告失败：{}", report.getAbsolutePath(), e);
        }
      }
      // 报告所在目录之下不再找（避免把 build/ 里的深层副本当独立模块）。
      return;
    }
    File[] children = dir.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      if (!child.isDirectory() || "build".equals(child.getName()) || ".git".equals(child.getName())) {
        continue;
      }
      if (child.getName().startsWith(".") && depth > 0) {
        // 隐藏目录（.gradle/.idea）里不会有源码模块。
        continue;
      }
      collectRecursive(projectDir, child, moduleFilter, out, depth + 1, maxDepth);
    }
  }

  /** 把绝对路径转成项目相对路径（用正斜杠，便于与用户传的 module 参数比对）。 */
  static String relativePath(File projectDir, File dir) {
    String base = projectDir.getAbsolutePath();
    String full = dir.getAbsolutePath();
    String rel = full.startsWith(base) ? full.substring(base.length()) : full;
    rel = rel.replace('\\', '/');
    while (rel.startsWith("/")) {
      rel = rel.substring(1);
    }
    return rel.isEmpty() ? "." : rel;
  }

  private static String normalizeModule(String module) {
    String m = module.replace('\\', '/').trim();
    while (m.startsWith("/")) {
      m = m.substring(1);
    }
    while (m.endsWith("/")) {
      m = m.substring(0, m.length() - 1);
    }
    if (m.startsWith(":")) {
      m = m.substring(1).replace(':', '/');
    }
    return m;
  }

  /** 渲染文本结果（模块汇总 + 每模块的未覆盖类）。 */
  static String render(
      List<KoverReportParser.ModuleCoverage> reports, int topClasses, double minPercent) {
    StringBuilder sb = new StringBuilder();
    sb.append("测试覆盖率（共 ").append(reports.size()).append(" 个模块）\n\n");
    sb.append("| 模块 | 行覆盖率 | 已覆盖/总行 |").append(minPercent >= 0 ? " 达标 |" : "").append('\n');
    sb.append("|---|---|---|").append(minPercent >= 0 ? "---|" : "").append('\n');
    int totalCovered = 0;
    int totalLines = 0;
    for (KoverReportParser.ModuleCoverage m : reports) {
      totalCovered += m.coveredLines;
      totalLines += m.totalLines();
      sb.append("| ")
          .append(m.modulePath)
          .append(" | ")
          .append(formatPercent(m.percent()))
          .append(" | ")
          .append(m.coveredLines)
          .append('/')
          .append(m.totalLines());
      if (minPercent >= 0) {
        sb.append(" | ").append(m.percent() + 1e-9 >= minPercent ? "✅" : "❌");
      }
      sb.append(" |\n");
    }
    if (totalLines > 0) {
      sb.append("\n**合计**：")
          .append(formatPercent(100d * totalCovered / totalLines))
          .append("（")
          .append(totalCovered)
          .append('/')
          .append(totalLines)
          .append(" 行）\n");
    }

    if (topClasses > 0) {
      for (KoverReportParser.ModuleCoverage m : reports) {
        if (m.uncoveredClasses.isEmpty()) {
          continue;
        }
        sb.append("\n### ").append(m.modulePath).append(" — 未覆盖最多的类\n");
        int shown = Math.min(topClasses, m.uncoveredClasses.size());
        for (int i = 0; i < shown; i++) {
          KoverReportParser.ClassCoverage c = m.uncoveredClasses.get(i);
          sb.append("- ")
              .append(abbreviateClass(c.className))
              .append("：缺 ")
              .append(c.missedLines)
              .append('/')
              .append(c.totalLines())
              .append(" 行（")
              .append(formatPercent(c.percent()))
              .append("）\n");
        }
        if (m.uncoveredClasses.size() > shown) {
          sb.append("- …另有 ")
              .append(m.uncoveredClasses.size() - shown)
              .append(" 个未完全覆盖的类\n");
        }
      }
    }
    return sb.toString();
  }

  private static String formatPercent(double v) {
    return String.format(java.util.Locale.ROOT, "%.1f%%", v);
  }

  /** 类名过长时截断中段：保留包名前缀与类名尾部，才能认出是哪个类。 */
  static String abbreviateClass(String name) {
    if (name == null) {
      return "";
    }
    if (name.length() <= CLASS_NAME_LIMIT) {
      return name;
    }
    int half = (CLASS_NAME_LIMIT - 1) / 2;
    return name.substring(0, half) + "…" + name.substring(name.length() - half);
  }

  private TaskExecutionResult runTask(
      BuildService service, List<String> tasks, long timeout, ToolContext context)
      throws InterruptedException {
    java.util.concurrent.CompletableFuture<TaskExecutionResult> future;
    future = service.executeTasks(tasks.toArray(new String[0]));

    ToolContext.CancellationSignal cancel = context == null ? null : context.getCancellation();
    long deadline = System.currentTimeMillis() + timeout;
    while (true) {
      if (cancel != null && cancel.isCancelled()) {
        try {
          service.cancelCurrentBuild();
        } catch (RuntimeException e) {
          log.warn("取消覆盖率任务失败", e);
        }
        service.recordRejectedBuildAttempt();
        throw new IllegalStateException("覆盖率任务已被用户停止。");
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        try {
          service.cancelCurrentBuild();
        } catch (RuntimeException e) {
          log.warn("取消覆盖率任务失败", e);
        }
        service.recordRejectedBuildAttempt();
        throw new IllegalStateException("覆盖率任务超时（" + (timeout / 1000) + " 秒），已请求取消。");
      }
      try {
        return future.get(Math.min(CANCEL_POLL_MS, remaining), TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException slice) {
        // 本片未完成：回到循环顶检查取消/总超时。
      } catch (java.util.concurrent.ExecutionException e) {
        throw new IllegalStateException("覆盖率任务执行出错: " + e.getMessage(), e);
      }
    }
  }

  private static String buildFailureNote(BuildService service, TaskExecutionResult result) {
    if (result == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Gradle 结果：").append(result.isSuccessful() ? "成功" : "失败").append('\n');
    if (result.getFailure() != null) {
      sb.append("失败类型：").append(GradleBuildTool.describeFailure(result.getFailure())).append('\n');
    }
    try {
      String detail =
          com.tom.rv2ide.ai.tool.BuildErrorExtractor.extract(
              service.getBuildOutput().tail(com.tom.rv2ide.projects.builder.BuildOutputBuffer.DEFAULT_CAPACITY));
      if (!detail.isEmpty()) {
        sb.append(detail);
      }
    } catch (RuntimeException e) {
      log.debug("读取构建输出失败", e);
    }
    return sb.toString();
  }

  /** 项目根目录：优先用上下文的 homePath（工作区根），回退到构建服务。 */
  private static File projectDir(ToolContext context) {
    if (context != null) {
      String home = context.getHomePath();
      if (home != null && !home.isEmpty()) {
        File dir = new File(home);
        if (dir.isDirectory()) {
          return dir;
        }
      }
    }
    return null;
  }
}
