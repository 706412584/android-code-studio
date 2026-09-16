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

package com.tom.rv2ide.artificial.agent;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.tom.rv2ide.ai.tool.ShellBackend;
import com.tom.rv2ide.ai.tool.ShellRequest;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rikka.shizuku.Shizuku;

/**
 * 通过 Shizuku 以 adb 级权限（uid 2000）执行 shell 命令。
 *
 * <p><b>为什么需要它</b>：{@link TermuxShellBackend} 在 app 自身 uid 下执行，
 * {@code pm install} / {@code am start} / {@code logcat} 这类命令会因权限不足失败。
 * 运行 app 测试的闭环（构建 → 安装 → 启动 → 读日志）必须要有 adb 级权限。
 *
 * <p><b>实现要点</b>（来自对 shizuku api 13.1.5 字节码的实际确认）：
 * <ul>
 *   <li>{@code Shizuku.newProcess} 是 private，无法直接调用。但 {@code Shizuku.getBinder()}
 *       是 public，因此用 {@code IShizukuService.Stub.asInterface(Shizuku.getBinder())}
 *       拿到服务代理，直接调 {@code newProcess}。
 *   <li>{@code IShizukuService} / {@code IRemoteProcess} 位于 {@code dev.rikka.shizuku:aidl}，
 *       由 {@code api} 传递引入，不需要额外声明依赖。
 *   <li>{@code waitForTimeout} 的单位参数是 {@code TimeUnit.toString()}，即 {@code "MILLISECONDS"}。
 *   <li>进程输出通过 {@link ParcelFileDescriptor} 读取，必须手动 close，否则 fd 泄漏。
 *   <li>Shizuku 未安装或未授权时 {@code getBinder()} 返回 null / 抛异常，
 *       此时 {@link #isAvailable()} 为 false，{@link #unavailableReason()} 给出具体原因。
 * </ul>
 *
 * <p><b>授权流程</b>：本类只检查已有授权，不主动弹窗请求。需要授权时调用
 * {@link #requestPermission()}，由 UI 层触发（Shizuku 的授权弹窗需要前台 Activity）。
 */
public final class ShizukuShellBackend implements ShellBackend {

  private static final Logger log = LoggerFactory.getLogger(ShizukuShellBackend.class);

  public static final String ID = "shizuku";

  /** Shizuku 授权状态：已授权。 */
  private static final int PERMISSION_GRANTED = 0;

  /** 单次读取输出的缓冲大小。 */
  private static final int READ_BUFFER_SIZE = 8192;

  /** 单个输出流的最大读取字节数，防止 OOM。 */
  private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

  private final Context appContext;

