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

import android.content.Context;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读取应用的内存与渲染性能，返回<b>结构化摘要</b>。
 *
 * <p>数据源：
 * <ul>
 *   <li>{@code dumpsys meminfo <pkg>} — 内存（PSS / 堆 / 对象计数）
 *   <li>{@code dumpsys gfxinfo <pkg>} — 帧耗时与卡顿率（janky frames、分位耗时）
 * </ul>
 *
 * <p><b>为什么不直接把 dump 返回给模型</b>：{@code dumpsys meminfo} 单个应用动辄
 * 上百行、{@code gfxinfo} 带整段直方图，原始输出会挤占上下文且淹没关键数字。
 * 这里只提取验证真正需要的字段（见 {@link #parseMeminfo} / {@link #parseGfxinfo}），
 * 原始文本一律丢弃。
 *
 * <p><b>权限</b>：查询被测应用需要 adb 级权限（Shizuku）。无权限时 dumpsys 返回空，
 * 结果会明确说明，而不是把空当成「0 内存」。
 */
public final class PhoneMemInfoTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(PhoneMemInfoTool.class);

  private static final long MEMINFO_TIMEOUT_MS = 15_000L;
  private static final long GFXINFO_TIMEOUT_MS = 20_000L;

  /** App Summary 里要提取的字段（按行首标签匹配）。 */
  private static final Pattern P_SUMMARY_LINE =
      Pattern.compile("^\\s*(Java Heap|Native Heap|Code|Stack|Graphics|Private Other|System|"
          + "TOTAL PSS|TOTAL RSS)\\s*:\\s*(\\d+)", Pattern.MULTILINE);

  private static final Pattern P_TOTAL_ROW = Pattern.compile("^\\s*TOTAL\\s+(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_VIEWS = Pattern.compile("^\\s*Views:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_ACTIVITIES =
      Pattern.compile("^\\s*Activities:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_APP_CONTEXTS =
      Pattern.compile("^\\s*AppContexts:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_WEBVIEWS =
      Pattern.compile("^\\s*WebViews:\\s*(\\d+)", Pattern.MULTILINE);

  private static final Pattern P_TOTAL_FRAMES =
      Pattern.compile("^\\s*Total frames rendered:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_JANKY =
      Pattern.compile("^\\s*Janky frames:\\s*(\\d+)\\s*\\(([\\d.]+)%\\)", Pattern.MULTILINE);
  private static final Pattern P_PERCENTILE =
      Pattern.compile("^\\s*(\\d+)(?:st|nd|rd|th) percentile:\\s*(\\d+)ms", Pattern.MULTILINE);
  private static final Pattern P_MISSED_VSYNC =
      Pattern.compile("^\\s*Number Missed Vsync:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_SLOW_UI =
      Pattern.compile("^\\s*Number Slow UI thread:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_SLOW_DRAW =
      Pattern.compile("^\\s*Number Slow issue draw commands:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_MISSED_DEADLINE =
      Pattern.compile("^\\s*Number Frame deadline missed:\\s*(\\d+)", Pattern.MULTILINE);
  /** 旧版 gfxinfo 的渲染耗时（Render time）。 */
  private static final Pattern P_RENDER_TIME =
      Pattern.compile("^\\s*Render time:\\s*([\\d.]+)ms", Pattern.MULTILINE);

  private final Context appContext;
  private final ShellBackendRegistry shellBackends;

  public PhoneMemInfoTool(Context context) {
    this(context, PhoneShellRunner.defaultRegistry(context));
  }

  public PhoneMemInfoTool(Context context, ShellBackendRegistry shellBackends) {
    this.appContext = context.getApplicationContext();
    this.shellBackends = shellBackends;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_MEM_INFO;
  }

  @Override
  public String getDescription() {
    return "查询应用的内存与渲染性能，返回结构化摘要。"
        + "内存含 TOTAL PSS、Java/Native Heap、Code、Graphics、Views/Activities 计数；"
        + "渲染含总帧数、卡顿帧数与卡顿率、50/90/95/99 分位帧耗时、错过 Vsync 等。"
        + "只返回关键数字，不返回 dumpsys 原始大段输出。需要 adb 级权限（Shizuku）。";
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
                    "packageName",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "应用的包名，如 com.example.myapp"))
                .put(
                    "metrics",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "要查询的指标：memory / gfx / both（默认 both）")
                        .put(
                            "enum",
                            new org.json.JSONArray().put("memory").put("gfx").put("both"))))
        .put("required", new org.json.JSONArray().put("packageName"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String packageName = input.optString("packageName", "").trim();
    if (packageName.isEmpty()) {
      return error("packageName 不能为空");
    }
    // 拼接进 shell 命令前先校验，杜绝命令注入（uid 2000 下危害更大）。
    if (!PhoneShellRunner.isValidPackage(packageName)) {
      return error(
          "非法的包名: \"" + packageName + "\"（只允许字母、数字、下划线与点，且形如 com.example.app）");
    }
    String metrics = input.optString("metrics", "both").trim().toLowerCase();
    boolean wantMemory = !"gfx".equals(metrics);
    boolean wantGfx = !"memory".equals(metrics);

    if (context != null) {
      context.reportProgress("查询内存/渲染: " + packageName);
    }

    StringBuilder sb = new StringBuilder();
    sb.append("应用: ").append(packageName).append('\n');

    String pid = PhoneShellRunner.findPid(shellBackends, packageName);
    sb.append("PID: ").append(pid == null ? "(未运行)" : pid).append('\n');

    boolean anyData = false;
    String permissionNote = "";

    if (wantMemory) {
      PhoneShellRunner.Output out =
          PhoneShellRunner.exec(
              shellBackends, "dumpsys meminfo " + packageName, MEMINFO_TIMEOUT_MS);
      if (!out.note.isEmpty()) {
        permissionNote = out.note;
      }
      Map<String, String> mem = parseMeminfo(out.combined());
      sb.append("\n[内存 meminfo]\n");
      if (mem.isEmpty()) {
        sb.append("(无数据)\n");
      } else {
        anyData = true;
        appendMem(sb, mem);
      }
    }

    if (wantGfx) {
      PhoneShellRunner.Output out =
          PhoneShellRunner.exec(
              shellBackends, "dumpsys gfxinfo " + packageName, GFXINFO_TIMEOUT_MS);
      if (!out.note.isEmpty()) {
        permissionNote = out.note;
      }
      Map<String, String> gfx = parseGfxinfo(out.combined());
      sb.append("\n[渲染 gfxinfo]\n");
      if (gfx.isEmpty()) {
        sb.append("(无数据)\n");
      } else {
        anyData = true;
        appendGfx(sb, gfx);
      }
    }

    if (!anyData && pid == null) {
      sb.append("\n说明: 未找到正在运行的进程。请先用 launch_app 启动应用。");
    }
    if (!anyData && !permissionNote.isEmpty()) {
      sb.append("\n原因: ").append(permissionNote);
    }

    return ok(sb.toString());
  }

  // ---------------------------------------------------------------------------
  // 解析
  // ---------------------------------------------------------------------------

  /**
   * 解析 {@code dumpsys meminfo} 的关键字段。
   *
   * <p>优先取 App Summary 段落；没有该段时回退到明细表的 TOTAL 行。
   * 返回保持插入顺序的「标签 → 值（KB 或计数）」。
   */
  static Map<String, String> parseMeminfo(String text) {
    Map<String, String> result = new LinkedHashMap<>();
    if (text == null || text.isEmpty()) {
      return result;
    }

    // App Summary（现代 ROM）
    boolean inSummary = false;
    for (String line : text.split("\\r?\\n")) {
      if (line.contains("App Summary")) {
        inSummary = true;
        continue;
      }
      if (inSummary) {
        Matcher m = P_SUMMARY_LINE.matcher(line);
        if (m.find()) {
          result.put(m.group(1).trim(), m.group(2));
        }
        // Summary 段通常以 Objects 段开始而结束
        if (line.trim().startsWith("Objects")) {
          break;
        }
      }
    }

    if (!result.containsKey("TOTAL PSS")) {
      Matcher total = P_TOTAL_ROW.matcher(text);
      if (total.find()) {
        result.put("TOTAL PSS", total.group(1));
      }
    }

    putIfFound(result, "Views", P_VIEWS, text);
    putIfFound(result, "Activities", P_ACTIVITIES, text);
    putIfFound(result, "AppContexts", P_APP_CONTEXTS, text);
    putIfFound(result, "WebViews", P_WEBVIEWS, text);

    return result;
  }

  /**
   * 解析 {@code dumpsys gfxinfo} 的关键字段：总帧数、卡顿帧/率、分位耗时、
   * 错过 Vsync / 慢 UI / 慢绘制 / 错过帧截止。
   */
  static Map<String, String> parseGfxinfo(String text) {
    Map<String, String> result = new LinkedHashMap<>();
    if (text == null || text.isEmpty()) {
      return result;
    }

    putIfFound(result, "Total frames", P_TOTAL_FRAMES, text);

    Matcher janky = P_JANKY.matcher(text);
    if (janky.find()) {
      result.put("Janky frames", janky.group(1));
      result.put("Janky rate", janky.group(2) + "%");
    }

    Matcher pct = P_PERCENTILE.matcher(text);
    while (pct.find()) {
      result.put(pct.group(1) + "th percentile", pct.group(2) + "ms");
    }

    putIfFound(result, "Missed Vsync", P_MISSED_VSYNC, text);
    putIfFound(result, "Slow UI thread", P_SLOW_UI, text);
    putIfFound(result, "Slow draw commands", P_SLOW_DRAW, text);
    putIfFound(result, "Missed deadline", P_MISSED_DEADLINE, text);
    putIfFound(result, "Render time", P_RENDER_TIME, text);

    return result;
  }

  private static void putIfFound(
      Map<String, String> target, String label, Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    if (matcher.find()) {
      target.put(label, matcher.group(1));
    }
  }

  private static void appendMem(StringBuilder sb, Map<String, String> mem) {
    for (Map.Entry<String, String> entry : mem.entrySet()) {
      String label = entry.getKey();
      String value = entry.getValue();
      boolean count =
          label.equals("Views")
              || label.equals("Activities")
              || label.equals("AppContexts")
              || label.equals("WebViews");
      sb.append("  ")
          .append(label)
          .append(": ")
          .append(value)
          .append(count ? "" : " KB")
          .append('\n');
    }
  }

  private static void appendGfx(StringBuilder sb, Map<String, String> gfx) {
    for (Map.Entry<String, String> entry : gfx.entrySet()) {
      sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
    }
  }
}
