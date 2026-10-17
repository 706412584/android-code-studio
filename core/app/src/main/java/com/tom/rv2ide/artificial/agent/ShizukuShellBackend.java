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
 *   <li><b>不依赖 Shizuku 的包名</b>（第三方魔改版可用）：可用性只看 binder 与授权
 *       （{@code Shizuku.pingBinder()} / {@code checkSelfPermission()}）；「是否安装」
 *       以「有应用声明 API_V23 权限」为准——官方包与任意魔改包名都识别。
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

  /** 等待进程时的分片时长：每片之后检查一次取消信号。 */
  private static final long CANCEL_POLL_MS = 250L;

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
    return "Shizuku（adb/root）";
  }

  @Override
  public boolean hasAdbPrivileges() {
    return true;
  }

  @Override
  public boolean hasCliToolchain() {
    // 命令以 shell(2000) 身份经 /system/bin/sh 执行，PATH 只有 Android 系统目录。
    // Termux 的 bin 在 app 私有目录（700），shell 身份既读不到、也无法执行其二进制
    // （实测：复制出来也会因缺少 Termux 的 libpcre2 等依赖而链接失败）。
    return false;
  }

  @Override
  public String capabilitySummary() {
    // 服务以 root 启动时 newProcess 继承 uid 0，所以权限可能高于 adb(2000)，
    // 能力描述必须把这个事实带上，否则模型会误判「这里做不了 root 才能做的事」。
    return "adb 级权限，Shizuku 以 root 启动时即 root（pm/am/logcat 可用）；"
        + "没有 git/node/python 等工具链。";
  }

  /**
   * Shizuku 是否已安装（无论是否授权；兼容第三方魔改包名）。
   *
   * <p><b>为什么不查包名</b>：魔改版 Shizuku 的包名各不相同（官方为
   * {@code moe.shizuku.privileged.api}），查固定包名会把「已安装并授权」误判为
   * 「未安装」——实测表现是设置里切不过去、提示「没有安装」。
   *
   * <p>兼容实现都必须声明 {@code moe.shizuku.manager.permission.API_V23} 权限：
   * 客户端的授权请求、服务端的权限授予用的都是这个写死在 Shizuku-API 里的名字
   * （binder 投递协议本身也不携带 manager 包名）。因此查「谁声明了它」即可定位
   * manager，与包名无关。
   */
  public static boolean isShizukuInstalled(Context context) {
    try {
      // 部分 OEM 的 PackageManager 在权限不存在时返回 null 而不是抛异常，两者都要挡。
      return context
              .getPackageManager()
              .getPermissionInfo("moe.shizuku.manager.permission.API_V23", 0)
          != null;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  @Override
  public boolean isAvailable() {
    try {
      // 只看 binder 与授权，不查包名：binder 存活本身就是「已安装且服务在运行」的
      // 证据（binder 只能由 Shizuku 服务端推给本应用），查固定包名会误伤魔改版
      // ——实测表现是「已安装并授权却提示没有安装」。
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
    try {
      if (Shizuku.pingBinder()) {
        return Shizuku.checkSelfPermission() == PERMISSION_GRANTED
            ? ""
            : "AndroidCodeStudio 尚未获得 Shizuku 授权。请在 Shizuku 应用中授予本应用权限。";
      }
    } catch (Throwable e) {
      return "Shizuku 状态检查失败: " + e.getMessage();
    }
    // 没有 binder：区分「没装」与「服务没跑」——两者给用户的下一步动作不同。
    if (!isShizukuInstalled(appContext)) {
      return "Shizuku 未安装。需要 adb 权限执行 pm install / am start 时，"
          + "请先安装 Shizuku（官方版或兼容版）并完成授权。";
    }
    return "Shizuku 服务未运行。请打开 Shizuku 应用并启动服务"
        + "（部分机型每次重启后需要重新启动）。";
  }

  /** 授权结果回调，在主线程被调用。 */
  public interface PermissionResultCallback {
    void onResult(boolean granted);
  }

  /**
   * 请求 Shizuku 授权。必须在主线程调用，且应用处于前台。
   *
   * <p>Shizuku 的授权结果由 {@code Shizuku.requestPermission(int)} 异步返回：本次注册的
   * 监听器在收到结果后**立即注销**，避免每次请求都在 Shizuku 里堆积一个常驻监听器
   * （那会导致一次授权触发 N 次回调，且持有 Activity/回调引用造成泄漏）。
   *
   * @return true 表示请求已发出；false 表示前置条件不满足（binder 不存在，即服务未运行）
   *     或请求过程异常，此时不会弹出授权框，调用方可直接根据
   *     {@link #unavailableReason()} 提示用户
   */
  public boolean requestPermission(PermissionResultCallback callback) {
    try {
      // 前置条件只有「binder 活着」——它同时蕴含「已安装」与「服务在运行」，
      // 且不依赖包名（见 isShizukuInstalled 的说明）。
      if (!Shizuku.pingBinder()) {
        log.warn("Shizuku 服务未运行，无法请求授权");
        return false;
      }

      // 用数组持有监听器引用，才能在回调里注销它自己（lambda 无法在赋值前自引用）。
      final Shizuku.OnRequestPermissionResultListener[] holder =
          new Shizuku.OnRequestPermissionResultListener[1];
      holder[0] =
          (requestCode, grantResult) -> {
            Shizuku.removeRequestPermissionResultListener(holder[0]);
            boolean granted = grantResult == PERMISSION_GRANTED;
            log.info("Shizuku 授权结果: {}", granted ? "已授权" : "被拒绝");
            if (callback != null) {
              callback.onResult(granted);
            }
          };

      Shizuku.addRequestPermissionResultListener(holder[0]);
      Shizuku.requestPermission(0);
      return true;
    } catch (Throwable e) {
      log.warn("请求 Shizuku 授权失败", e);
      return false;
    }
  }

  /** 兼容旧签名：不关心异步结果时使用。 */
  public boolean requestPermission() {
    return requestPermission(null);
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

      return collectOutput(process, request.getTimeoutMs(), sink, request);

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
      IRemoteProcess process, long timeoutMs, ShellRequest.ShellOutputSink sink,
      ShellRequest request) {

    boolean timedOut = false;
    int exitCode = -1;

    try {
      // 分片等待，而不是一次 waitForTimeout(timeout)：后者在等待期间无法响应取消，
      // 后台任务被 task_stop 时只能干等到超时。每片 250ms，取消延迟可忽略。
      long deadline = System.currentTimeMillis() + timeoutMs;
      boolean exited = false;
      while (true) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          break;
        }
        if (request != null && request.isCancellationRequested()) {
          timedOut = true;
          break;
        }
        long slice = Math.min(CANCEL_POLL_MS, remaining);
        if (process.waitForTimeout(slice, TimeUnit.MILLISECONDS.toString())) {
          exited = true;
          break;
        }
      }
      if (!exited) {
        timedOut = true;
        try {
          process.destroy();
        } catch (Throwable e) {
          log.warn("终止 Shizuku 进程失败", e);
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
