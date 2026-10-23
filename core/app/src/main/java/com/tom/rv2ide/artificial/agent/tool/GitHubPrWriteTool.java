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
import com.tom.rv2ide.cloudbuild.GitHubPullRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * GitHub Pull Request 的**写**工具：创建 / 评论 / 关闭 / 合并。
 *
 * <p><b>merge 是这里唯一不可逆的动作</b>：它把提交并进目标分支，本地 {@code git reset}
 * 回滚不了远端。因此权限规则按 action 区分（见 {@code ToolPermissionRule}），
 * 「允许评论一次」不会顺带放行合并。
 *
 * <p><b>不做 force push / 删分支</b>：那些属于 {@code git_write} 的职责，
 * 这里只碰 GitHub 侧的 PR 对象。
 */
public final class GitHubPrWriteTool extends BaseTool {

  private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

  static {
    ACTIONS.put("create", "创建 PR（需 title、head、base；可选 body / draft）");
    ACTIONS.put("comment", "给 PR 加评论（需 number 与 body）");
    ACTIONS.put("close", "关闭 PR（需 number）");
    ACTIONS.put("merge", "合并 PR（需 number；可选 method=merge/squash/rebase）");
  }

  private final GitHubActionsBuildTool.TokenProvider tokenProvider;

  public GitHubPrWriteTool(GitHubActionsBuildTool.TokenProvider tokenProvider) {
    this.tokenProvider = tokenProvider;
  }

  @Override
  public String getName() {
    return ToolNames.GITHUB_PR_WRITE;
  }

  @Override
  public String getDescription() {
    return "对当前项目 GitHub 仓库的 Pull Request 执行写操作："
        + "create（新建，需 title + head + base）、comment（需 number + body）、"
        + "close（需 number）、merge（需 number，可选 method=merge/squash/rebase）。"
        + "每次调用需要用户确认（AUTO 模式除外）。"
        + "merge 会把提交并进目标分支，无法用本地 git 回滚——执行前必须确认用户明确要求合并。"
        + "只读查询用 github_pr。";
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
  public boolean isAllowedInReadonlyMode() {
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject action = new JSONObject().put("type", "string").put("description", "操作类型");
    StringBuilder hint = new StringBuilder("可用值：");
    for (Map.Entry<String, String> e : ACTIONS.entrySet()) {
      hint.append("\n- ").append(e.getKey()).append(": ").append(e.getValue());
    }
    action.put("description", action.getString("description") + "\n" + hint);
    action.put("enum", new org.json.JSONArray(ACTIONS.keySet()));
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("action", action)
                .put(
                    "number",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "comment/close/merge 必填：PR 编号"))
                .put(
                    "title",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=create 必填：PR 标题"))
                .put(
                    "head",
                    new JSONObject()
                        .put("type", "string")
                        .put("description",
                            "action=create 必填：源分支（同仓库只写分支名；跨 fork 写 user:branch）"))
                .put(
                    "base",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=create 必填：目标分支，如 main"))
                .put(
                    "body",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "create 的正文 / comment 的评论内容"))
                .put(
                    "draft",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "action=create 可选：创建为草稿 PR"))
                .put(
                    "method",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=merge 可选：合并方式，默认 merge")
                        .put("enum",
                            new org.json.JSONArray().put("merge").put("squash").put("rebase"))))
        .put("required", new org.json.JSONArray().put("action"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    GitHubIssueSupport.Setup setup =
        GitHubIssueSupport.prepare(getName(), tokenProvider, context);
    if (setup.error != null) {
      return setup.error;
    }

    String action = input.optString("action", "").trim().toLowerCase(java.util.Locale.ROOT);
    if (!ACTIONS.containsKey(action)) {
      return error("不支持的 action：" + action + "。可用值：" + String.join(" / ", ACTIONS.keySet()));
    }

    try {
      switch (action) {
        case "create": {
          for (String[] required : new String[][] {
              {"title", "标题"}, {"head", "源分支"}, {"base", "目标分支"}}) {
            String missing = GitHubIssueSupport.requireNonBlank(input, required[0], required[1]);
            if (missing != null) {
              return error(missing);
            }
          }
          if (context != null) {
            context.reportProgress("创建 PR…");
          }
          GitHubPullRequest pull = setup.service.getClient().createPullRequest(
              input.optString("title", ""),
              input.optString("head", ""),
              input.optString("base", ""),
              input.optString("body", ""),
              input.optBoolean("draft", false));
          return ok("已创建 PR #" + pull.number + "：" + GitHubIssueSupport.oneLine(pull.title)
              + "\n" + pull.headRef + " → " + pull.baseRef
              + (pull.htmlUrl.isEmpty() ? "" : "\n" + pull.htmlUrl));
        }
        case "comment": {
          int number = GitHubIssueSupport.parseNumber(input);
          if (number < 0) {
            return error("action=comment 需要 PR 编号：请传 number。");
          }
          String missing = GitHubIssueSupport.requireNonBlank(input, "body", "评论内容");
          if (missing != null) {
            return error(missing);
          }
          if (context != null) {
            context.reportProgress("评论 PR #" + number + "…");
          }
          // PR 的评论与 issue 共用同一个端点。
          String url = setup.service.getClient()
              .addComment(number, input.optString("body", ""));
          return ok("已评论 PR #" + number + (url.isEmpty() ? "" : "\n" + url));
        }
        case "close": {
          int number = GitHubIssueSupport.parseNumber(input);
          if (number < 0) {
            return error("action=close 需要 PR 编号：请传 number。");
          }
          if (context != null) {
            context.reportProgress("关闭 PR #" + number + "…");
          }
          GitHubPullRequest pull = setup.service.getClient().closePullRequest(number);
          return ok("PR #" + number + " 现在" + pull.stateText()
              + (pull.htmlUrl.isEmpty() ? "" : "\n" + pull.htmlUrl));
        }
        case "merge": {
          int number = GitHubIssueSupport.parseNumber(input);
          if (number < 0) {
            return error("action=merge 需要 PR 编号：请传 number。");
          }
          if (context != null) {
            context.reportProgress("合并 PR #" + number + "…");
          }
          String message = setup.service.getClient()
              .mergePullRequest(number, input.optString("method", ""));
          return ok("PR #" + number + " 合并结果：" + message);
        }
        default:
          return error("不支持的 action：" + action);
      }
    } catch (Exception e) {
      return error("操作失败：" + GitHubIssueSupport.describeError(e));
    }
  }
}
