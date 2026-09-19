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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ShellCommandConstants;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.tom.rv2ide.ai.tool.ShellBackend;
import com.tom.rv2ide.ai.tool.ShellRequest;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 IDE 内置的 Termux 服务执行 shell 命令。
 *
 * <p><b>权限级别</b>：在 app 自身 uid 下执行，<b>没有 adb 权限</b>。
 * 因此 {@code pm install} / {@code am start} 这类命令会失败——需要这些能力时
 * 应改用 Shizuku 后端。{@link #hasAdbPrivileges()} 返回 false 让调用方可以判断。
 *
 * <p><b>实现要点</b>（都来自对 TermuxService 的实际阅读）：
 * <ul>
 *   <li>TermuxService 在 manifest 中未声明 {@code android:process}，即与 app 同进程，
 *       因此可以 bindService 后用 LocalBinder 直接调用 Java 方法，无需走 Intent。
 *   <li>结果通过 {@code resultConfig.resultDirectoryPath} 落盘为多个文件；
 *       {@code err} 文件最后写入，作为「执行完成」的信号。
 *   <li>结果目录必须落在 Termux 允许的父路径下。{@code filesDir} 会命中兜底分支
 *       （{@code TermuxFileUtils} 对不在白名单内的路径返回 files 目录），因此总是可写。
 *   <li>Termux 不提供超时机制，这里用轮询 + deadline 实现，超时后 kill 进程。
 * </ul>
 */
public final class TermuxShellBackend implements ShellBackend {

  private static final Logger log = LoggerFactory.getLogger(TermuxShellBackend.class);

  public static final String ID = "termux";

  /** 轮询结果文件的间隔。 */
  private static final long POLL_INTERVAL_MS = 50L;

  /** bindService 的等待上限。 */
  private static final long BIND_TIMEOUT_MS = 5_000L;

  /**
   * 超时到期、但进程已退出时，额外等待结果文件落盘的时间。
   *
   * <p>2 秒足够任何一次磁盘 flush；给这个宽限只是为了把「命令跑完了但结果还在写」
   * 与「命令真的卡住了」区分开——见 {@link #awaitResult}。
   */
  private static final long EXIT_GRACE_MS = 2_000L;

  /** 单个输出文件的读取上限，防止把巨大文件读进内存。 */
  private static final int MAX_READ_BYTES = 512 * 1024;

  private final Context appContext;

  public TermuxShellBackend(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String displayName() {
    return "内置终端 (Termux)";
  }

  @Override
  public boolean isAvailable() {
    // Termux 服务随 IDE 一起分发，无需额外安装；只需确认能绑定到它。
    return true;
  }

  @Override
  public String unavailableReason() {
    return "";
  }

  @Override
  public boolean hasAdbPrivileges() {
    return false;
  }

  @Override
  public ShellRequest.ShellResult execute(
      ShellRequest request, ShellRequest.ShellOutputSink sink) {
    if (request == null || request.getCommand().trim().isEmpty()) {
      return ShellRequest.ShellResult.error("命令为空");
    }

    TermuxService service = bindService();
    if (service == null) {
      return ShellRequest.ShellResult.error(
          "无法连接内置终端服务。请确认终端功能可用后重试。");
    }

    File resultDir = prepareResultDir();
    if (resultDir == null) {
      return ShellRequest.ShellResult.error("无法创建命令结果目录");
    }

    try {
      ExecutionCommand command =
          new ExecutionCommand(
              com.termux.shared.termux.shell.TermuxShellManager.getNextShellId(),
              "/system/bin/sh",
              new String[] {"-c", request.getCommand()},
              request.getStdin(),
              request.getCwd().isEmpty() ? null : request.getCwd(),
              ExecutionCommand.Runner.APP_SHELL.getRunnerName(),
              false);

      command.isPluginExecutionCommand = true;
      command.resultConfig.resultDirectoryPath = resultDir.getAbsolutePath();
      command.resultConfig.resultDirectoryAllowedParentPath =
          com.termux.shared.termux.file.TermuxFileUtils
              .getMatchedAllowedTermuxWorkingDirectoryParentPathForPath(resultDir.getAbsolutePath());
      command.resultConfig.resultFilesSuffix = "";

      AppShell shell = service.createTermuxTask(command);
      if (shell == null) {
        return ShellRequest.ShellResult.error("命令未能启动");
      }

      return awaitResult(resultDir, request.getTimeoutMs(), shell, sink);
    } catch (RuntimeException e) {
      log.error("Termux 命令执行失败", e);
      return ShellRequest.ShellResult.error("命令执行异常: " + e.getMessage());
    } finally {
      unbindService();
      deleteRecursively(resultDir);
    }
  }

  /** 轮询等待 {@code err} 文件出现，然后读取全部结果。 */
  private ShellRequest.ShellResult awaitResult(
      File resultDir, long timeoutMs, AppShell shell, ShellRequest.ShellOutputSink sink) {

    File errFile = new File(resultDir, ShellCommandConstants.RESULT_SENDER.RESULT_FILE_ERR_PREFIX);
    long deadline = System.currentTimeMillis() + timeoutMs;

    while (System.currentTimeMillis() < deadline) {
      if (errFile.exists() && errFile.length() > 0) {
        return readResult(resultDir, sink);
      }
      if (!isShellRunning(shell)) {
        // 进程已退出但 err 文件还没落盘，再等一小段时间给它写完
        if (errFile.exists()) {
          return readResult(resultDir, sink);
        }
        sleepQuietly(POLL_INTERVAL_MS * 4);
        return readResult(resultDir, sink);
      }
      sleepQuietly(POLL_INTERVAL_MS);
    }

    // 到期。**先确认命令是不是真的还在跑**。
    //
    // 这一步不能省。Termux 的结果文件是一个个写的，`err` 最后落盘，而 stdout /
    // exit_code 可能早已写好。极短的 timeoutMs（实测模型填过 10ms、30ms）会让
    // deadline 在写 `err` 之前就到期，于是命令**明明成功跑完**（exit_code=0、
    // stdout 完整）却被报成「命令超时被终止」——结果里 exit_code=0 与超时自相矛盾，
    // 模型无法据此判断发生了什么，只会重试同一条命令。
    //
    // 判据用「进程是否还在」而不是「结果文件是否齐全」：进程已退出说明命令确实
    // 结束了，剩下的只是落盘延迟，多等一小段拿真实结果；进程仍在跑才是真超时。
    if (!isShellRunning(shell)) {
      // 进程已退出：等 err 落盘。给足时间（2s）——这只是磁盘 flush，不是命令执行。
      long graceDeadline = System.currentTimeMillis() + EXIT_GRACE_MS;
      while (System.currentTimeMillis() < graceDeadline) {
        if (errFile.exists() && errFile.length() > 0) {
          return readResult(resultDir, sink);
        }
        sleepQuietly(POLL_INTERVAL_MS);
      }
      // err 始终没出现，但进程已退出、exit_code 文件可能已写好。用它作为
      // 「命令确实结束了」的证据，返回真实退出码而不是超时。
      if (hasExitCode(resultDir)) {
        return readResult(resultDir, sink);
      }
    }

    // 真超时：终止进程，避免残留占用后续调用
    try {
      shell.killIfExecuting(appContext, false);
    } catch (RuntimeException e) {
      log.warn("超时后终止进程失败", e);
    }
    ShellRequest.ShellResult partial = readResult(resultDir, sink);
    return new ShellRequest.ShellResult(
        partial.getExitCode(), partial.getStdout(), partial.getStderr(), true, false);
  }

  /** 结果目录里是否已有 exit_code 文件——「命令已结束」的证据之一。 */
  private static boolean hasExitCode(File dir) {
    File file =
        new File(dir, ShellCommandConstants.RESULT_SENDER.RESULT_FILE_EXIT_CODE_PREFIX);
    return file.exists() && file.length() > 0;
  }

  private static boolean isShellRunning(AppShell shell) {
    try {
      return shell.getProcess() != null && shell.getProcess().isAlive();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** 读取结果目录下的各输出文件。缺失的文件按空处理。 */
  private ShellRequest.ShellResult readResult(File dir, ShellRequest.ShellOutputSink sink) {
    String stdout = readFile(new File(dir, ShellCommandConstants.RESULT_SENDER.RESULT_FILE_STDOUT_PREFIX));
    String stderr = readFile(new File(dir, ShellCommandConstants.RESULT_SENDER.RESULT_FILE_STDERR_PREFIX));
    String errmsg = readFile(new File(dir, ShellCommandConstants.RESULT_SENDER.RESULT_FILE_ERRMSG_PREFIX));
    String exitCodeText =
        readFile(new File(dir, ShellCommandConstants.RESULT_SENDER.RESULT_FILE_EXIT_CODE_PREFIX));

    int exitCode = -1;
    if (!exitCodeText.trim().isEmpty()) {
      try {
        exitCode = Integer.parseInt(exitCodeText.trim());
      } catch (NumberFormatException e) {
        exitCode = -1;
      }
    }
    if (!errmsg.isEmpty()) {
      stderr = stderr.isEmpty() ? errmsg : (stderr + "\n" + errmsg);
    }

    if (sink != null && !stdout.isEmpty()) {
      sink.onOutput(stdout);
    }
    return new ShellRequest.ShellResult(exitCode, stdout, stderr, false, false);
  }

  private static String readFile(File file) {
    if (file == null || !file.exists() || file.length() == 0) {
      return "";
    }
    try {
      if (file.length() > MAX_READ_BYTES) {
        byte[] buffer = new byte[MAX_READ_BYTES];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
          int read = in.read(buffer);
          return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
      }
      return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.warn("读取结果文件失败: {}", file, e);
      return "";
    }
  }

  private File prepareResultDir() {
    try {
      File dir = new File(appContext.getFilesDir(), "ai_shell_results/" + System.nanoTime());
      if (!dir.exists() && !dir.mkdirs()) {
        return null;
      }
      return dir;
    } catch (RuntimeException e) {
      log.error("创建结果目录失败", e);
      return null;
    }
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void deleteRecursively(File file) {
    if (file == null || !file.exists()) {
      return;
    }
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    // 结果文件可能没有删除权限，失败不影响主流程
    //noinspection ResultOfMethodCallIgnored
    file.delete();
  }

  // --- 服务绑定 ---

  private TermuxService boundService;
  private ServiceConnection connection;
  private final CountDownLatch bindLatch = new CountDownLatch(1);

  private TermuxService bindService() {
    // 每次执行都重新绑定：绑定状态与命令生命周期一致，避免跨调用的状态泄漏。
    boundService = null;
    final CountDownLatch latch = new CountDownLatch(1);

    connection =
        new ServiceConnection() {
          @Override
          public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder instanceof TermuxService.LocalBinder) {
              boundService = ((TermuxService.LocalBinder) binder).service;
            }
            latch.countDown();
          }

          @Override
          public void onServiceDisconnected(ComponentName name) {
            boundService = null;
          }
        };

    try {
      Intent intent = new Intent(appContext, TermuxService.class);
      if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
        return null;
      }
      if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return null;
      }
      return boundService;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (RuntimeException e) {
      log.error("绑定终端服务失败", e);
      return null;
    }
  }

  private void unbindService() {
    if (connection != null) {
      try {
        appContext.unbindService(connection);
      } catch (RuntimeException e) {
        log.warn("解绑终端服务失败", e);
      }
      connection = null;
    }
    boundService = null;
  }
}
