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
import com.tom.rv2ide.ai.tool.ApkFreshnessCheck;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.projects.builder.BuildOutcome;
import com.tom.rv2ide.projects.builder.BuildService;
import io.github.miyazkaori.silentinstaller.SilentInstaller;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 安装 APK 到本机。
 *
 * <p>这是「运行 app 测试」闭环的第二步（构建 → 安装 → 启动 → 读日志）。
 *
 * <p><b>权限链路</b>：优先走 Shizuku 静默安装（需要用户已激活 Shizuku）；
 * 未激活时回退到系统安装器（会弹出确认界面，需要用户手动点确认）。
 * 结果里<b>必须如实报告实际走了哪条路径</b>——模型需要知道安装是否真正完成，
 * 否则会在应用还没装上时就去启动它。
 */
public final class InstallApkTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(InstallApkTool.class);

  /** 安装等待上限：2 分钟。 */
  private static final long INSTALL_TIMEOUT_MS = 120_000L;

  private final Context appContext;

  public InstallApkTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String getName() {
    return "install_apk";
  }

  @Override
  public String getDescription() {
    return "把构建产出的 APK 安装到本机。"
        + "优先使用 Shizuku 静默安装；Shizuku 不可用时回退到系统安装器（需用户在界面上确认）。"
        + "结果会说明实际使用的安装方式。";
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
    // 安装应用会改变设备状态，确认模式下应经用户同意。
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
                    "apkPath",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "APK 文件的绝对路径，通常来自 gradle_build 的结果"))
                .put(
                    "useShizuku",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否尝试 Shizuku 静默安装，默认 true")))
        .put("required", new org.json.JSONArray().put("apkPath"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apkPath", "").trim();
    if (apkPath.isEmpty()) {
      return error("apkPath 不能为空");
    }

    File apk = new File(apkPath);
    if (!apk.exists() || !apk.isFile()) {
      return error("APK 文件不存在: " + apkPath);
    }
    if (!apkPath.endsWith(".apk")) {
      return error("指定的文件不是 APK: " + apkPath);
    }

    // 独立核验产物是否可信，不采信模型的自述。
    // 实测：模型删掉编译错误后没有重新构建，却报告「构建成功、APK 已生成」，
    // 并引用了 9 小时前的旧 APK。装旧包会让后续测试在错误的产物上给出「通过」。
    ApkFreshnessCheck.Result freshness = checkFreshness(apk);
    if (!freshness.isInstallable()) {
      return error(freshness.getMessage());
    }

    boolean preferShizuku = input.optBoolean("useShizuku", true);

    if (context != null) {
      context.reportProgress("安装 APK: " + apk.getName());
    }

    if (preferShizuku) {
      ShizukuInstallOutcome outcome = installViaShizuku(apk);
      if (outcome.handled) {
        return outcome.success
            ? ok("安装成功（Shizuku 静默安装）。\nAPK: " + apk.getName())
            : error("Shizuku 安装失败: " + outcome.message);
      }
      // Shizuku 不可用 → 明确告知将回退，而不是静默切换
      log.info("Shizuku 不可用（{}），回退到系统安装器", outcome.message);
    }

    return installViaSystemInstaller(apk);
  }

  /**
   * 核验 APK 是否为最近一次构建的产物。
   *
   * <p>构建服务不可用时（如未打开项目）不阻断——此时也无从判断，交由安装本身报错。
   */
  private ApkFreshnessCheck.Result checkFreshness(File apk) {
    BuildService service = lookupBuildService();
    if (service == null) {
      return ApkFreshnessCheck.Result.ok();
    }
    BuildOutcome outcome;
    try {
      outcome = service.getLastBuildOutcome();
    } catch (RuntimeException e) {
      log.debug("读取构建结果失败，跳过产物核验", e);
      return ApkFreshnessCheck.Result.ok();
    }
    if (outcome == null) {
      log.info("产物核验：无构建记录，放行 {}", apk.getName());
      return ApkFreshnessCheck.Result.ok();
    }
    ApkFreshnessCheck.Result result =
        ApkFreshnessCheck.check(
            apk.lastModified(), outcome.getSuccessful(), outcome.getStartedAtMs());
    log.info(
        "产物核验：apkModified={} buildOk={} buildStartedAt={} installable={}",
        apk.lastModified(),
        outcome.getSuccessful(),
        outcome.getStartedAtMs(),
        result.isInstallable());
    return result;
  }

  private static BuildService lookupBuildService() {
    try {
      return com.tom.rv2ide.lookup.Lookup.getDefault()
          .lookup(BuildService.KEY_BUILD_SERVICE);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Shizuku 静默安装的结果。 */
  private static final class ShizukuInstallOutcome {
    /** 是否已由 Shizuku 路径处理（无论成败）。false 表示 Shizuku 不可用，需要回退。 */
    final boolean handled;
    final boolean success;
    final String message;

    ShizukuInstallOutcome(boolean handled, boolean success, String message) {
      this.handled = handled;
      this.success = success;
      this.message = message == null ? "" : message;
    }

    static ShizukuInstallOutcome unavailable(String reason) {
      return new ShizukuInstallOutcome(false, false, reason);
    }
  }

  private ShizukuInstallOutcome installViaShizuku(File apk) {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<ShizukuInstallOutcome> result = new AtomicReference<>();

    try {
      SilentInstaller.install(
          apk,
          new SilentInstaller.InstallCallback() {
            @Override
            public void onPermissionDenied() {
              // 权限被拒：Shizuku 存在但未授权，应回退而不是报错终止
              result.set(ShizukuInstallOutcome.unavailable("Shizuku 权限未授予"));
              latch.countDown();
            }

            @Override
            public void onSuccess(int status, String message) {
              result.set(new ShizukuInstallOutcome(true, true, message));
              latch.countDown();
            }

            @Override
            public void onFailure(int status, String message, Throwable tr) {
              result.set(new ShizukuInstallOutcome(true, false, message));
              latch.countDown();
            }
          });
    } catch (RuntimeException | LinkageError e) {
      // 类缺失或运行时异常都视为不可用，走回退路径
      return ShizukuInstallOutcome.unavailable(
          e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    try {
      if (!latch.await(INSTALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return new ShizukuInstallOutcome(true, false, "安装超时");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ShizukuInstallOutcome(true, false, "安装被中断");
    }
    ShizukuInstallOutcome outcome = result.get();
    return outcome == null ? new ShizukuInstallOutcome(true, false, "未收到安装回调") : outcome;
  }

  /**
   * 回退路径：打开系统安装器。
   *
   * <p>这是<b>异步且需要用户交互</b>的——系统会弹出确认界面。因此结果里必须说明
   * 「已请求安装，等待用户确认」，而不是声称安装已完成。
   */
  private ToolResult installViaSystemInstaller(File apk) {
    try {
      android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
      String authority = appContext.getPackageName() + ".providers.fileprovider";
      android.net.Uri uri =
          androidx.core.content.FileProvider.getUriForFile(appContext, authority, apk);
      intent.setDataAndType(uri, "application/vnd.android.package-archive");
      intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      appContext.startActivity(intent);
      return ok(
          "已打开系统安装器，等待用户在界面上确认安装。\n"
              + "注意：这是异步操作，安装尚未完成。用户确认后才能启动应用。\n"
              + "APK: "
              + apk.getName());
    } catch (RuntimeException e) {
      return error("无法打开系统安装器: " + e.getMessage());
    }
  }
}
