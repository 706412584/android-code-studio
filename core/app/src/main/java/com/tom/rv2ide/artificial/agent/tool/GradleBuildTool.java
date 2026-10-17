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
import com.tom.rv2ide.ai.tool.BuildErrorExtractor;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.models.ApkMetadata;
import com.tom.rv2ide.projects.IProjectManager;
import com.tom.rv2ide.projects.IWorkspace;
import com.tom.rv2ide.projects.android.AndroidModule;
import com.tom.rv2ide.projects.builder.BuildOutputBuffer;
import com.tom.rv2ide.projects.builder.BuildService;
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult;
import com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 Gradle Tooling API 执行构建任务。
 *
 * <p>这是 ACS 相对参考项目的独有能力：参考项目没有 Gradle 集成，无法构建 Android 项目。
 *
 * <p><b>阻塞与超时</b>：{@code executeTasks} 返回 {@link CompletableFuture}，
 * 这里用 {@code get(timeout)} 等待，超时后调用 {@code cancelCurrentBuild()} 而不是
 * {@code future.cancel()} —— 后者只取消本地等待，Gradle 侧仍在跑，会阻塞后续构建。
 */
public final class GradleBuildTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(GradleBuildTool.class);

  /** 默认超时：10 分钟。Android 项目首次构建可能较慢。 */
  private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L;

  /** 最大超时：30 分钟。 */
  private static final long MAX_TIMEOUT_MS = 30 * 60 * 1000L;

  /** 取消信号轮询分片（ms）。用户点停止后最迟这么久终止 Gradle 侧构建。 */
  private static final long CANCEL_POLL_MS = 250L;

  /**
   * 构建服务查询器。用接口注入而非直接依赖 Lookup，便于测试。
   *
   * <p><b>为什么带项目路径</b>：会话可以绑定任意项目（cwd 在 SessionMetaEntry），
   * 而构建连接是「每项目一个」。只给无参 {@code get()} 的话，工具层只能拿到
   * 编辑器当前项目的连接，跨项目会话要么静默构建错项目、要么一律拒绝。
   * 传入会话 cwd 后，注册表可以返回对应项目的连接（P3 起还能按需创建）。
   */
  public interface BuildServiceProvider {
    /**
     * @param projectPath 会话的工作区路径；空串表示「用编辑器当前项目」
     * @return 该项目的构建服务；不存在时返回 null
     */
    BuildService get(String projectPath);
  }

  private final BuildServiceProvider buildServiceProvider;

  public GradleBuildTool(BuildServiceProvider buildServiceProvider) {
    this.buildServiceProvider = buildServiceProvider;
  }

  @Override
  public String getName() {
    return "gradle_build";
  }

  @Override
  public String getDescription() {
    return "执行 Gradle 构建任务，例如 [\":app:assembleDebug\"] 构建 debug APK。"
        + "构建成功后返回 APK 路径与 applicationId，可直接用于 install_apk。"
        + "注意：构建耗时较长，且同一时间只能有一个构建在进行。";
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
                    "tasks",
                    new JSONObject()
                        .put("type", "array")
                        .put(
                            "items",
                            new JSONObject().put("type", "string"))
                        .put(
                            "description",
                            "Gradle 任务路径列表，如 [\":app:assembleDebug\"]"))
                .put(
                    "timeoutMs",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "超时毫秒数，默认 " + DEFAULT_TIMEOUT_MS + "，最大 " + MAX_TIMEOUT_MS)))
        .put("required", new JSONArray().put("tasks"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    // 跨项目守卫（P2 起语义变化）：注册表按会话 cwd 查找连接，命中即用该项目的
    // 连接；只有**没有该项目连接**时才拒绝。原先「编辑器开 A、会话绑 B 一律拒绝」
    // 的硬守卫已移除——那会让跨项目构建永远不可用。
    //
    // 仍保留 mismatch 检查的降级路径：provider 未注入（测试/旧装配）时没有注册表，
    // 此时只能退回全局 Lookup，必须拦住以免静默构建错项目。
    if (context != null && buildServiceProvider == null) {
      String mismatch = context.getProjectMismatchReason();
      if (!mismatch.isEmpty()) {
        return error(mismatch);
      }
    }
    JSONArray tasksJson = input.optJSONArray("tasks");
    if (tasksJson == null || tasksJson.length() == 0) {
      return error("tasks 不能为空");
    }
    String[] tasks = new String[tasksJson.length()];
    for (int i = 0; i < tasksJson.length(); i++) {
      tasks[i] = tasksJson.optString(i, "").trim();
      if (tasks[i].isEmpty()) {
        return error("tasks 中存在空任务名");
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
                + "请先在 IDE 中打开该项目并等待初始化完成、"
                + "或在 AI 设置里开启「后台项目构建」，"
                + "或改用 shell_execute 执行 ./gradlew。");
      }
      return error("构建服务不可用。请确认已打开一个项目并完成初始化。");
    }
    if (!service.isToolingServerStarted()) {
      return error("Gradle 工具服务尚未启动。请先在 IDE 中打开项目并等待初始化完成。");
    }
    // 已有构建在进行时直接拒绝而非排队：并发构建会争用 Tooling API 连接，
    // 且用户可能正在手动构建。
    if (service.isBuildInProgress()) {
      // 早退也要登记为一次失败的构建尝试，否则 lastBuildOutcome 会保留上一次的
      // 成功记录，产物核验据此放行安装旧 APK（真机实测正是如此）。
      service.recordRejectedBuildAttempt();
      return error("已有构建正在进行，请等待其完成后再试。");
    }

    long timeout = (long) input.optDouble("timeoutMs", DEFAULT_TIMEOUT_MS);
    if (timeout <= 0) {
      timeout = DEFAULT_TIMEOUT_MS;
    }
    timeout = Math.min(timeout, MAX_TIMEOUT_MS);

    if (context != null) {
      context.reportProgress("构建中: " + String.join(" ", tasks));
    }

    CompletableFuture<TaskExecutionResult> future;
    try {
      future = service.executeTasks(tasks);
    } catch (RuntimeException e) {
      return error("无法启动构建: " + e.getMessage());
    }

    TaskExecutionResult result;
    try {
      // 分片等待而不是一次 get(timeout)：后者在等待期间无法响应取消，用户点停止
      // 只能干等到构建完成或超时——而构建动辄几分钟，表现为「停止没反应」。
      // 每片 250ms 轮询一次取消信号，点停止后最迟 250ms 终止 Gradle 侧构建。
      ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
      long deadline = System.currentTimeMillis() + timeout;
      result = null;
      while (true) {
        if (runCancel != null && runCancel.isCancelled()) {
          try {
            service.cancelCurrentBuild();
          } catch (RuntimeException cancelError) {
            log.warn("取消构建失败", cancelError);
          }
          service.recordRejectedBuildAttempt();
          return error("构建已被用户停止。");
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          break; // 走下面的超时处理
        }
        try {
          result = future.get(Math.min(CANCEL_POLL_MS, remaining), TimeUnit.MILLISECONDS);
          break;
        } catch (java.util.concurrent.TimeoutException slice) {
          // 本片未完成：回到循环顶检查取消/总超时。
        }
      }
      if (result == null) {
        // 总超时：取消 Gradle 侧的真实构建，而不是只放弃本地等待
        try {
          service.cancelCurrentBuild();
        } catch (RuntimeException cancelError) {
          log.warn("取消构建失败", cancelError);
        }
        // 超时的构建被取消了，产物不可信，登记为失败
        service.recordRejectedBuildAttempt();
        return error("构建超时（" + (timeout / 1000) + " 秒），已请求取消。");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error("构建被中断。");
    } catch (Exception e) {
      return error("构建失败: " + e.getMessage());
    }

    if (result == null) {
      return error("构建未返回结果。");
    }
    if (!result.isSuccessful()) {
      StringBuilder sb = new StringBuilder();
      sb.append("构建失败");
      if (result.getFailure() != null) {
        sb.append(": ").append(describeFailure(result.getFailure()));
      }
      sb.append('\n');
      // TaskExecutionResult 只带 Failure 枚举，不含任何错误文本。没有下面这段，
      // 模型只知道「失败了」而不知「为什么」，只能靠反复试错去猜
      // （实测一次构建失败烧掉 30 次工具调用）。
      appendBuildErrorDetail(sb, service);
      return error(sb.toString());
    }

    StringBuilder sb = new StringBuilder();
    sb.append("构建成功。\n");

    // 尝试定位 APK：**必须按会话项目路径**扫描，不能用无参重载。
    //
    // 无参重载走 `IProjectManager` 的 workspace（编辑器单例）。后台项目构建时
    // 会话绑 B、编辑器开 A，它会把 A 的 APK 路径报给模型——真机实测：ProjectB
    // 的 APK 确实被重新构建（01:31），工具却回了 MyBasicActivity 的路径（01:21），
    // 模型据此以为构建打错了项目，还主动向用户报告了这个错误结论。
    String apkPath = ApkLocator.findApkPath(projectPath);
    if (apkPath != null) {
      sb.append("APK: ").append(apkPath).append('\n');
    } else {
      sb.append("(未能自动定位 APK 路径，可用 install_apk 时手动指定)\n");
    }
    String applicationId = ApkLocator.findApplicationId(projectPath);
    if (applicationId != null) {
      sb.append("applicationId: ").append(applicationId).append('\n');
    }
    return ok(sb.toString());
  }

  /**
   * 把 {@link TaskExecutionResult.Failure} 翻译成「原因 + 下一步该做什么」。
   *
   * <p><b>为什么要翻译而不是直接打印枚举名</b>：实测设备会话里 {@code gradle_build}
   * 4 次调用全部失败，返回的都是光秃秃的 {@code PROJECT_NOT_INITIALIZED}——模型只知道
   * 「失败了」，不知道是「项目还没同步完」还是「这不是 Gradle 项目」，于是反复重试同
   * 一条构建。这与 {@code GitRepoSupport} 的教训一致：错误文案必须指向可执行的下一步，
   * 否则模型只能靠试错去猜。
   *
   * <p>文案里的「下一步」都指向**用户或模型能真正执行的动作**（等待初始化、同步项目、
   * 检查 settings.gradle），而不是笼统的「请检查配置」。
   *
   * <p>包内可见而非 private：本方法纯字符串映射、不碰 Android 类型，因此可在 JVM 上
   * 直接单测——而「文案是否真的指向下一步」正是容易写成空话、又难以在设备上回归的地方。
   */
  static String describeFailure(TaskExecutionResult.Failure failure) {
    if (failure == null) {
      // 调用点虽然已经判过 null，但这里仍要兜底：switch 对 null 会抛 NPE，
      // 而「失败信息本身把 agent 循环炸掉」是最不该发生的一种失败。
      return "未知原因。请查看下方构建输出定位问题。";
    }
    switch (failure) {
      case PROJECT_NOT_INITIALIZED:
        return "项目尚未初始化完成（Gradle 工具服务还没就绪）"
            + "。刚打开项目时需要等待同步完成；若已等待较久，可在 IDE 里点「同步项目」后重试。"
            + "在此之前任何构建任务都会以同一原因失败，重试没有意义。";
      case PROJECT_NOT_FOUND:
        return "找不到项目。请确认工作区路径正确、且项目已在本 IDE 中打开。";
      case PROJECT_NOT_DIRECTORY:
        return "项目路径不是目录。请确认工作区指向的是一个项目根目录，而不是单个文件。";
      case PROJECT_DIRECTORY_INACCESSIBLE:
        return "项目目录不可访问（权限不足或已被移动/删除）。"
            + "请确认目录存在且有读写权限。";
      case UNSUPPORTED_GRADLE_VERSION:
        return "Gradle 版本不受支持。请检查 gradle/wrapper/gradle-wrapper.properties 里的"
            + " distributionUrl，必要时换成受支持的版本。";
      case UNSUPPORTED_CONFIGURATION:
        return "不支持的构建配置。请检查 build.gradle / settings.gradle 是否有语法错误"
            + "或不兼容的插件版本。";
      case UNSUPPORTED_BUILD_ARGUMENT:
        return "构建参数不受支持。请检查 tasks 里填的任务名是否存在"
            + "（任务名拼错、或该模块没有这个任务都会走到这里）。";
      case BUILD_FAILED:
        return "构建过程失败（编译错误或任务执行失败）。"
            + "请查看下方输出的错误详情定位并修正代码，再重新构建。";
      case BUILD_CANCELLED:
        return "构建被取消（用户中止或超时）。确认是否需要重新发起。";
      case CONNECTION_ERROR:
        return "与 Gradle 工具服务的连接出错。可在 IDE 里重新同步项目后重试。";
      case CONNECTION_CLOSED:
        return "与 Gradle 工具服务的连接已关闭（服务可能已停止）。"
            + "请重新同步项目后再试。";
      case UNKNOWN:
      default:
        return "未知原因。请查看下方构建输出定位问题。";
    }
  }

  /**
   * 把构建输出的关键部分附加到失败信息里。
   *
   * <p>提取策略见 {@link BuildErrorExtractor}（在纯 Java 模块中，便于单测）。
   */
  private static void appendBuildErrorDetail(StringBuilder sb, BuildService service) {
    List<String> all;
    try {
      all = service.getBuildOutput().tail(BuildOutputBuffer.DEFAULT_CAPACITY);
    } catch (RuntimeException e) {
      log.debug("读取构建输出失败", e);
      return;
    }
    String detail = BuildErrorExtractor.extract(all);
    if (detail.isEmpty()) {
      sb.append("(未能获取构建输出)\n");
      return;
    }
    sb.append(detail);
  }

  /**
   * 从构建产物元数据里解析 APK 路径与 applicationId。
   *
   * <p>AGP 不通过 TaskExecutionResult 返回产物路径，而是把清单写在
   * {@code assembleTaskOutputListingFile} 指向的 JSON 里，因此需要遍历
   * 各 Android 模块的选中变体去定位。
   */
  static final class ApkLocator {

    private ApkLocator() {}

    static String findApkPath() {
      File apk = locateApkFile();
      return apk == null ? null : apk.getAbsolutePath();
    }

    /**
     * 按项目路径定位 APK（跨项目会话必须用这个重载）。
     *
     * <p>实现方式：先按项目根目录扫描 `build/outputs/apk/**`（限深、跳过中间产物
     * 目录），按修改时间取最新。**不走** `IProjectManager`——那是编辑器单例，
     * 会话绑定 B 时会指向 A。
     *
     * <p>扫描失败（目录不存在/无产物）时返回 null，由调用方给出「未能自动定位」。
     */
    static String findApkPath(String projectPath) {
      File root = new File(projectPath);
      if (!root.isDirectory()) {
        return null;
      }
      File newest = null;
      java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
      queue.add(root);
      int visited = 0;
      // 限深防止在超大仓库里扫到天荒地老；build/ 之外的层级一般不超过 3。
      while (!queue.isEmpty() && visited < MAX_SCAN_DIRS) {
        File dir = queue.poll();
        visited++;
        File[] children = dir.listFiles();
        if (children == null) {
          continue;
        }
        for (File child : children) {
          if (child.isDirectory()) {
            String name = child.getName();
            if ("build".equals(name)) {
              File apk = newestApkUnder(new File(child, "outputs/apk"));
              if (apk != null && (newest == null || apk.lastModified() > newest.lastModified())) {
                newest = apk;
              }
              continue;
            }
            if (".git".equals(name) || ".gradle".equals(name) || "node_modules".equals(name)) {
              continue;
            }
            queue.add(child);
          }
        }
      }
      return newest == null ? null : newest.getAbsolutePath();
    }

    /** `build/outputs/apk` 下最新的 .apk（限深 4 层）。 */
    private static File newestApkUnder(File dir) {
      if (!dir.isDirectory()) {
        return null;
      }
      File newest = null;
      java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
      java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
      queue.add(dir);
      depths.add(0);
      while (!queue.isEmpty()) {
        File current = queue.poll();
        int depth = depths.poll();
        File[] children = current.listFiles();
        if (children == null) {
          continue;
        }
        for (File child : children) {
          if (child.isDirectory() && depth < 4) {
            queue.add(child);
            depths.add(depth + 1);
          } else if (child.isFile() && child.getName().endsWith(".apk")) {
            if (newest == null || child.lastModified() > newest.lastModified()) {
              newest = child;
            }
          }
        }
      }
      return newest;
    }

    /** 扫描目录数上限：防止在异常大的仓库里阻塞构建结果返回。 */
    private static final int MAX_SCAN_DIRS = 4000;

    /**
     * 按项目路径读 applicationId。
     *
     * <p>从 APK 同目录的 `output-metadata.json` 里取（AGP 会写 applicationId）；
     * 读不到时返回 null——这不是关键信息，不值得为它做额外解析。
     */
    static String findApplicationId(String projectPath) {
      String apkPath = findApkPath(projectPath);
      if (apkPath == null) {
        return null;
      }
      File meta = new File(new File(apkPath).getParentFile(), "output-metadata.json");
      if (!meta.isFile()) {
        return null;
      }
      try {
        String text = new String(java.nio.file.Files.readAllBytes(meta.toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject json = new org.json.JSONObject(text);
        org.json.JSONArray elements = json.optJSONArray("elements");
        if (elements != null && elements.length() > 0) {
          String id = elements.optJSONObject(0).optString("applicationId", "");
          return id.isEmpty() ? null : id;
        }
      } catch (Exception e) {
        log.debug("读取 output-metadata.json 失败: {}", meta, e);
      }
      return null;
    }

    static String findApplicationId() {
      for (AndroidModule module : androidModules()) {
        BasicAndroidVariantMetadata variant = module.getSelectedVariant();
        if (variant != null && variant.getMainArtifact() != null) {
          String id = variant.getMainArtifact().getApplicationId();
          if (id != null && !id.isEmpty()) {
            return id;
          }
        }
      }
      return null;
    }

    private static File locateApkFile() {
      for (AndroidModule module : androidModules()) {
        BasicAndroidVariantMetadata variant = module.getSelectedVariant();
        if (variant == null || variant.getMainArtifact() == null) {
          continue;
        }
        File listing = variant.getMainArtifact().getAssembleTaskOutputListingFile();
        if (listing == null) {
          continue;
        }
        File apk = ApkMetadata.Companion.findApkFile(listing);
        if (apk != null && apk.exists()) {
          return apk;
        }
      }
      return null;
    }

    /** 当前工作区中的 Android 模块。工作区不可用时返回空列表。 */
    private static List<AndroidModule> androidModules() {
      try {
        IWorkspace workspace = IProjectManager.getInstance().getWorkspace();
        if (workspace == null) {
          return Collections.emptyList();
        }
        List<AndroidModule> modules = new ArrayList<>();
        java.util.Iterator<AndroidModule> it = workspace.androidProjects().iterator();
        while (it.hasNext()) {
          modules.add(it.next());
        }
        return modules;
      } catch (RuntimeException e) {
        log.debug("枚举 Android 模块失败", e);
        return Collections.emptyList();
      }
    }
  }
}
