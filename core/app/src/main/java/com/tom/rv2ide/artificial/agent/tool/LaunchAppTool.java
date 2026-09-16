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
import android.os.Handler;
import android.os.Looper;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * 启动已安装的应用。
 *
 * <p>「运行 app 测试」闭环的第三步。
 *
 * <p><b>必须主线程执行</b>：{@code IntentUtils.launchApp} 内部会通过 Toast 报告错误，
 * 而 Toast 在非主线程调用会抛 {@code Can't toast on a thread that has not called Looper.prepare()}。
 * 因此这里用 Handler 投递到主线程并等待结果。
 */
public final class LaunchAppTool extends BaseTool {

  /** 启动等待上限：15 秒。启动是即时操作，超时说明系统无响应。 */
  private static final long LAUNCH_TIMEOUT_MS = 15_000L;

  private final Context appContext;

  public LaunchAppTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String getName() {
    return "launch_app";
  }

  @Override
  public String getDescription() {
    return "启动本机已安装的应用（按包名）。"
        + "启动后用 logcat_read 查看它的日志。"
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

    if (context != null) {
      context.reportProgress("启动应用: " + packageName);
    }

    // 先确认已安装，给出比「启动失败」更明确的错误
    if (!isInstalled(packageName)) {
      return error("应用未安装: " + packageName + "。请先构建并安装。");
    }

    AtomicBoolean launched = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);

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

    if (Looper.myLooper() == Looper.getMainLooper()) {
      launchTask.run();
    } else {
      new Handler(Looper.getMainLooper()).post(launchTask);
      try {
        if (!latch.await(LAUNCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
          return error("启动超时（主线程无响应）: " + packageName);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return error("启动被中断: " + packageName);
      }
    }

    return launched.get()
        ? ok("已启动应用: " + packageName + "\n可用 logcat_read 查看其日志。")
        : error("启动失败: " + packageName + "。请确认它已安装且有可启动的 Activity。");
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
