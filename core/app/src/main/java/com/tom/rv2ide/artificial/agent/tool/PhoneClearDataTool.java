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
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * 清除某个应用的数据（{@code pm clear <pkg>}）。
 *
 * <p>用途：真机测试需要「干净初始状态」时（首次启动、登录态重置、缓存清空）。
 * {@code pm clear} 会删除应用的内部存储、数据库、SharedPreferences 与缓存，
 * 是<b>破坏性且不可逆</b>的操作。
 *
 * <p><b>为什么必须限定目标包名</b>：{@code pm clear} 接受任意包名，写错一个字符
 * 就可能清掉别的应用（甚至系统应用）的数据。因此这里做三重限定：
 * <ol>
 *   <li>包名必须是合法的 Android 包名格式（至少两段、每段以字母开头）
 *   <li>显式拒绝 {@code android} 与 {@code com.android.*} / {@code com.google.android.*} 等系统包
 *   <li>整个操作需经确认门（{@link #needsConfirmation()}）
 * </ol>
 *
 * <p>需要 adb 级权限（Shizuku 后端）；未启用时返回明确错误。
 */
public final class PhoneClearDataTool extends BaseTool {

  private static final long TIMEOUT_MS = 30_000L;

  /**
   * 合法包名：至少两段，每段以字母开头，段内允许字母/数字/下划线。
   * 比 Android 实际规则略严（不接受段首数字、连字符），够用且能挡住明显误填。
   */
  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

  private final ShellBackendRegistry registry;

  public PhoneClearDataTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_CLEAR_DATA;
  }

  @Override
  public String getDescription() {
    return "清除指定应用的数据（pm clear），使应用回到全新安装状态。"
        + "**破坏性且不可逆**：会删除该应用的数据库、偏好与缓存。"
        + "仅接受合法包名；拒绝系统包（android / com.android.* / com.google.android.*）。"
        + "需要 adb 级权限（Shizuku 后端）。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean needsConfirmation() {
    // 破坏性操作，必须经确认门。
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
                        .put("description", "要清除数据的应用包名，如 com.example.myapp")))
        .put("required", new org.json.JSONArray().put("packageName"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String packageName = input.optString("packageName", "").trim();
    if (packageName.isEmpty()) {
      return error("packageName 不能为空。");
    }

    String rejection = validatePackage(packageName);
    if (rejection != null) {
      return error(rejection);
    }

    if (context != null) {
      context.reportProgress("清除应用数据: " + packageName);
    }

    PhoneUiSelector.Exec exec =
        PhoneUiSelector.exec(registry, "pm clear " + packageName, TIMEOUT_MS);
    if (!exec.ok) {
      return error(exec.error);
    }

    // pm clear 成功时输出 "Success"，失败时输出 "Failed"。退出码可能仍为 0，
    // 因此必须检查 stdout，不能只看 exec.ok。
    String out = exec.stdout.trim();
    if (out.toLowerCase(java.util.Locale.ROOT).contains("failed")) {
      return error("pm clear 失败：" + (out.isEmpty() ? "(无输出)" : out) + "\n包名: " + packageName);
    }
    return ok("已清除应用数据: " + packageName + (out.isEmpty() ? "" : "（" + out + "）"));
  }

  /**
   * 校验包名。通过返回 null，否则返回面向模型的错误说明。
   */
  static String validatePackage(String packageName) {
    if (!PACKAGE_PATTERN.matcher(packageName).matches()) {
      return "不是合法的包名: "
          + packageName
          + "。应形如 com.example.myapp（至少两段，每段以字母开头）。";
    }
    if ("android".equals(packageName)
        || packageName.startsWith("com.android.")
        || packageName.startsWith("android.")
        || packageName.startsWith("com.google.android.")) {
      return "拒绝清除系统包: " + packageName + "。pm clear 系统应用可能破坏设备。";
    }
    return null;
  }
}
