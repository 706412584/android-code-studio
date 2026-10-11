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
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.cloudbuild.CloudBuildService;
import org.json.JSONObject;

/**
 * 查询 GitHub Actions 最近一次运行的状态（只读）。
 *
 * <p><b>为什么与触发拆成两个工具</b>：权限层按**工具**分级——{@code needsConfirmation()}
 * 无参数、只读模式的放行也只看分类。查询不消耗 CI 资源，应当免确认、只读模式放行；
 * 而触发会真的起一次云构建（消耗 Actions 分钟数），必须逐次确认。混在一个工具里
 * 只能整包按触发处理（见 {@code ToolNames.GIT} 与 {@code GIT_WRITE} 同一拆分先例）。
 *
 * <p><b>数据来源</b>：{@link CloudBuildService#describeLatestRun} 返回的摘要文本
 * 已由服务层格式化（状态/分支/时间/job 列表/链接），工具层不再重新拼装——
 * UI 与 AI 看到同一份文案，不会漂移出两套说法。
 */
public final class GitHubActionsStatusTool extends BaseTool {

  private final GitHubActionsBuildTool.TokenProvider tokenProvider;

  public GitHubActionsStatusTool(GitHubActionsBuildTool.TokenProvider tokenProvider) {
    this.tokenProvider = tokenProvider;
  }

  @Override
  public String getName() {
    return ToolNames.GITHUB_ACTIONS_STATUS;
  }

  @Override
  public String getDescription() {
    return "查询当前项目 GitHub 仓库最近一次 Actions 运行的状态（排队/运行中/成功/失败），"
        + "含分支、创建时间与各 job 的状态。只读，不触发任何构建。"
        + "用户问「云构建跑得怎么样」时用它；要触发新构建用 github_actions_build。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  /** 只读查询，与 git 查询同级，只读模式下放行。 */
  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  /** 只读查询互不依赖，可并发。 */
  @Override
  public boolean isConcurrencySafe() {
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
                    "workflow",
                    new JSONObject()
                        .put("type", "string")
                        .put("description",
                            "可选：workflow 文件名（如 build.yml），默认不限（查全部 workflow 的最新运行）"))
                .put(
                    "branch",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "可选：分支名，默认不限（查所有分支的最新运行）")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String token = tokenProvider == null ? "" : tokenProvider.get();
    CloudBuildSupport.Setup setup = CloudBuildSupport.prepare(getName(), token, context);
    if (setup.error != null) {
      return setup.error;
    }

    String workflow = input.optString("workflow", "").trim();
    if (!workflow.isEmpty() && !CloudBuildSupport.isSafeWorkflowName(workflow)) {
      return error("workflow 参数只能是文件名（如 build.yml），不能包含路径。");
    }
    String branch = input.optString("branch", "").trim();

    if (context != null) {
      context.reportProgress("查询 GitHub Actions 状态…");
    }
    try {
      String summary = setup.service.describeLatestRun(
          workflow.isEmpty() ? null : workflow,
          branch.isEmpty() ? null : branch);
      return ok(summary);
    } catch (Exception e) {
      return error("查询运行状态失败：" + CloudBuildSupport.describeApiError(e));
    }
  }
}
