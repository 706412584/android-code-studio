/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import android.graphics.Bitmap;
import android.os.Build;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.artificial.agent.a11y.AcsAccessibilityService;
import org.json.JSONObject;

/**
 * 用无障碍服务**读取**屏幕（不需要 Shizuku/adb 权限）。
 *
 * <p><b>与 shell 系工具的分工</b>：{@code phone_screenshot} / {@code phone_view_hierarchy}
 * 经 shell 后端执行（`screencap`、`uiautomator dump`），需要 Shizuku；本工具走无障碍服务，
 * 只需用户在系统设置里开启服务。两者都能用时优先 shell 系（输出更完整）；Shizuku 不可用时
 * 本工具是唯一选择。
 *
 * <p><b>为什么与写动作拆开</b>：权限层按工具分级（{@code needsConfirmation()} 无参数），
 * 读与写混在一起会让每次读屏都弹确认框。同 {@code git}/{@code git_write} 的拆分。
 *
 * <p>移植自 Aharou `A11yTool` 的只读动作（dump/find/shot/windows/foreground），
 * 逻辑照搬，仅适配基类与结果类型。
 */
public final class PhoneA11yTool extends BaseTool {

  public PhoneA11yTool() {}

  @Override
  public String getName() {
    return ToolNames.PHONE_A11Y;
  }

  @Override
  public String getDescription() {
    return "用无障碍服务读取屏幕（**不需要 Shizuku/adb 权限**，需用户在系统设置里开启本应用的无障碍服务）。"
        + "适合没装 Shizuku 时读界面/找控件/看前台应用。"
        + "action 可选："
        + "dump=读当前界面元素树（每个节点带短 id，如 a3f2）；"
        + "find=按文字找节点（text 参数）；"
        + "shot=系统截图（图片随结果返回，**需 Android 11+**）；"
        + "windows=窗口列表（含输入法窗口）；"
        + "foreground=前台应用与 Activity。"
        + "节点 id 60 秒过期，用 phone_a11y_write 点击前建议重新 dump/find。"
        + "与 phone_screenshot/phone_view_hierarchy 的区别：那些需要 Shizuku 但输出更完整，"
        + "两者都能用时优先它们。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
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
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "读取动作：dump / find / shot / windows / foreground")
                        .put(
                            "enum",
                            new org.json.JSONArray()
                                .put("dump")
                                .put("find")
                                .put("shot")
                                .put("windows")
                                .put("foreground")))
                .put(
                    "text",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "find 专用：要搜索的文字（大小写不敏感，匹配 text 与 contentDescription）")))
        .put("required", new org.json.JSONArray().put("action"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "").trim().toLowerCase();
    if (action.isEmpty()) {
      return error("缺少 action（dump / find / shot / windows / foreground）。");
    }

    AcsAccessibilityService service = AcsAccessibilityService.getInstance();
    if (service == null) {
      return error(PhoneA11ySupport.notEnabledMessage());
    }

    if (context != null) {
      context.reportProgress("无障碍读取: " + action);
    }

    try {
      switch (action) {
        case "dump":
          return ok(PhoneA11ySupport.dumpTree(service));

        case "find":
          {
            String query = input.optString("text", "").trim();
            if (query.isEmpty()) {
              return error("find 需要 text 参数。");
            }
            return ok(PhoneA11ySupport.findNodes(service, query));
          }

        case "shot":
          {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
              return error(
                  "系统截图需要 Android 11+（当前 SDK "
                      + Build.VERSION.SDK_INT
                      + "）。可改用 phone_screenshot（需 Shizuku）。");
            }
            AcsAccessibilityService.ShotResult shot = service.captureScreenshot();
            Bitmap bitmap = shot.getBitmap();
            if (bitmap == null) {
              return error("截图失败：" + shot.getErrorCode() + " " + shot.getErrorMessage());
            }
            String base64 = PhoneA11ySupport.bitmapToJpegBase64(bitmap);
            bitmap.recycle();

            String pkg = service.foregroundPackage().getFirst();
            String activity = PhoneA11ySupport.currentActivity(service);
            StringBuilder note = new StringBuilder("已截图（无障碍通道）");
            if (pkg != null && !pkg.isEmpty()) {
              note.append(" · ").append(pkg);
            }
            if (activity != null && !activity.isEmpty()) {
              note.append(" · ").append(activity);
            }
            return ToolResult.withImage(getName(), note.toString(), "image/jpeg", base64);
          }

        case "windows":
          return ok(PhoneA11ySupport.formatWindows(service));

        case "foreground":
          {
            String pkg = service.foregroundPackage().getFirst();
            String activity = PhoneA11ySupport.currentActivity(service);
            JSONObject out = new JSONObject();
            out.put("package", pkg == null ? "" : pkg);
            out.put("activity", activity == null ? "" : activity);
            return ok(out.toString());
          }

        default:
          return error("不支持的 action：" + action + "（只读动作：dump / find / shot / windows / foreground）。");
      }
    } catch (Throwable t) {
      return error("无障碍读取失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }
}
