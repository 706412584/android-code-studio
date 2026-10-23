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

package com.tom.rv2ide.artificial.agent.tool;

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.JUnitReportParser;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行单元测试并解析 JUnit XML 报告。
 *
 * <p><b>为什么需要它</b>：{@code gradle_build} 跑测试任务时只回「BUILD FAILED」——
 * 它的 {@code TaskExecutionResult} 只带 Failure 枚举，不含错误文本。模型改完代码
 * 要验证时，只能再自己去 {@code build/test-results/} 里逐个读 XML，多花 2–3 轮。
 * 本工具把「跑了多少 / 挂了哪几个 / 断言说了什么」一次返回。
 *
 * <p><b>为什么与 test_coverage 分开</b>：覆盖率回答「测到了多少」，本工具回答
 * 「挂了哪几个、为什么挂」。两者跑的是不同 Gradle 任务（koverXmlReport vs test），
 * 合并会让每次调用都做两件耗时的事。
 *
 * <p><b>为什么走 BuildService 而不是自建 GradleConnector</b>：BuildService 已经维护
 * 与项目的连接、且带「同时只能一个构建」的互斥。自建连接会绕开这个互斥，
 * 与用户手动触发的构建抢锁——表现是随机的 daemon 超时。
 */
public final class RunTestsTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(RunTestsTool.class);

  /** 默认超时 10 分钟：单元测试通常几十秒，给足余量避免误杀。 */
  private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L;
  /** 最大 30 分钟：与 test_coverage 一致。 */
  private static final long MAX_TIMEOUT_MS = 30 * 60 * 1000L;
  /** 取消信号轮询分片（ms），与 test_coverage 一致。 */
  private static final long CANCEL_POLL_MS = 250L;

  /** 每个失败用例最多展示多少行堆栈。 */
  private static final int STACK_LINES = 8;
  /** 最多列出多少个失败用例：超出的只报数字，避免撑爆上下文。 */
  private static final int MAX_FAILURES_SHOWN = 25;

  private final GradleBuildTool.BuildServiceProvider buildServiceProvider;

  public RunTestsTool(GradleBuildTool.BuildServiceProvider buildServiceProvider) {
    this.buildServiceProvider = buildServiceProvider;
  }

  @Override
  public String getName() {
    return ToolNames.RUN_TESTS;
  }

  @Override
  public String getDescription() {
    return "运行 Gradle 单元测试并返回结构化结果：通过/失败/跳过数，以及每个失败用例的"
        + "「类名.方法名 + 断言消息 + 关键堆栈帧」。"
        + "改完代码后用它验证，比 gradle_build 更直接——后者只回 BUILD FAILED，看不到哪个用例挂了。"
        + "参数 module 可只跑某个模块（如 core/ai-tool）。"
        + "只跑单元测试（test 任务），不跑 instrumented 测试。";
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
        .put("properties", new JSONObject()
            .put("module", new JSONObject()
                .put("type", "string")
                .put("description",
                    "可选：只跑某个模块的测试，如 core/ai-tool（也接受 :core:ai-tool）。"
                        + "不传则跑全部模块的 test 任务。"))
            .put("timeoutMs", new JSONObject()
                .put("type", "number")
                .put("description",
                    "超时毫秒数，默认 " + DEFAULT_TIMEOUT_MS + "，最大 " + MAX_TIMEOUT_MS)));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    // 跨项目守卫（与 gradle_build / test_coverage 同语义）：provider 已注入时按会话
    // cwd 查找连接；只有 provider 未注入（测试/旧装配）才需要退回全局 Lookup，
    // 此时必须拦住以免跑错项目的测试。
    if (context != null && buildServiceProvider == null) {
      String mismatch = context.getProjectMismatchReason();
      if (!mismatch.isEmpty()) {
        return error(mismatch);
      }
    }
    String projectPath = context == null ? "" : context.getHomePath();
    BuildService service =
        buildServiceProvider == null ? null : buildServiceProvider.get(projectPath);
    if (service == null) {
      if (projectPath != null && !projectPath.trim().isEmpty()) {
        return error(
            "项目 " + projectPath + " 尚未建立构建连接。"
                + "请先在 IDE 中打开该项目并等待初始化完成、"
                + "或在 AI 设置里开启「后台项目构建」，"
                + "或改用 shell_execute 执行 ./gradlew test。");
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
      return error("拿不到项目根目录，无法定位测试报告。请确认已打开项目。");
    }

    String module = input.optString("module", "").trim();
    String task = testTask(module);

    long timeout = (long) input.optDouble("timeoutMs", DEFAULT_TIMEOUT_MS);
    if (timeout <= 0) {
      timeout = DEFAULT_TIMEOUT_MS;
    }
    timeout = Math.min(timeout, MAX_TIMEOUT_MS);

    if (context != null) {
      context.reportProgress("运行 " + task);
    }

    TaskExecutionResult result;
    try {
      result = runTask(service, Collections.singletonList(task), timeout, context);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error("测试运行被中断。");
    } catch (RuntimeException e) {
      return error("无法启动测试任务：" + describe(e));
    }
    boolean buildFailed = result != null && !result.isSuccessful();

    List<File> reportDirs = findReportDirs(projectDir, module);
    if (reportDirs.isEmpty()) {
      StringBuilder sb = new StringBuilder("没有找到测试报告（build/test-results/）。\n");
      if (buildFailed) {
        sb.append(buildFailureNote(service, result));
      } else {
        sb.append("请确认目标模块有测试源码（src/test/java）。");
      }
      return error(sb.toString());
    }

    JUnitReportParser.Summary summary = new JUnitReportParser.Summary();
    for (File dir : reportDirs) {
      summary.add(JUnitReportParser.parseDirectory(dir));
    }

    String rendered = render(summary, module, buildFailed);
    if (buildFailed) {
      // 构建失败但报告里没有失败用例，多半是编译没过——把构建输出尾部带上，
      // 否则模型只知道「失败了」却不知道该改哪。
      rendered = rendered + "\n" + buildFailureNote(service, result);
    }
    // 有失败用例时返回 error：agent 循环据此知道「这轮改动没通过」，
    // 但内容仍是完整报告（不是一句「失败了」）。
    return summary.isSuccess() && !buildFailed ? ok(rendered) : error(rendered);
  }

  /** 测试任务名：指定模块则加前缀（与 test_coverage 的写法一致）。 */
  static String testTask(String module) {
    if (module == null || module.trim().isEmpty()) {
      // 无前缀 → 各子项目各跑一次 test（Gradle 会跳过没有测试的模块）
      return "test";
    }
    String normalized = module.trim().replace('/', ':').replace('\\', ':');
    if (!normalized.startsWith(":")) {
      normalized = ":" + normalized;
    }
    return normalized + ":test";
  }

  /**
   * 找测试报告目录。
   *
   * <p>Gradle 的输出位置是 {@code <module>/build/test-results/<task>/}。模块层级随项目
   * 而变（core/x、java/lsp…），因此递归找，深度设上限避免扫进 build/ 下的缓存副本。
   *
   * @param moduleFilter 非空时只保留该模块
   */
  static List<File> findReportDirs(File projectDir, String moduleFilter) {
    List<File> out = new ArrayList<>();
    if (projectDir == null) {
      return out;
    }
    String normalizedFilter = moduleFilter == null ? ""
        : moduleFilter.trim().replace('\\', '/').replace(':', '/');
    collectReportDirs(projectDir, projectDir, normalizedFilter, out, 0, 4);
    return out;
  }

  private static void collectReportDirs(
      File root, File current, String moduleFilter, List<File> out, int depth, int maxDepth) {
    if (depth > maxDepth || current == null || !current.isDirectory()) {
      return;
    }
    File results = new File(current, "build/test-results");
    if (results.isDirectory()) {
      File[] children = results.listFiles(File::isDirectory);
      if (children != null) {
        String relative = relativize(root, current);
        if (moduleFilter.isEmpty() || relative.equals(moduleFilter)) {
          Collections.addAll(out, children);
        }
      }
      // build/ 下不再往下找：里面的 test-results 就是最终产物，别扫进中间目录
      return;
    }
    File[] children = current.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      if (!child.isDirectory()
          || "build".equals(child.getName())
          || "src".equals(child.getName())
          || ".git".equals(child.getName())) {
        continue;
      }
      collectReportDirs(root, child, moduleFilter, out, depth + 1, maxDepth);
    }
  }

  private static String relativize(File root, File dir) {
    String rootPath = root.getAbsolutePath();
    String dirPath = dir.getAbsolutePath();
    if (dirPath.equals(rootPath)) {
      return "";
    }
    if (dirPath.startsWith(rootPath + File.separator)) {
      return dirPath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }
    return dirPath;
  }

  /**
   * 把汇总渲染成模型可读的文本（纯函数，可单测）。
   *
   * @param buildFailed 构建是否失败（用于区分「测试挂了」与「编译没过」）
   */
  static String render(
      JUnitReportParser.Summary summary, String module, boolean buildFailed) {
    StringBuilder sb = new StringBuilder();
    String scope = module == null || module.trim().isEmpty() ? "全部模块" : module.trim();
    sb.append("测试结果（").append(scope).append("）：");
    sb.append("通过 ").append(summary.passed())
        .append(" / 失败 ").append(summary.failed())
        .append(" / 跳过 ").append(summary.skipped)
        .append("，共 ").append(summary.tests).append(" 个用例");
    if (summary.unreadableFiles > 0) {
      sb.append("（另有 ").append(summary.unreadableFiles).append(" 份报告无法解析）");
    }
    sb.append('\n');

    if (summary.failures.isEmpty()) {
      if (buildFailed) {
        // 报告里没有失败用例但构建失败：多半是编译错误——测试根本没跑起来
        sb.append("\n构建失败，但没有失败用例：这通常意味着**编译没通过**，测试未执行。\n")
            .append("请先用 diagnostics 或 gradle_build 看编译错误。\n");
      } else if (summary.tests == 0) {
        sb.append("\n没有发现测试用例。请确认目标模块的 src/test 下有测试源码。\n");
      }
      return sb.toString();
    }

    sb.append("\n失败用例：\n");
    int shown = Math.min(summary.failures.size(), MAX_FAILURES_SHOWN);
    for (int i = 0; i < shown; i++) {
      JUnitReportParser.Failure failure = summary.failures.get(i);
      sb.append(i + 1).append(". ").append(failure.display())
          .append(failure.isError ? "（异常）" : "（断言失败）").append('\n');
      if (!failure.message.isEmpty()) {
        sb.append("   ").append(oneLine(failure.message)).append('\n');
      }
      String stack = JUnitReportParser.trimStack(failure.stack, STACK_LINES);
      if (!stack.isEmpty()) {
        sb.append(stack);
      }
    }
    if (summary.failures.size() > shown) {
      sb.append("…另有 ").append(summary.failures.size() - shown)
          .append(" 个失败未列出（可用 module 缩小范围）。\n");
    }
    return sb.toString();
  }

  /** 断言消息可能多行，列表里压成一行。 */
  private static String oneLine(String text) {
    String compact = text.replace('\r', ' ').replace('\n', ' ').trim();
    return compact.length() > 300 ? compact.substring(0, 300) + "…" : compact;
  }

  private TaskExecutionResult runTask(
      BuildService service, List<String> tasks, long timeout, ToolContext context)
      throws InterruptedException {
    CompletableFuture<TaskExecutionResult> future;
    future = service.executeTasks(tasks.toArray(new String[0]));

    ToolContext.CancellationSignal cancel = context == null ? null : context.getCancellation();
    long deadline = System.currentTimeMillis() + timeout;
    while (true) {
      if (cancel != null && cancel.isCancelled()) {
        try {
          service.cancelCurrentBuild();
        } catch (RuntimeException e) {
          log.warn("取消测试任务失败", e);
        }
        service.recordRejectedBuildAttempt();
        throw new IllegalStateException("测试任务已被用户停止。");
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        try {
          service.cancelCurrentBuild();
        } catch (RuntimeException e) {
          log.warn("取消测试任务失败", e);
        }
        service.recordRejectedBuildAttempt();
        throw new IllegalStateException("测试任务超时（" + (timeout / 1000) + " 秒），已请求取消。");
      }
      try {
        return future.get(Math.min(CANCEL_POLL_MS, remaining), TimeUnit.MILLISECONDS);
      } catch (TimeoutException slice) {
        // 本片未完成：回到循环顶检查取消/总超时。
      } catch (ExecutionException e) {
        throw new IllegalStateException("测试任务执行出错: " + e.getMessage(), e);
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
              service.getBuildOutput().tail(
                  com.tom.rv2ide.projects.builder.BuildOutputBuffer.DEFAULT_CAPACITY));
      if (!detail.isEmpty()) {
        sb.append(detail);
      }
    } catch (RuntimeException e) {
      log.debug("读取构建输出失败", e);
    }
    return sb.toString();
  }

  /** 项目根目录：优先用上下文的 homePath（工作区根）。 */
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

  private static String describe(Exception e) {
    String message = e.getMessage();
    return message == null || message.trim().isEmpty()
        ? e.getClass().getSimpleName() : message;
  }
}
