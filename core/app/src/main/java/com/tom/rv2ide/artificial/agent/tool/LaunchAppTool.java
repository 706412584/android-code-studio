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
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动已安装的应用，并返回结构化的启动结果。
 *
 * <p>「运行 app 测试」闭环的第三步（构建 → 安装 → 启动 → 读日志）。
 *
 * <p><b>为什么改用 {@code am start -W}</b>：{@code IntentUtils.launchApp} 只能回答
 * 「Intent 发出去了没有」，无法回答「目标 Activity 到底起来了没有、花了多久」。
 * 后者才是验证真正需要的信号——尤其是「启动后立刻截图」这类场景，必须等到
 * 首帧就绪再动作，否则截到的是空白/上一屏。
 *
 * <p>因此这里优先走 shell 后端的 {@code am start -W}：
 * <ul>
 *   <li>{@code -W} 让 {@code am} 阻塞到启动完成，并打印
 *       {@code Status / Activity / ThisTime / TotalTime / WaitTime}
 *   <li>结果被解析成结构化字段（{@link #parseAmStart}），模型拿到的是
 *       {@code Status: ok} + 耗时数字，而不是一大段需要自己正则的文本
 * </ul>
 *
 * <p><b>回退</b>：没有 adb 级 shell 后端（例如 Shizuku 未授权）时，回退到原有的
 * {@code IntentUtils.launchApp}。此时结果里<b>如实说明</b>拿不到耗时与目标 Activity，
 * 避免模型把「已发出 Intent」误当成「已验证启动成功」。
 */
public final class LaunchAppTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(LaunchAppTool.class);

  /** am start -W 的等待上限：20 秒。冷启动慢的设备留足余量。 */
  private static final long AM_START_TIMEOUT_MS = 20_000L;

  /** 解析 am 输出用的正则。 */
  private static final Pattern P_STATUS = Pattern.compile("^\\s*Status:\\s*(\\S+)", Pattern.MULTILINE);

  private static final Pattern P_ACTIVITY =
      Pattern.compile("^\\s*Activity:\\s*(\\S+)", Pattern.MULTILINE);
  private static final Pattern P_THIS_TIME =
      Pattern.compile("^\\s*ThisTime:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_TOTAL_TIME =
      Pattern.compile("^\\s*TotalTime:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_WAIT_TIME =
      Pattern.compile("^\\s*WaitTime:\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern P_ERROR =
      Pattern.compile("^\\s*Error:\\s*(.+)$", Pattern.MULTILINE);
  private static final Pattern P_EXCEPTION =
      Pattern.compile("^\\s*Exception occurred while executing.*$", Pattern.MULTILINE);
  private static final Pattern P_COMPONENT =
      Pattern.compile("^\\s*([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)", Pattern.MULTILINE);

  private final Context appContext;
  private final ShellBackendRegistry shellBackends;

  /** 最近一次 am start 因无 Shizuku 而不可用的原因，用于回退结果里如实说明。 */
  private volatile String lastAmUnavailableNote = "";

  public LaunchAppTool(Context context) {
    this(context, PhoneShellRunner.defaultRegistry(context));
  }

  /**
   * @param context 应用上下文
   * @param shellBackends shell 后端注册表，可为 null（此时只走 Intent 回退路径）
   */
  public LaunchAppTool(Context context, ShellBackendRegistry shellBackends) {
    this.appContext = context.getApplicationContext();
    this.shellBackends = shellBackends;
  }

  @Override
  public String getName() {
    return "launch_app";
  }

  @Override
  public String getDescription() {
    return "启动本机已安装的应用（按包名），返回结构化启动结果："
        + "Status（ok/失败）、目标 Activity、ThisTime/TotalTime/WaitTime 耗时（毫秒）。"
        + "启动后用 logcat_read 查看它的日志，或用 logcat_read mode=crash 判定是否崩溃。"
        + "包名通常来自 gradle_build 返回的 applicationId。";
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
                        .put("description", "应用的包名，如 com.example.myapp")))
        .put("required", new org.json.JSONArray().put("packageName"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String packageName = input.optString("packageName", "").trim();
    if (packageName.isEmpty()) {
      return error("packageName 不能为空");
    }
    // 拼接进 shell 命令（am start / resolve-activity）前先校验，杜绝命令注入。
    if (!PhoneShellRunner.isValidPackage(packageName)) {
      return error(
          "非法的包名: \"" + packageName + "\"（只允许字母、数字、下划线与点，且形如 com.example.app）");
    }

    if (context != null) {
      context.reportProgress("启动应用: " + packageName);
    }

    // 先确认已安装，给出比「启动失败」更明确的错误
    if (!isInstalled(packageName)) {
      return error("应用未安装: " + packageName + "。请先构建并安装。");
    }

    // 优先：am start -W（可拿到 Status / 目标 Activity / 耗时）
    String component = resolveLauncherComponent(packageName);
    if (component != null) {
      LaunchOutcome outcome = amStart(component);
      if (outcome != null) {
        return outcome.toResult(getName(), packageName);
      }
    }

    // 回退：Intent 启动（拿不到耗时与目标 Activity）
    return fallbackLaunch(packageName);
  }

  /** am start -W 的结构化解析结果。 */
  static final class LaunchOutcome {
    final boolean ok;
    final String status;
    final String activity;
    final String thisTimeMs;
    final String totalTimeMs;
    final String waitTimeMs;
    final String error;
    final String raw;

    private LaunchOutcome(
        boolean ok,
        String status,
        String activity,
        String thisTimeMs,
        String totalTimeMs,
        String waitTimeMs,
        String error,
        String raw) {
      this.ok = ok;
      this.status = status;
      this.activity = activity;
      this.thisTimeMs = thisTimeMs;
      this.totalTimeMs = totalTimeMs;
      this.waitTimeMs = waitTimeMs;
      this.error = error;
      this.raw = raw;
    }

    ToolResult toResult(String toolName, String packageName) {
      StringBuilder sb = new StringBuilder();
      sb.append("Status: ").append(status.isEmpty() ? "unknown" : status).append('\n');
      sb.append("Package: ").append(packageName).append('\n');
      if (!activity.isEmpty()) {
        sb.append("Activity: ").append(activity).append('\n');
      }
      if (!thisTimeMs.isEmpty() || !totalTimeMs.isEmpty() || !waitTimeMs.isEmpty()) {
        sb.append("ThisTime: ")
            .append(orDash(thisTimeMs))
            .append(" ms, TotalTime: ")
            .append(orDash(totalTimeMs))
            .append(" ms, WaitTime: ")
            .append(orDash(waitTimeMs))
            .append(" ms\n");
      }
      if (!error.isEmpty()) {
        sb.append("Error: ").append(error).append('\n');
      }
      if (ok) {
        sb.append("提示：启动已完成。可用 logcat_read 查看日志，或 logcat_read mode=crash 判定是否崩溃。");
        return ToolResult.of("", toolName, sb.toString(), false);
      }
      sb.append("提示：启动未成功。请确认包名/启动 Activity 是否正确，或改用 shell_execute 查看 am 原始输出。");
      return ToolResult.of("", toolName, sb.toString(), true);
    }

    private static String orDash(String value) {
      return value == null || value.isEmpty() ? "-" : value;
    }
  }

  /**
   * 通过 {@link PhoneShellRunner}（严格要求 Shizuku）执行 {@code am start -W} 并解析输出。
   *
   * @return 解析结果；无 adb 权限或输出无法解析时返回 null，交由调用方回退
   */
  private LaunchOutcome amStart(String component) {
    // -W: 等待启动完成；-n: 指定组件，避免歧义
    PhoneShellRunner.Output output =
        PhoneShellRunner.exec(shellBackends, "am start -W -n " + component, AM_START_TIMEOUT_MS);
    if ("none".equals(output.channel)) {
      // 无 Shizuku：交由 Intent 回退路径，并带上原因
      lastAmUnavailableNote = output.note;
      return null;
    }
    LaunchOutcome parsed = parseAmStart(output.combined());
    if (parsed != null) {
      return parsed;
    }
    // am 输出无法解析 → 回退
    log.info("am start -W 输出无法解析，回退到 Intent 启动");
    return null;
  }

  /**
   * 解析 {@code am start -W} 的输出。
   *
   * <p>典型输出：
   * <pre>
   * Starting: Intent { cmp=com.example/.MainActivity }
   * Status: ok
   * Activity: com.example/.MainActivity
   * ThisTime: 234
   * TotalTime: 456
   * WaitTime: 470
   * Complete
   * </pre>
   * 失败时通常有 {@code Error: ...} 或 {@code Exception occurred ...}。
   *
   * @return 解析结果；输出中既无 Status 也无 Error 时返回 null（表示不是 am 的输出）
   */
  static LaunchOutcome parseAmStart(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    String status = group(P_STATUS, text);
    String activity = group(P_ACTIVITY, text);
    String error = group(P_ERROR, text);
    String exception = group(P_EXCEPTION, text);

    if (status.isEmpty() && error.isEmpty() && exception.isEmpty()) {
      // 不是 am start 的输出
      return null;
    }

    boolean ok = "ok".equalsIgnoreCase(status) && error.isEmpty() && exception.isEmpty();
    if (activity.isEmpty()) {
      // 从 "Starting: Intent { cmp=... }" 里兜底取组件
      activity = group(P_COMPONENT, text);
    }
    String thisTime = group(P_THIS_TIME, text);
    String totalTime = group(P_TOTAL_TIME, text);
    String waitTime = group(P_WAIT_TIME, text);
    String errorText = error.isEmpty() ? exception : error;

    return new LaunchOutcome(
        ok, status, activity, thisTime, totalTime, waitTime, errorText, text);
  }

  private static String group(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    if (matcher.find()) {
      String value = matcher.group(1);
      return value == null ? "" : value.trim();
    }
    return "";
  }

  /**
   * 解析包的启动 Activity（launcher component）。
   *
   * <p>用 {@code cmd package resolve-activity --brief <pkg>} 拿到 {@code pkg/.Activity}。
   * 解析不到时返回 null，由调用方回退到 Intent 启动。
   */
  private String resolveLauncherComponent(String packageName) {
    PhoneShellRunner.Output output =
        PhoneShellRunner.exec(
            shellBackends, "cmd package resolve-activity --brief " + packageName, 10_000L);
    for (String line : output.combined().split("\\r?\\n")) {
      String trimmed = line.trim();
      // 形如 com.example/.MainActivity 或 com.example/com.example.MainActivity
      if (trimmed.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) {
        return trimmed;
      }
    }
    return null;
  }

  /**
   * 回退路径：用 {@code IntentUtils.launchApp} 发出启动 Intent。
   *
   * <p>必须主线程执行——{@code IntentUtils.launchApp} 内部会通过 Toast 报告错误，
   * 而 Toast 在非主线程调用会抛 {@code Can't toast on a thread that has not called Looper.prepare()}。
   */
  private ToolResult fallbackLaunch(String packageName) {
    java.util.concurrent.atomic.AtomicBoolean launched =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

    Runnable launchTask =
        () -> {
          try {
            launched.set(
                com.tom.rv2ide.utils.IntentUtils.INSTANCE.launchApp(
                    appContext, packageName, false));
          } catch (RuntimeException e) {
            launched.set(false);
          } finally {
            latch.countDown();
          }
        };

    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      launchTask.run();
    } else {
      new android.os.Handler(android.os.Looper.getMainLooper()).post(launchTask);
      try {
        if (!latch.await(15_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
          return error("启动超时（主线程无响应）: " + packageName);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return error("启动被中断: " + packageName);
      }
    }

    if (launched.get()) {
      StringBuilder sb = new StringBuilder();
      sb.append("已发出启动 Intent: ").append(packageName).append('\n');
      sb.append("注意：当前没有 adb 级 shell 后端，拿不到 Status/耗时/目标 Activity，");
      sb.append("无法确认目标界面是否真正就绪。可用 phone_current_activity 核对前台界面。\n");
      if (!lastAmUnavailableNote.isEmpty()) {
        sb.append("原因: ").append(lastAmUnavailableNote);
      }
      return ok(sb.toString());
    }
    return error("启动失败: " + packageName + "。请确认它已安装且有可启动的 Activity。");
  }

  private boolean isInstalled(String packageName) {
    try {
      appContext.getPackageManager().getPackageInfo(packageName, 0);
      return true;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return false;
    } catch (RuntimeException e) {
      // 查询异常时不阻断启动尝试，交由启动结果判定
      return true;
    }
  }
}
