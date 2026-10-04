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
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 查询当前前台 Activity 与包名。
 *
 * <p><b>用途</b>：判断「应用是否真的启动到了预期界面」「点击后是否跳转成功」「是否弹出了
 * 系统对话框（如权限弹窗）」。仅靠 {@code launch_app} 的成功返回无法确认界面状态——
 * 启动成功只代表进程起来了，不代表显示的是目标 Activity。
 *
 * <p><b>实现</b>：优先解析 {@code dumpsys activity activities} 的
 * {@code mResumedActivity / topResumedActivity}；部分 ROM 不输出该行，再回退到
 * {@code dumpsys window} 的 {@code mCurrentFocus / mFocusedApp}。
 *
 * <p><b>权限</b>：需要 adb 级权限（Shizuku）；无权限时明确报错。
 */
public final class PhoneCurrentActivityTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(PhoneCurrentActivityTool.class);

  private static final long ACTIVITY_TIMEOUT_MS = 15_000L;
  private static final long WINDOW_TIMEOUT_MS = 15_000L;

  /** 新版 dumpsys 的当前 Activity 行。 */
  private static final Pattern RESUMED_ACTIVITY =
      Pattern.compile("(?:mResumedActivity|topResumedActivity|ResumedActivity)\\s*[:=]\\s*(.*)");

  /** 回退：窗口焦点。 */
  private static final Pattern CURRENT_FOCUS = Pattern.compile("mCurrentFocus\\s*=\\s*(.*)");

  /** 回退：焦点应用。 */
  private static final Pattern FOCUSED_APP = Pattern.compile("mFocusedApp\\s*=\\s*(.*)");

  /** ActivityRecord 里的 task id，如 {@code t1234}。 */
  private static final Pattern TASK_ID = Pattern.compile("\\bt(\\d+)\\b");

  private final ShellBackendRegistry shellBackends;

  public PhoneCurrentActivityTool(ShellBackendRegistry shellBackends) {
    this.shellBackends = shellBackends;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_CURRENT_ACTIVITY;
  }

  @Override
  public String getDescription() {
    return "查询设备当前前台的 Activity 与包名。"
        + "用于确认应用是否启动到预期界面、点击后是否跳转成功、是否弹出了系统对话框。"
        + "需要 adb 级权限（Shizuku 后端）；无权限时会明确报错。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
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
                    "expectedPackage",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "可选：期望的包名。给出时结果会明确标注是否与当前前台包名一致，便于断言"))
                .put(
                    "raw",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否附带命中的原始 dumpsys 行，便于排查解析异常，默认 false")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String expectedPackage = input.optString("expectedPackage", "").trim();
    boolean raw = input.optBoolean("raw", false);

    if (context != null) {
      context.reportProgress("查询前台界面");
    }

    // 主来源：activity 栈。
    PhoneUiSelector.Exec activityExec =
        PhoneUiSelector.exec(shellBackends, "dumpsys activity activities", ACTIVITY_TIMEOUT_MS);
    if (!activityExec.ok) {
      return error("查询前台 Activity 失败。\n" + activityExec.error);
    }

    Focus focus = parseFocus(activityExec.stdout, RESUMED_ACTIVITY);
    String source = "dumpsys activity activities";

    // 回退：window 焦点（部分 ROM 的 activity dump 不含 resumed 行）。
    if (focus == null) {
      PhoneUiSelector.Exec windowExec =
          PhoneUiSelector.exec(shellBackends, "dumpsys window", WINDOW_TIMEOUT_MS);
      if (windowExec.ok) {
        focus = parseFocus(windowExec.stdout, CURRENT_FOCUS);
        if (focus == null) {
          focus = parseFocus(windowExec.stdout, FOCUSED_APP);
        }
        if (focus != null) {
          source = "dumpsys window";
        }
      }
    }

    if (focus == null) {
      return error(
          "无法从 dumpsys 输出中解析出当前前台 Activity。\n"
              + "可能原因：设备处于锁屏 / 无焦点窗口，或该 ROM 的 dumpsys 输出格式不受支持。\n"
              + "可先用 phone_screenshot 观察当前界面。");
    }

    StringBuilder sb = new StringBuilder();
    sb.append("当前前台界面\n");
    sb.append("  包名: ").append(focus.packageName).append('\n');
    sb.append("  Activity: ").append(focus.activityName).append('\n');
    if (focus.taskId > 0) {
      sb.append("  任务栈: t").append(focus.taskId).append('\n');
    }
    sb.append("  来源: ").append(source).append('\n');

    if (!expectedPackage.isEmpty()) {
      boolean matched = expectedPackage.equals(focus.packageName);
      sb.append("  与期望包名 ").append(expectedPackage).append(": ")
          .append(matched ? "一致 ✓" : "不一致 ✗")
          .append('\n');
    }

    if (raw) {
      sb.append("\n原始行:\n").append(focus.rawLine).append('\n');
    }

    return ok(sb.toString());
  }

  /** 解析结果。 */
  private static final class Focus {
    final String packageName;
    final String activityName;
    final int taskId;
    final String rawLine;

    Focus(String packageName, String activityName, int taskId, String rawLine) {
      this.packageName = packageName;
      this.activityName = activityName;
      this.taskId = taskId;
      this.rawLine = rawLine;
    }
  }

  /** 在文本里找到第一个匹配 pattern 的行，并从中抽出组件。 */
  private static Focus parseFocus(String text, Pattern pattern) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) {
      return null;
    }
    String line = matcher.group(matcher.groupCount()).trim();
    String[] component = PhoneUiSelector.extractComponent(line);
    if (component == null) {
      return null;
    }
    int taskId = 0;
    Matcher taskMatcher = TASK_ID.matcher(line);
    if (taskMatcher.find()) {
      try {
        taskId = Integer.parseInt(taskMatcher.group(1));
      } catch (RuntimeException e) {
        log.debug("解析 task id 失败: {}", line);
      }
    }
    return new Focus(component[0], component[1], taskId, line);
  }
}
