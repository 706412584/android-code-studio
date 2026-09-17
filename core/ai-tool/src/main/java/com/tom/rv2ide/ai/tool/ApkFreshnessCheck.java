/*
 * This file is part of AndroidCodeStudio.
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

package com.tom.rv2ide.ai.tool;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 判断待安装的 APK 是否可信。
 *
 * <p><b>为什么需要</b>：模型会谎报构建成功。真机实测：模型删掉编译错误后<b>没有重新构建</b>，
 * 却报告「构建成功，APK 已生成」，并引用了 9 小时前那次构建留下的旧 APK 路径。
 * 若直接采信，安装的就是旧包——自动化测试会在错误的产物上给出「通过」。
 *
 * <p><b>为什么不能只比时间戳</b>：Gradle 增量构建判定 up-to-date 时不会重写 APK，
 * 此时 APK 的修改时间合法地早于本次构建开始时间。只看时间戳会把正常的增量构建
 * 误判为陈旧产物。
 *
 * <p>因此判定依据是<b>最近一次构建的结果</b>：
 * <ul>
 *   <li>最近一次构建失败 → 阻断。APK 必然是更早的产物，安装它没有意义
 *   <li>最近一次构建成功但 APK 未重写 → 放行，但提示这是增量构建
 *   <li>无构建记录（用户手动构建过等）→ 放行，不阻断
 * </ul>
 *
 * <p>纯 Java、无 Android 依赖，因此判定分支可被单测锁定。
 */
public final class ApkFreshnessCheck {

  /** 判定结果。 */
  public static final class Result {
    private final boolean installable;
    private final String message;

    Result(boolean installable, String message) {
      this.installable = installable;
      this.message = message == null ? "" : message;
    }

    /** 放行且无附加说明。 */
    public static Result ok() {
      return new Result(true, "");
    }

    /**
     * 放行，但附带说明。
     *
     * <p>用于「允许安装但需告知」的情况，例如增量构建未重写 APK。
     */
    public static Result ok(String message) {
      return new Result(true, message);
    }

    /** 阻断安装。 */
    public static Result blocked(String reason) {
      return new Result(false, reason);
    }

    /** 是否允许安装。 */
    public boolean isInstallable() {
      return installable;
    }

    /**
     * 附带给模型的信息。
     *
     * <p>阻断时是拒绝原因；放行时可能是提示（如增量构建未重写 APK），也可能为空。
     */
    public String getMessage() {
      return message;
    }
  }

  private ApkFreshnessCheck() {}

  /**
   * 判定 APK 是否可用于安装。
   *
   * @param apkLastModifiedMs APK 文件的修改时间（{@code File.lastModified()}）
   * @param lastBuildSuccessful 最近一次构建是否成功；{@code null} 表示无记录
   * @param lastBuildStartedAtMs 最近一次构建的开始时间；{@code null} 表示无记录
   */
  public static Result check(
      long apkLastModifiedMs, Boolean lastBuildSuccessful, Long lastBuildStartedAtMs) {

    if (lastBuildSuccessful == null || lastBuildStartedAtMs == null) {
      // 没有构建记录：可能是用户手动构建的，不应阻断
      return new Result(true, "");
    }

    if (!lastBuildSuccessful) {
      return new Result(
          false,
          "拒绝安装：最近一次构建失败，这个 APK 是更早一次构建留下的旧产物。\n"
              + "  APK 文件时间: "
              + formatTime(apkLastModifiedMs)
              + "\n"
              + "  最近一次构建开始于: "
              + formatTime(lastBuildStartedAtMs)
              + "\n"
              + "请先修复构建错误、重新构建成功后再安装；安装旧包会让后续测试在错误的产物上运行。");
    }

    if (apkLastModifiedMs < lastBuildStartedAtMs) {
      // 构建成功但 APK 未重写 → Gradle 判定 up-to-date，内容仍是最新的，属正常
      return new Result(
          true,
          "注意：APK 未在本次构建中重新生成（Gradle 判定无需重做，属正常增量构建）。\n"
              + "  APK 文件时间: "
              + formatTime(apkLastModifiedMs)
              + "\n"
              + "  最近一次构建开始于: "
              + formatTime(lastBuildStartedAtMs)
              + "\n");
    }

    return new Result(true, "");
  }

  private static String formatTime(long millis) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
  }
}
