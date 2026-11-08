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
 * 安装一个 Android SDK 组件包。
 *
 * <p><b>为什么不让 AI 直接用 {@code idesetup --sdk}</b>：那走 IDE 的镜像 manifest，
 * build-tools 最高只到 35.0.1，而 AGP 9 要求 ≥ 36——镜像链路给不了。本工具走官方
 * {@code sdkmanager} 直连 Google 仓库（可装 build-tools 至 37、platforms 至 android-36、
 * ndk/cmake），也省掉模型自己拼 {@code sh} 调起与 JAVA_HOME 的坑。
 *
 * <p>下载几十 MB 且改变设备环境，需用户确认。
 */
public final class SdkInstallTool extends BaseTool {

  /** 保留的输出尾部长上限（字符）：sdkmanager 会打大量进度行，全量倒给模型无信息量。 */
  private static final int MAX_OUTPUT_CHARS = 4000;

  private final SdkManagerInstaller installer = new SdkManagerInstaller();

  @Override
  public String getName() {
    return ToolNames.SDK_INSTALL;
  }

  @Override
  public String getDescription() {
    return "安装一个 Android SDK 组件包（走官方 sdkmanager，直连 Google 仓库）。"
        + "packageId 用分号形式的完整包名，例如 build-tools;36.0.0、platforms;android-36、"
        + "ndk;26.1.10909125、cmake;3.22.1、platform-tools。"
        + "先调 sdk_list 看有哪些版本可装。"
        + "**不要用 idesetup --sdk 或镜像源装 SDK**——镜像的 build-tools 最高 35.0.1，"
        + "AGP 9 要 ≥36，那条路装不出来。"
        + "安装会自动接受许可；需要下载几十 MB，耗时以分钟计。";
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
    // 改变设备 SDK 环境（写 $ANDROID_HOME），且要下几十 MB。
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
                            "完整包名，形如 build-tools;36.0.0 / platforms;android-36 / "
                                + "ndk;26.1.10909125 / cmake;3.22.1 / platform-tools")))
        .put("required", new org.json.JSONArray().put("packageId"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String packageId = input.optString("packageId", "").trim();
    if (packageId.isEmpty()) {
      return error("packageId 不能为空，例如 build-tools;36.0.0。");
    }

    String unavailable = SdkManagerInstaller.unavailableReason();
    if (unavailable != null) {
      return error("sdkmanager 不可用，无法安装：" + unavailable);
    }

    if (context != null) {
      context.reportProgress("安装 SDK 组件 " + packageId);
    }

    StringBuilder tail = new StringBuilder();
    boolean installed;
    try {
      installed =
          installer.install(
              packageId,
              line -> {
                tail.append(line).append('\n');
                if (tail.length() > MAX_OUTPUT_CHARS) {
                  tail.delete(0, tail.length() - MAX_OUTPUT_CHARS);
                }
                // Kotlin 的 (String) -> Unit 在 Java 侧要求返回 kotlin.Unit。
                return kotlin.Unit.INSTANCE;
              });
    } catch (RuntimeException e) {
      return error("安装 " + packageId + " 失败（异常）：" + e.getMessage());
    }

    if (!installed) {
      return error("安装 " + packageId + " 失败。sdkmanager 输出尾部：\n" + tail);
    }
    return ok(
        "已安装 " + packageId + "。\n"
            + "（环境变化：Gradle/AGP 可能需要重新同步才能用上该组件。）\n"
            + "sdkmanager 输出尾部：\n"
            + tail);
  }
}
