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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * 等待界面条件成立（带超时）。
 *
 * <p>真机测试里，点击之后界面往往需要时间加载/跳转。直接 dump 会读到中间态，
 * 于是模型要么误判「没变化」而重复点击，要么读到半截节点树。本工具把
 * 「轮询直到条件成立或超时」收敛成一次调用，让模型不必自己写轮询。
 *
 * <p><b>支持的条件</b>（可组合，<b>全部满足</b>才算成立）：
 * <ul>
 *   <li>{@code activity} — 前台（resumed）Activity 变为指定值。支持完整
 *       {@code pkg/Activity}、完整类名、类短名或包名，按包含匹配
 *   <li>节点条件 — 复用 {@link PhoneUiSelector.Selector}：{@code resourceId} / {@code text}
 *       / {@code contentDesc} / {@code className} / {@code clickable} / {@code enabled}
 *       / {@code textContains} / {@code descContains} / {@code index}，
 *       即「某个节点出现」
 * </ul>
 * 至少给一个条件。同时给多个时是 AND（等一个确定的界面状态），不是 OR。
 *
 * <p><b>轮询策略</b>：每 {@value #POLL_INTERVAL_MS}ms 检查一次。节点条件需要
 * {@code uiautomator dump}（较慢），Activity 条件只需 {@code dumpsys}（较快）；
 * 只给 Activity 条件时轮询更密。
 *
 * <p><b>无 adb 权限时立即报错</b>：进入轮询前先用一条廉价命令探测后端，若不具备
 * adb 权限则直接返回明确错误，而不是空等满超时才报「超时」——后者会把「环境没配好」
 * 误报成「条件一直没出现」。
 *
 * <p>需要 adb 级权限（Shizuku 后端）。
 */
public final class PhoneWaitForTool extends BaseTool {

  /** 默认超时。 */
  private static final long DEFAULT_TIMEOUT_MS = 10_000L;

  /** 超时下限。 */
  private static final long MIN_TIMEOUT_MS = 1_000L;

  /** 超时上限：等待是阻塞操作，不应让一次调用挂太久。 */
  private static final long MAX_TIMEOUT_MS = 120_000L;

  /** 轮询间隔（含节点条件的场景，dump 本身较慢，间隔可小一些）。 */
  private static final long POLL_INTERVAL_MS = 600L;

  /** 单次 dump 的超时。 */
  private static final long DUMP_TIMEOUT_MS = PhoneUiSelector.DEFAULT_DUMP_TIMEOUT_MS;

  /** 探测/查询 Activity 的命令超时。 */
  private static final long PROBE_TIMEOUT_MS = 10_000L;

  /**
   * 取前台 Activity 的命令。
   *
   * <p>先看 {@code dumpsys activity}（Android 10 的 {@code mResumedActivity}、
   * Android 13+ 的 {@code topResumedActivity} 都含 "ResumedActivity"）；取不到时
   * 退回窗口焦点（{@code mCurrentFocus}），二者都带 {@code pkg/Activity} 形态，
   * 可由 {@link PhoneUiSelector#extractComponent} 解析。
   */
  private static final String ACTIVITY_CMD =
      "dumpsys activity activities | grep -m1 ResumedActivity"
          + " || dumpsys window windows | grep -m1 mCurrentFocus";

  private final ShellBackendRegistry registry;

  /** 仅用于定位 dump 临时文件目录（app 外部私有目录），避免写公共存储根。 */
  private final android.content.Context appContext;

  public PhoneWaitForTool(android.content.Context appContext, ShellBackendRegistry registry) {
    this.appContext = appContext;
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_WAIT_FOR;
  }

  @Override
  public String getDescription() {
    return "等待界面条件成立，超时后报错。用于点击/启动后等待界面稳定，避免读到中间态。"
        + "条件二选一或组合（组合时全部满足才算成立）："
        + "① activity=期望的前台 Activity（支持 pkg/Activity、类名、短名或包名，包含匹配）；"
        + "② 节点条件 resourceId/text/contentDesc/className（含义同 phone_click_view）。"
        + "至少给一个条件。默认超时 "
        + DEFAULT_TIMEOUT_MS
        + "ms（范围 "
        + MIN_TIMEOUT_MS
        + "-"
        + MAX_TIMEOUT_MS
        + "）。需要 adb 级权限（Shizuku 后端）。";
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
    // 纯观察：轮询只是反复执行 phone_view_hierarchy 式的 dump/dumpsys，零副作用——
    // 不点击、不输入、不改任何状态。确认门保护的是副作用而非「是否执行了命令」，
    // 因此与 phone_view_hierarchy（同为 READ + 免确认）保持一致，不设 needsConfirmation。
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
                    "activity",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "期望的前台 Activity。支持 com.example/.MainActivity、"
                                + "com.example.MainActivity、MainActivity 或包名 com.example，包含匹配"))
                .put(
                    "resourceId",
                    new JSONObject().put("type", "string").put("description", "等待该 resource-id 的节点出现"))
                .put(
                    "text",
                    new JSONObject().put("type", "string").put("description", "等待该 text 的节点出现（默认精确，textContains=true 为包含）"))
                .put(
                    "contentDesc",
                    new JSONObject().put("type", "string").put("description", "等待该 content-desc 的节点出现"))
                .put(
                    "className",
                    new JSONObject().put("type", "string").put("description", "等待该类的节点出现"))
                .put("textContains", new JSONObject().put("type", "boolean").put("description", "text 用包含匹配"))
                .put("descContains", new JSONObject().put("type", "boolean").put("description", "contentDesc 用包含匹配"))
                .put(
                    "timeoutMs",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "超时毫秒数。默认 "
                                + DEFAULT_TIMEOUT_MS
                                + "，范围 "
                                + MIN_TIMEOUT_MS
                                + "-"
                                + MAX_TIMEOUT_MS)))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    PhoneUiSelector.Selector selector = PhoneUiSelector.Selector.fromJson(input);
    boolean wantNode = !selector.isEmpty();
    String activity = input.optString("activity", "").trim();
    boolean wantActivity = !activity.isEmpty();

    if (!wantNode && !wantActivity) {
      return error(
          "至少提供一个条件：activity，或一个节点条件（resourceId / text / contentDesc / className）。");
    }

    long timeout = clampTimeout(input.optDouble("timeoutMs", DEFAULT_TIMEOUT_MS));

    // 先探测后端是否有 adb 权限：没有就立即报错，不要空等满超时。
    PhoneUiSelector.Exec probe = PhoneUiSelector.exec(registry, "true", PROBE_TIMEOUT_MS);
    if (!probe.ok) {
      return error(probe.error);
    }

    if (context != null) {
      context.reportProgress("等待条件: " + describeConditions(selector, activity, wantNode, wantActivity));
    }

    long start = System.currentTimeMillis();
    long deadline = start + timeout;
    String lastNote = "";
    boolean lastNodeFound = false;
    String lastActivitySeen = "";

    while (true) {
      boolean nodeOk = true;
      boolean activityOk = true;

      if (wantNode) {
        // 用完整树（compressed=false）：压缩会省略既无 text 又无 id 的节点，
        // 可能把「节点其实在」误判成「未出现」，导致等待空转到超时。
        PhoneUiSelector.Dump dump =
            PhoneUiSelector.dump(appContext, registry, false, DUMP_TIMEOUT_MS);
        if (dump.isOk()) {
          lastNodeFound = PhoneUiSelector.find(dump.root, selector) != null;
          nodeOk = lastNodeFound;
        } else {
          // 中途 dump 失败多为界面在动画/转场，视为「条件未成立」，继续轮询。
          nodeOk = false;
          lastNote = dump.error;
        }
      }

      if (wantActivity) {
        PhoneUiSelector.Exec exec = PhoneUiSelector.exec(registry, ACTIVITY_CMD, PROBE_TIMEOUT_MS);
        if (exec.ok) {
          String[] component = PhoneUiSelector.extractComponent(exec.stdout);
          lastActivitySeen = component == null ? "" : component[0] + "/" + component[1];
          activityOk = component != null && matchesActivity(component, activity);
        } else {
          activityOk = false;
          lastNote = exec.error;
        }
      }

      if (nodeOk && activityOk) {
        long elapsed = System.currentTimeMillis() - start;
        return ok("条件已满足（耗时 " + elapsed + "ms）。\n" + describeObserved(selector, activity, wantNode, wantActivity, lastActivitySeen));
      }

      if (System.currentTimeMillis() >= deadline) {
        StringBuilder sb = new StringBuilder();
        sb.append("等待超时（").append(timeout).append("ms）。期望条件：")
            .append(describeConditions(selector, activity, wantNode, wantActivity)).append('\n');
        if (wantActivity) {
          sb.append("当前前台 Activity：")
              .append(lastActivitySeen.isEmpty() ? "(未能读取)" : lastActivitySeen)
              .append('\n');
        }
        if (wantNode) {
          sb.append(lastNodeFound ? "节点条件：已找到匹配节点，但其它条件未满足。\n" : "节点条件：未找到匹配节点。\n");
        }
        if (!lastNote.isEmpty()) {
          sb.append("最后一次检查的说明：").append(lastNote).append('\n');
        }
        sb.append("提示：可增大 timeoutMs；或用 phone_view_hierarchy 查看当前界面实际内容后修正条件。");
        return error(sb.toString());
      }

      sleep(POLL_INTERVAL_MS);
    }
  }

  /** 成功时报告观测到的实际状态。 */
  private static String describeObserved(
      PhoneUiSelector.Selector selector,
      String activity,
      boolean wantNode,
      boolean wantActivity,
      String seenActivity) {
    List<String> parts = new ArrayList<>();
    if (wantNode) {
      parts.add("节点出现：" + selector.describe());
    }
    if (wantActivity) {
      parts.add("前台 Activity：" + seenActivity + "（期望 " + activity + "）");
    }
    return String.join("\n", parts);
  }

  private static String describeConditions(
      PhoneUiSelector.Selector selector, String activity, boolean wantNode, boolean wantActivity) {
    List<String> parts = new ArrayList<>();
    if (wantActivity) {
      parts.add("activity~=" + activity);
    }
    if (wantNode) {
      parts.add("节点 " + selector.describe());
    }
    return parts.isEmpty() ? "(空)" : String.join(" AND ", parts);
  }

  /**
   * Activity 是否匹配期望值。
   *
   * <p>期望值可写成完整 {@code pkg/Activity}、{@code pkg/.Activity}、{@code pkg/Activity短名}、
   * 完整类名、类短名或包名。{@link PhoneUiSelector#extractComponent} 会把
   * {@code pkg/.MainActivity} 展开成 {@code pkg.MainActivity}，因此这里把多种候选写法都
   * 生成出来逐一比对，避免调用方用常见的 {@code pkg/.Activity} 形式时匹配不上。
   *
   * <p>大小写不敏感；采用「候选串包含期望串」或「期望串等于包名」的宽松语义。
   */
  static boolean matchesActivity(String[] component, String wanted) {
    if (component == null || component.length < 2 || wanted == null) {
      return false;
    }
    String pkg = component[0];
    String activity = component[1];
    String wantedLower = wanted.trim().toLowerCase(java.util.Locale.ROOT);
    if (wantedLower.isEmpty()) {
      return false;
    }
    int dot = activity.lastIndexOf('.');
    String simple = dot >= 0 && dot + 1 < activity.length() ? activity.substring(dot + 1) : activity;

    String[] candidates = {
      pkg + "/" + activity, // com.example/com.example.MainActivity
      pkg + "/." + simple, // com.example/.MainActivity
      pkg + "/" + simple, // com.example/MainActivity
      activity, // com.example.MainActivity
      simple, // MainActivity
    };
    for (String candidate : candidates) {
      String lower = candidate.toLowerCase(java.util.Locale.ROOT);
      if (lower.contains(wantedLower) || lower.equals(wantedLower)) {
        return true;
      }
    }
    // 只给包名时也认（表示「仍停留在该应用」）。
    return pkg.toLowerCase(java.util.Locale.ROOT).equals(wantedLower);
  }

  private static long clampTimeout(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return DEFAULT_TIMEOUT_MS;
    }
    long rounded = Math.round(value);
    return Math.max(MIN_TIMEOUT_MS, Math.min(rounded, MAX_TIMEOUT_MS));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
