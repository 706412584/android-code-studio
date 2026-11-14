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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * 列出 Android SDK 组件的已装与可装版本。
 *
 * <p><b>为什么需要它</b>：AI 修「AGP 要求 build-tools ≥ 36 但设备只有 35」这类问题时，
 * 此前只能靠 {@code shell_execute} 手敲 {@code sdkmanager --list}（还得自己拼
 * {@code sh} 调起与 JAVA_HOME，见 {@link SdkManagerInstaller} 的说明），拿到的是一大段
 * 原始文本。本工具直接把结果整理成「已装 / 可装」两组，模型一眼看出缺哪个版本。
 *
 * <p>只读、无副作用，只读模式下也放行（同 {@link ProjectInfoTool}）。
 */
public final class SdkListTool extends BaseTool {

  /** 单组件的可装版本上限，避免把 sdkmanager 的完整列表（几十项）倒进上下文。 */
  private static final int MAX_AVAILABLE = 40;

  @Override
  public String getName() {
    return ToolNames.SDK_LIST;
  }

  @Override
  public String getDescription() {
    return "列出 Android SDK 组件（build-tools / platforms / NDK / CMake / platform-tools）的"
        + "**已安装版本**与**可安装版本**。"
        + "构建报「要求 build-tools x.y.z」或「找不到 platform android-NN」时先调它，"
        + "看清设备上有什么、还差什么，再用 sdk_install 装缺的那个。只读，不需要确认。"
        + "它查的是官方 sdkmanager 的可用列表（直连 Google 仓库），不是 IDE 镜像"
        + "——镜像的 build-tools 最高只到 35.0.1，AGP 9 要 ≥36。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put("properties", new JSONObject())
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    // sdkmanager 不可用时（未装 cmdline-tools / 目录不可读 / 缺 JDK）明确说明原因，
    // 而不是回一个空列表让模型以为「什么都装好了」。
    String unavailable = SdkManagerInstaller.unavailableReason();
    if (unavailable != null) {
      return error("sdkmanager 不可用：" + unavailable);
    }

    if (context != null) {
      context.reportProgress("查询 SDK 可用版本");
    }

    SdkManagerInstaller.AvailableSdk available;
    try {
      // Kotlin 的默认实参在 Java 侧不可见，显式传 null 表示「不要逐行输出」。
      available = new SdkManagerInstaller().listAvailable(null);
    } catch (RuntimeException e) {
      return error("查询 SDK 版本失败：" + e.getMessage());
    }
    if (available == null) {
      return error(
          "无法获取可安装版本（sdkmanager --list 失败，通常是网络不通）。已安装的如下：\n"
              + installedSection());
    }

    StringBuilder sb = new StringBuilder();
    sb.append(installedSection()).append('\n');
    sb.append("可安装（来自官方仓库；某项为空表示已装齐或该组件不适用）：\n");
    appendAvailable(sb, "build-tools", available.getBuildTools());
    appendAvailable(sb, "platforms", available.getPlatforms());
    appendAvailable(sb, "ndk", available.getNdk());
    appendAvailable(sb, "cmake", available.getCmake());
    appendAvailable(sb, "platform-tools", available.getPlatformTools());
    sb.append("\n安装用 sdk_install，包名形如 build-tools;36.0.0 / platforms;android-37.2 / "
        + "ndk;26.1.10909125 / cmake;3.22.1。");
    sb.append("\n平台包名**必须与上面列出的完全一致**：API 36 起有次要版本"
        + "（android-36.1 / android-37.0 / 37.1 / 37.2），少写小数后缀会因包不存在而失败。");
    sb.append("\n注意（实测）：官方仓库的 CMake 只有 x86-64，arm64 设备装了也跑不了；"
        + "本机能用的 CMake 是 ACS 自带的 4.1.2。原生项目要显式钉住该版本"
        + "（见 android-native-build skill）。");
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  /** 已安装组件的清单（读 $ANDROID_HOME 下对应目录）。 */
  private static String installedSection() {
    StringBuilder sb = new StringBuilder("已安装：\n");
    appendInstalled(sb, "build-tools", SdkManagerInstaller.installedBuildTools());

    // platforms 的目录名是 android-NN，这里只显示 NN（与 sdk_install 的版本参数一致）。
    List<String> platforms = new ArrayList<>();
    for (String platform : SdkManagerInstaller.installedPlatforms()) {
      platforms.add(
          platform.startsWith("android-") ? platform.substring("android-".length()) : platform);
    }
    appendInstalled(sb, "platforms", platforms);

    appendInstalled(sb, "ndk", SdkManagerInstaller.installedNdk());
    appendInstalled(sb, "cmake", SdkManagerInstaller.installedCmake());

    // platform-tools 无版本号，只报有没有。
    sb.append("  platform-tools: ")
        .append(SdkManagerInstaller.isPlatformToolsInstalled() ? "已安装" : "(无)")
        .append('\n');
    return sb.toString();
  }

  private static void appendInstalled(StringBuilder sb, String label, List<String> versions) {
    sb.append("  ").append(label).append(": ")
        .append(versions.isEmpty() ? "(无)" : String.join(", ", versions))
        .append('\n');
  }

  private static void appendAvailable(StringBuilder sb, String label, List<String> list) {
    if (list == null || list.isEmpty()) {
      return;
    }
    sb.append("  ").append(label).append(": ");
    if (list.size() > MAX_AVAILABLE) {
      // 列表按升序，最新的才有升级意义，只列尾部。
      sb.append("(共 ").append(list.size()).append(" 项，列最新的 ").append(MAX_AVAILABLE).append(") ");
      sb.append(String.join(", ", list.subList(list.size() - MAX_AVAILABLE, list.size())));
    } else {
      sb.append(String.join(", ", list));
    }
    sb.append('\n');
  }
}