  public ShizukuShellBackend(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String displayName() {
    return "Shizuku (adb 权限)";
  }

  @Override
  public boolean hasAdbPrivileges() {
    return true;
  }

  /** Shizuku 服务端是否已安装（无论是否授权）。 */
  public static boolean isShizukuInstalled(Context context) {
    try {
      context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
      return true;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  @Override
  public boolean isAvailable() {
    if (!isShizukuInstalled(appContext)) {
      return false;
    }
    try {
      if (!Shizuku.pingBinder()) {
        return false;
      }
      return Shizuku.checkSelfPermission() == PERMISSION_GRANTED;
    } catch (Throwable e) {
      // Shizuku 在未就绪时会抛各种 RuntimeException/IllegalStateException
      log.debug("Shizuku 不可用: {}", e.toString());
      return false;
    }
  }

  @Override
  public String unavailableReason() {
    if (!isShizukuInstalled(appContext)) {
      return "Shizuku 未安装。需要 adb 权限执行 pm install / am start 时，"
          + "请先安装 Shizuku（moe.shizuku.privileged.api）并完成授权。";
    }
    try {
      if (!Shizuku.pingBinder()) {
        return "Shizuku 服务未运行。请打开 Shizuku 应用并启动服务"
            + "（部分机型每次重启后需要重新启动）。";
      }
      if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) {
        return "AndroidCodeStudio 尚未获得 Shizuku 授权。请在 Shizuku 应用中授予本应用权限。";
      }
    } catch (Throwable e) {
      return "Shizuku 状态检查失败: " + e.getMessage();
    }
    return "";
  }

  /**
   * 请求 Shizuku 授权。必须在主线程调用，且应用处于前台。
   *
   * @return true 表示已发起请求，结果通过 Shizuku 的
   *     {@code OnRequestPermissionResultListener} 异步返回
   */
  public boolean requestPermission() {
    try {
      Shizuku.requestPermission(0);
      return true;
    } catch (Throwable e) {
      log.warn("请求 Shizuku 授权失败", e);
      return false;
    }
  }

  @Override
  public ShellRequest.ShellResult execute(
      ShellRequest request, ShellRequest.ShellOutputSink sink) {
    if (request == null || request.getCommand().trim().isEmpty()) {
      return ShellRequest.ShellResult.error("命令为空");
    }
    if (!isAvailable()) {
      return ShellRequest.ShellResult.error(unavailableReason());
    }

    IRemoteProcess process = null;
    try {
      IBinder binder = Shizuku.getBinder();
      if (binder == null) {
        return ShellRequest.ShellResult.error("Shizuku 服务不可用（binder 为 null）。");
      }
      IShizukuService service = IShizukuService.Stub.asInterface(binder);
      if (service == null) {
        return ShellRequest.ShellResult.error("无法获取 Shizuku 服务代理。");
      }

      // newProcess(cmd[], env[], cwd)：以 sh -c 包裹，保留用户的引号与管道语义
      String[] command = {"/system/bin/sh", "-c", request.getCommand()};
      String[] env = null;
      String cwd = request.getCwd().isEmpty() ? null : request.getCwd();

      process = service.newProcess(command, env, cwd);
      if (process == null) {
        return ShellRequest.ShellResult.error("Shizuku 未能创建进程。");
      }

      return collectOutput(process, request.getTimeoutMs(), sink);

    } catch (Throwable e) {
      log.error("Shizuku 命令执行失败", e);
      return ShellRequest.ShellResult.error("Shizuku 执行异常: " + e.getMessage());
    } finally {
      if (process != null) {
        try {
          process.destroy();
        } catch (Throwable ignored) {
          // 进程可能已退出
        }
      }
    }
  }

  /**
   * 读取进程输出并等待退出。
   *
   * <p>先用 {@code waitForTimeout} 阻塞等待；超时则 destroy 并标记 timedOut。
   * 读取在等待之后进行——Shizuku 的 ParcelFileDescriptor 是阻塞流，
   * 若在进程结束前读取可能永久阻塞。
   */
  private ShellRequest.ShellResult collectOutput(
      IRemoteProcess process, long timeoutMs, ShellRequest.ShellOutputSink sink) {

    boolean timedOut = false;
    int exitCode = -1;

    try {
      boolean exited =
          process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString());
      if (!exited) {
        timedOut = true;
        try {
          process.destroy();
        } catch (Throwable e) {
          log.warn("超时后终止 Shizuku 进程失败", e);
        }
      } else {
        exitCode = process.exitValue();
      }
    } catch (Throwable e) {
      log.warn("等待 Shizuku 进程失败", e);
      return ShellRequest.ShellResult.error("等待进程失败: " + e.getMessage());
    }

    String stdout = readStream(process, true, sink);
    String stderr = readStream(process, false, sink);

    return new ShellRequest.ShellResult(exitCode, stdout, stderr, timedOut, false);
  }

  private String readStream(
      IRemoteProcess process, boolean isStdout, ShellRequest.ShellOutputSink sink) {
    ParcelFileDescriptor pfd = null;
    try {
      pfd = isStdout ? process.getInputStream() : process.getErrorStream();
      if (pfd == null) {
        return "";
      }
      try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
          out.write(buffer, 0, read);
          total += read;
          if (total >= MAX_OUTPUT_BYTES) {
            break;
          }
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        if (isStdout && sink != null && !text.isEmpty()) {
          sink.onOutput(text);
        }
        return text;
      }
    } catch (Throwable e) {
      log.warn("读取 Shizuku 输出流失败 (stdout={})", isStdout, e);
      return "";
    } finally {
      if (pfd != null) {
        try {
          pfd.close();
        } catch (Throwable ignored) {
          // 已关闭
        }
      }
    }
  }
}
