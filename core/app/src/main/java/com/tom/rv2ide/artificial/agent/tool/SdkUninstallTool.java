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
import com.tom.rv2ide.handlers.system.installer.SdkManagerInstaller;
import org.json.JSONObject;

/**
 * 卸载一个 Android SDK 组件包。
 *
 * <p><b>为什么单独成工具</b>：卸载不可逆——SDK 目录在 app 私有空间，用户删不掉也恢复不了，
 * 删了只能重新下载。混进 {@code sdk_install} 按 action 分派的话，权限层无法只对「装」
 * 放行而对「卸」收紧（同 {@code git}/{@code git_write}、{@code file_delete} 的拆分理由）。
 *
 * <p>典型用途：清理装错的版本（如误装了 35.0.1 占空间）、回滚到某个 build-tools。
 */
public final class SdkUninstallTool extends BaseTool {

  private static final int MAX_OUTPUT_CHARS = 4000;

  private final SdkManagerInstaller installer = new SdkManagerInstaller();

  @Override
  public String getName() {
    return ToolNames.SDK_UNINSTALL;
  }

  @Override
  public String getDescription() {
    return "卸载一个 Android SDK 组件包（走官方 sdkmanager --uninstall）。"
        + "packageId 用分号形式的完整包名，例如 build-tools;35.0.1、platforms;android-37.2、"
        + "ndk;26.1.10909125、cmake;3.22.1、platform-tools。"
        + "用 sdk_list 查已装的确切包名（API 36 起平台有次要版本，"
        + "如 android-36.1 / android-37.2，包名必须带小数后缀才匹配得上）。"
        + "**不可逆**：删掉后只能重新下载，且 SDK 目录在应用私有空间、用户无法手动恢复。"
        + "只该在「清理装错的版本」或「用户明确要求」时使用；不要为了省空间删除项目构建需要的版本"
        + "（build-tools 低于 AGP 要求的版本会让构建直接失败）。";
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
    // 破坏性且不可逆，与 file_delete 同级。
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
                    "packageId",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "完整包名，形如 build-tools;35.0.1 / platforms;android-37.2 / "
                                + "ndk;26.1.10909125 / cmake;3.22.1 / platform-tools")))
        .put("required", new org.json.JSONArray().put("packageId"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String packageId = input.optString("packageId", "").trim();
    if (packageId.isEmpty()) {
      return error("packageId 不能为空，例如 build-tools;35.0.1。");
    }

    String unavailable = SdkManagerInstaller.unavailableReason();
    if (unavailable != null) {
      return error("sdkmanager 不可用，无法卸载：" + unavailable);
    }

    if (context != null) {
      context.reportProgress("卸载 SDK 组件 " + packageId);
    }

    StringBuilder tail = new StringBuilder();
    boolean ok;
    try {
      ok =
          installer.uninstall(
              packageId,
              line -> {
                tail.append(line).append('\n');
                if (tail.length() > MAX_OUTPUT_CHARS) {
                  tail.delete(0, tail.length() - MAX_OUTPUT_CHARS);
                }
                return kotlin.Unit.INSTANCE;
              });
    } catch (RuntimeException e) {
      return error("卸载 " + packageId + " 失败（异常）：" + e.getMessage());
    }

    if (!ok) {
      return error("卸载 " + packageId + " 失败。sdkmanager 输出尾部：\n" + tail);
    }
    return ok(
        "已卸载 " + packageId + "。\n"
            + "（若项目仍引用该版本，下次构建会报缺少组件——需要的话用 sdk_install 装回。）\n"
            + "sdkmanager 输出尾部：\n"
            + tail);
  }
}
