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

import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.cloudbuild.CloudBuildService;
import com.tom.rv2ide.cloudbuild.GitHubApiException;
import com.tom.rv2ide.cloudbuild.GitRemoteParser;
import java.io.File;
import java.util.Locale;
import java.util.TreeSet;
import org.json.JSONObject;

/**
 * 云打包工具（github_actions_build / github_actions_status）的共用支撑。
 *
 * <p><b>为什么需要「前置校验」而不是直接调用服务层</b>：与 {@link GitRepoSupport}
 * 同一个教训——含糊的失败（光秃秃的「失败了」）会让模型反复试错。这里在每个前置
 * 条件不满足时给出**指向下一步**的文案：没 token → 去配置；没工作区 → 打开项目；
 * remote 不是 GitHub → 检查 remote。
 *
 * <p><b>为什么抽成共享类</b>：两个工具的前三段校验（token / 工作区 / 仓库识别）
 * 逐行相同，写两遍的结果是改一处漏一处（GitTool/GitWriteTool 拆分时
 * {@link GitRepoSupport} 就是这么来的）。
 */
final class CloudBuildSupport {

  private CloudBuildSupport() {}

  /** 前置校验通过后的执行环境。 */
  static final class Setup {
    /** 失败时的错误结果；成功时为 null。 */
    final ToolResult error;
    /** 成功时的服务实例（token 与 owner/repo 已就绪）；失败时为 null。 */
    final CloudBuildService service;
    /** 成功时的会话工作区路径（用于分支识别与 workflow 扫描）；失败时为 null。 */
    final String projectPath;

    private Setup(ToolResult error, CloudBuildService service, String projectPath) {
      this.error = error;
      this.service = service;
      this.projectPath = projectPath;
    }

    static Setup failed(ToolResult error) {
      return new Setup(error, null, null);
    }

    static Setup ok(CloudBuildService service, String projectPath) {
      return new Setup(null, service, projectPath);
    }
  }

  /**
   * 三段前置校验：token → 工作区 → GitHub 仓库识别。
   *
   * <p>顺序有意为之：token 是最常见的缺失项，先报它；工作区缺失说明会话没有绑定
   * 项目；仓库识别失败才是「项目不是 GitHub 仓库 / remote 配错」。
   *
   * <p>全部判定发生在任何网络调用之前——因此单测可以直接覆盖这三条失败路径，
   * 不需要 mock HTTP。
   */
  static Setup prepare(String toolName, String token, ToolContext context) {
    if (token == null || token.trim().isEmpty()) {
      return Setup.failed(err(toolName,
          "尚未配置 GitHub Personal Access Token，无法访问 GitHub Actions。"
              + "请让用户配置云打包 token（需要 repo 与 workflow 权限）后重试。"));
    }
    String path = context == null ? "" : context.getHomePath();
    if (path == null || path.trim().isEmpty()) {
      return Setup.failed(err(toolName,
          "当前会话没有绑定工作区目录，无法识别 git 仓库。请先在编辑器中打开项目。"));
    }
    GitRemoteParser.RepoSlug slug = CloudBuildService.detectRepository(path);
    if (slug == null || !slug.isValid()) {
      return Setup.failed(err(toolName,
          "未能从项目 git remote 识别出 GitHub 仓库（owner/repo）。"
              + "请确认项目已配置指向 github.com 的 remote（如 origin 指向"
              + " https://github.com/owner/repo）。"));
    }
    return Setup.ok(new CloudBuildService(token.trim(), slug.owner, slug.repo), path);
  }

  /**
   * workflow 参数只允许纯文件名。
   *
   * <p>路径分隔符会让 GitHub API 的路径拼接指向意外目标，而 workflow 本来就只能是
   * 仓库 {@code .github/workflows/} 下的文件名——提前拒绝比让 API 报 404 更明确。
   */
  static boolean isSafeWorkflowName(String name) {
    if (name == null || name.trim().isEmpty()) {
      return false;
    }
    return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.contains("..");
  }

  /**
   * 项目里默认使用的 workflow：{@code .github/workflows/} 下第一个 .yml/.yaml（字母序）。
   *
   * <p>字母序而非文件系统顺序：目录枚举顺序在不同设备/文件系统上不保证一致，
   * 「第一个」必须是确定的，否则同一项目在不同机器上可能触发不同 workflow。
   *
   * @return 文件名；目录不存在或没有 yml 时返回 null
   */
  static String findDefaultWorkflow(String projectPath) {
    File dir = new File(projectPath, ".github/workflows");
    File[] files = dir.listFiles();
    if (files == null) {
      return null;
    }
    TreeSet<String> names = new TreeSet<>();
    for (File file : files) {
      if (!file.isFile()) {
        continue;
      }
      String lower = file.getName().toLowerCase(Locale.ROOT);
      if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
        names.add(file.getName());
      }
    }
    return names.isEmpty() ? null : names.first();
  }

  /** 解析 timeoutMs 参数：<=0 或缺失取默认，超出上限截断（与 gradle_build 同一规则）。 */
  static long resolveTimeoutMs(JSONObject input, long defaultMs, long maxMs) {
    long timeout = (long) input.optDouble("timeoutMs", defaultMs);
    if (timeout <= 0) {
      timeout = defaultMs;
    }
    return Math.min(timeout, maxMs);
  }

  /**
   * 日志 zip 的临时目录。
   *
   * <p>由装配处注入 app 的 cacheDir（系统会清理、不污染用户项目），而不是
   * {@code java.io.tmpdir}——Android 上该属性的值不可靠，可能指向 app 不可写的
   * 路径。{@code fetchFailureLog} 提取完即删 zip，正常不残留。
   *
   * @param cacheDir 注入的临时目录；null 时退回工作区（宁可放项目里也别失败，
   *     但正常情况下装配处一定会注入）
   */
  static File cacheDir(File cacheDir, ToolContext context) {
    if (cacheDir != null) {
      return cacheDir;
    }
    String home = context == null ? "" : context.getHomePath();
    return new File(home.isEmpty() ? "." : home);
  }

  /**
   * 把 API/网络异常翻译成可读文案。
   *
   * <p>{@link GitHubApiException} 用它的中文说明（401/403/404/429 各指向不同的
   * 下一步动作）；其它异常退回消息本身——模型据此判断是网络抖动还是别的问题。
   */
  static String describeApiError(Exception e) {
    if (e instanceof GitHubApiException) {
      return ((GitHubApiException) e).userMessage();
    }
    String message = e == null ? null : e.getMessage();
    if (message == null || message.trim().isEmpty()) {
      return e == null ? "未知错误" : e.getClass().getSimpleName();
    }
    return message;
  }

  /** 错误结果构造。 */
  static ToolResult err(String toolName, String message) {
    return ToolResult.of("", toolName, message, true);
  }
}
