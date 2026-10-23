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
import com.tom.rv2ide.cloudbuild.GitHubIssue;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * GitHub Issue 的**写**工具：创建 / 评论 / 关闭 / 重新打开。
 *
 * <p><b>为什么需要确认</b>：这些操作会立刻对**公开可见**的仓库产生副作用，
 * 且 create/comment 一旦发出无法删除（只能编辑或关闭）。与 {@code git_write}
 * 同级处理：逐次确认，AUTO 模式除外。
 *
 * <p><b>为什么按 action 细分授权</b>：见 {@code ToolPermissionRule#githubWriteScope}——
 * 「允许评论一次」不该顺带放行「关闭 issue」。
 */
public final class GitHubIssueWriteTool extends BaseTool {

  /** 各 action 白名单与说明。 */
  private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

  static {
    ACTIONS.put("create", "创建 issue（需 title；可选 body / labels）");
    ACTIONS.put("comment", "给 issue 加评论（需 number 与 body）");
    ACTIONS.put("close", "关闭 issue（需 number）");
    ACTIONS.put("reopen", "重新打开 issue（需 number）");
  }

  private final GitHubActionsBuildTool.TokenProvider tokenProvider;

  public GitHubIssueWriteTool(GitHubActionsBuildTool.TokenProvider tokenProvider) {
    this.tokenProvider = tokenProvider;
  }

  @Override
  public String getName() {
    return ToolNames.GITHUB_ISSUE_WRITE;
  }

  @Override
  public String getDescription() {
    return "对当前项目 GitHub 仓库的 issue 执行写操作：create（新建，需 title）、"
        + "comment（评论，需 number + body）、close（关闭）、reopen（重新打开）。"
        + "每次调用需要用户确认（AUTO 模式除外）。"
        + "创建与评论对公开仓库立即公开可见且无法删除，调用前先确认用户意图。"
        + "只读查询用 github_issue。";
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

  /** 写操作，只读模式下不放行。 */
  @Override
  public boolean isAllowedInReadonlyMode() {
    return false;
  }

  /** 刻意保持串行：同一次对话里「创建 → 评论」的顺序不能被打乱。 */

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
                        .put("description", "comment/close/reopen 必填：issue 编号"))
                .put(
                    "title",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=create 必填：标题"))
                .put(
                    "body",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "create 的正文 / comment 的评论内容"))
                .put(
                    "labels",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=create 可选：逗号分隔的标签，如 bug,ui")))
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
          String missing = GitHubIssueSupport.requireNonBlank(input, "title", "标题");
          if (missing != null) {
            return error(missing);
          }
          if (context != null) {
            context.reportProgress("创建 issue…");
          }
          GitHubIssue issue = setup.service.getClient().createIssue(
              input.optString("title", ""),
              input.optString("body", ""),
              input.optString("labels", ""));
          return ok("已创建 issue #" + issue.number + "：" + GitHubIssueSupport.oneLine(issue.title)
              + "\n" + issue.htmlUrl);
        }
        case "comment": {
          int number = GitHubIssueSupport.parseNumber(input);
          if (number < 0) {
            return error("action=comment 需要 issue 编号：请传 number。");
          }
          String missing = GitHubIssueSupport.requireNonBlank(input, "body", "评论内容");
          if (missing != null) {
            return error(missing);
          }
          if (context != null) {
            context.reportProgress("评论 issue #" + number + "…");
          }
          String url = setup.service.getClient()
              .addComment(number, input.optString("body", ""));
          return ok("已评论 issue #" + number + (url.isEmpty() ? "" : "\n" + url));
        }
        case "close":
        case "reopen": {
          int number = GitHubIssueSupport.parseNumber(input);
          if (number < 0) {
            return error("action=" + action + " 需要 issue 编号：请传 number。");
          }
          if (context != null) {
            context.reportProgress(("close".equals(action) ? "关闭" : "重新打开")
                + " issue #" + number + "…");
          }
          GitHubIssue issue = setup.service.getClient()
              .setIssueState(number, "close".equals(action) ? "closed" : "open");
          return ok("issue #" + number + " 现在" + issue.stateText()
              + "\n" + issue.htmlUrl);
        }
        default:
          return error("不支持的 action：" + action);
      }
    } catch (Exception e) {
      return error("操作失败：" + GitHubIssueSupport.describeError(e));
    }
  }
}
