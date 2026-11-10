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

package com.tom.rv2ide.ssh

import com.tom.rv2ide.ai.tool.ShellBackend
import com.tom.rv2ide.ai.tool.ShellRequest
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

/**
 * 在远端服务器上执行 shell 命令的 [ShellBackend] 实现。
 *
 * <p><b>连接复用（本功能的核心需求）</b>：不自己持有连接，而是向 [SshSessionRegistry]
 * 按项目路径取。注册表第一次建立 SSH 会话，之后每次 {@link #execute} 只开一个新 channel
 * （毫秒级）。因此 AI 连着跑十条命令只需一次登录。
 *
 * <p><b>shell 状态不跨调用保留</b>：每次调用是独立的 exec，{@code cd} 与临时环境变量不会
 * 带到下一次。工作目录由 [ShellRequest.getCwd] 显式传入——这是刻意的：让 {@code cd} 保持
 * 需要 PTY 交互式 shell + 解析提示符，那套在远端用 sh/bash/zsh 混杂时极易误判，而
 * 「命令跑在哪个目录」是模型能明确表达的。
 *
 * <p><b>契约</b>：{@link ShellBackend} 要求「线程安全 + 不抛异常」。本类把一切异常转成
 * {@link ShellRequest.ShellResult#error}，并用 [lock] 串行化——sshj 的 session 不是为并发
 * 多命令设计的（同一条连接同时开几十个 channel 会占用服务端 MaxSessions 配额）。
 */
class SshShellBackend(private val registryProvider: () -> SshSessionRegistry?) : ShellBackend {

  /** 供 [isAvailable] / [unavailableReason] 判断「是否已配置连接」；不触发任何网络动作。 */
  private fun configured(): Boolean = SshComponents.profiles()?.active() != null

  private companion object {
    val log = LoggerFactory.getLogger(SshShellBackend::class.java)

    const val ID_VALUE = "ssh"

    /** 单次输出的读取上限，防止远端命令把超大文件直接打印进来。 */
    const val MAX_OUTPUT_BYTES = 512 * 1024

    /** 抽干残留输出的最长等待。见 [drainRemaining]。 */
    const val DRAIN_TIMEOUT_MS = 500L
  }

  /** 串行化同一后端实例上的执行。见类注释。 */
  private val lock = Any()

  override fun id(): String = ID_VALUE

  override fun displayName(): String = "远程服务器 (SSH)"

  /**
   * 是否可用：已配置至少一条连接配置。
   *
   * <p>刻意**不**去探测网络/连接状态：{@link #isAvailable} 会被提示词生成等廉价路径调用，
   * 在那里发起 TCP 连接会让每次生成提示都卡几秒。真正的连接失败由 [execute] 如实报告。
   */
  override fun isAvailable(): Boolean = registryProvider() != null && configured()

  override fun isPresent(): Boolean = isAvailable()

  override fun unavailableReason(): String {
    if (registryProvider() == null) return "SSH 组件未初始化。"
    if (!configured()) {
      return "未配置 SSH 连接。请在 AI 设置里填写主机、用户名与凭据，或改用其它后端。"
    }
    return ""
  }

  /**
   * 远端有完整命令行工具链，且**没有**本机 adb/root 权限的概念。
   *
   * <p>[hasAdbPrivileges] / [hasRootPrivileges] 保持默认 false：远端的 `pm install` 指的是
   * **服务器**上的包管理，与本机 Android 设备无关，把它标成「有 adb 权限」会误导模型。
   */
  override fun hasCliToolchain(): Boolean = true

  override fun capabilitySummary(): String =
      "在远端服务器上执行命令（完整的 Linux 工具链，如 git/docker/systemctl）。" +
          "文件是本机项目的副本关系需自行确认；adb/root 权限仅对被连的那台服务器有意义。"

  override fun execute(
      request: ShellRequest,
      sink: ShellRequest.ShellOutputSink?
  ): ShellRequest.ShellResult {
    if (request == null || request.command.trim().isEmpty()) {
      return ShellRequest.ShellResult.error("命令为空")
    }
    val registry =
        registryProvider() ?: return ShellRequest.ShellResult.error("SSH 组件未初始化。")

    // 串行化：sshj 的一条连接同时开太多 channel 会撞服务端 MaxSessions。
    synchronized(lock) {
      val connection =
          try {
            registry.getOrConnect(projectPathOf(request))
          } catch (e: Exception) {
            log.warn("获取 SSH 会话失败", e)
            return ShellRequest.ShellResult.error(
                "获取 SSH 会话失败：" + (e.message ?: e.javaClass.simpleName))
          }
      if (connection == null) {
        return ShellRequest.ShellResult.error(
            registry.lastError ?: "SSH 未连接。请检查配置与网络后重试。")
      }

      return try {
        runCommand(connection, request, sink)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        ShellRequest.ShellResult.error("命令被中断。")
      } catch (e: Exception) {
        // 带上异常类型与消息：真机排查时「未知错误」四个字完全没法定位问题
        // （实测踩过：真实原因是 sshj 的 Session.Command 在命令快速退出时抛
        // IllegalStateException，而文案把它吞成了「SSH 连接失败: 未知错误」）。
        // 用 android.util.Log 直接打栈：slf4j 的占位符规则会把 throwable 当普通参数吞掉，
        // 实测只能看到「NullPointerException: null」，完全看不出是哪一行。
        android.util.Log.e(
            "SshShellBackend", "SSH 命令执行失败：" + e.javaClass.simpleName + ": " + e.message, e)
        log.warn("SSH 命令执行失败：" + e.javaClass.simpleName + ": " + e.message)
        ShellRequest.ShellResult.error(
            friendlySshError(e) + " [" + e.javaClass.simpleName + ": " + (e.message ?: "") + "]")
      }
    }
  }

  /** 在已连接的会话上跑一条命令，处理 cwd / 超时 / 取消 / 输出截断。 */
  private fun runCommand(
      connection: RemoteSshConnection,
      request: ShellRequest,
      sink: ShellRequest.ShellOutputSink?
  ): ShellRequest.ShellResult {
    // cwd 显式前置：exec 的每条命令都是独立 shell，不显式 cd 就落在远端 $HOME。
    // **但不能硬 cd**：调用方传的 cwd 是**本机项目路径**（如
    // /data/data/.../ACSProjects/MyApp），那在远端服务器上通常不存在；
    // `cd` 失败会让整条命令以 exit-signal 中止，模型看到的是「SSH 连接失败: 未知错误」
    // ——一个完全指错方向的报错（真机实测踩过）。
    // 因此用「能 cd 就 cd，不能就退回远端工作区/$HOME」的容错写法。
    val cwd = resolveRemoteCwd(connection, request.cwd.trim())
    val fullCommand =
        if (cwd.isEmpty()) request.command
        else "cd ${shellQuote(cwd)} 2>/dev/null || cd ~; " + request.command

    // startExecSession 会在内部立刻给远端 stdin 送 EOF（所有调用方只读输出），
    // 因此不能再用它传 stdin；有 stdin 时必须走 startExecSessionWithStdin 并自己管 Session。
    val session = connection.startShellSession()
    try {
      val s = session
      // **`outputStream` 可能为 null**：sshj 在通道已关闭 / 命令极快退出时返回 null，
      // 直接调 .write()/.close() 就是 NullPointerException。这是实测的真凶——
      // 报错「SSH 连接失败: 未知错误」完全指不到这里。
      // 因此一律经 `?.` 访问；给远端送 EOF 失败不影响读输出（多数命令不依赖 stdin）。
      val command =
          s.exec(fullCommand).also { cmd ->
            val stdin = request.stdin
            if (stdin != null) {
              runCatching {
                cmd.outputStream?.write(stdin.toByteArray(StandardCharsets.UTF_8))
                cmd.outputStream?.flush()
              }
            }
            runCatching { cmd.outputStream?.close() }
          }

      val stdout = StringBuilder()
      val stderr = StringBuilder()
      val deadline = System.currentTimeMillis() + request.timeoutMs
      var timedOut = false
      var truncated = false

      // sshj 的 InputStream.read 会阻塞；用分片等待 + 取消检查，避免「停止按钮没反应」。
      val stdoutBuf = ByteArray(8 * 1024)
      val stderrBuf = ByteArray(8 * 1024)
      // **可能为 null**：sshj 在命令已退出/通道已关闭时两个流都可能返回 null，
// 直接调 available() 会 NPE（真机实测：「SSH 连接失败: 未知错误」的真凶）。
      val outIn = command.inputStream
      val errIn = command.errorStream

      while (true) {
        if (request.isCancellationRequested) {
          runCatching { s.close() }
          return ShellRequest.ShellResult(
              -1, stdout.toString(), stderr.toString(), true, truncated)
        }
        if (System.currentTimeMillis() > deadline) {
          timedOut = true
          runCatching { s.close() }
          break
        }
        // available() > 0 才读，否则睡一小会儿——直接 read 会阻塞到超时检查失效。
        val outReady = outIn != null && outIn.available() > 0
        if (outReady) {
          val n = outIn!!.read(stdoutBuf)
          if (n > 0) {
            if (stdout.length < MAX_OUTPUT_BYTES) {
              stdout.append(String(stdoutBuf, 0, n, StandardCharsets.UTF_8))
            } else {
              truncated = true
            }
            sink?.onOutput(String(stdoutBuf, 0, n, StandardCharsets.UTF_8))
          }
        }
        val errReady = errIn != null && errIn.available() > 0
        if (errReady) {
          val n = errIn!!.read(stderrBuf)
          if (n > 0) {
            if (stderr.length < MAX_OUTPUT_BYTES) {
              stderr.append(String(stderrBuf, 0, n, StandardCharsets.UTF_8))
            } else {
              truncated = true
            }
          }
        }
        val outIdle = outIn == null || outIn.available() <= 0
        val errIdle = errIn == null || errIn.available() <= 0
        if (outIdle && errIdle) {
          // 两个流都空：命令可能已结束，也可能还在跑。
          // **`exitStatus` 是 `Integer`（可空），进程未退出时返回 null**——不是 -1 也不是
          // Int.MIN_VALUE。这个假设错了整条链路都会误判（真机实测：把 null 当成
          // 「已退出」立刻收尾，随后构造 ShellResult 时 NPE）。
          if (command.exitStatus != null) {
            // 命令已结束，但可能还有残留输出没读完：再给一小段时间把缓冲区抽干。
            drainRemaining(outIn, stderr, stdout, sink, errIn)
            break
          }
          Thread.sleep(20)
        }
      }

      // exitStatus 是 Integer 且可能为 null（进程未退出）。ShellResult 要的是 int，
      // 因此这里显式兜底成 -1，而不是依赖它非空。
      val exitStatus = runCatching { command.exitStatus }.getOrNull() ?: -1
      return ShellRequest.ShellResult(
          exitStatus, stdout.toString(), stderr.toString(), timedOut, truncated)
    } finally {
      // Session 不实现 java.io.Closeable，不能用 use{}；而且 use{} 要求 lambda 返回
      // 一个值，配合内部的 return 会求值成 null（实测表现为构造 ShellResult 时 NPE）。
      runCatching { session.close() }
    }
  }

  /**
   * 取项目路径。
   *
   * <p>[ShellRequest] 没有「项目」字段，但 cwd 通常就是项目内的绝对路径；取不到时退回空串，
   * 由注册表以「默认项目」处理。这样在没有 cwd 的调用（如设置页测试连接）也能工作。
   */
  private fun projectPathOf(request: ShellRequest): String = request.cwd.trim()

  /**
   * 把调用方给的 cwd 映射成远端可用的目录。
   *
   * <p><b>为什么不能直接透传</b>：`shell_execute` 的 cwd 是**本机**项目路径。远端服务器上
   * 通常没有这个路径，`cd` 会失败。这里优先用配置里的 `remoteWorkspacePath` 拼出
   * 「同名项目在远端的目录」（`<remoteWorkspacePath>/<项目目录名>`，与 Aharou 的口径一致），
   * 取不到就返回空串让命令落在远端 `$HOME`。
   *
   * <p>拼出来的目录**可能也不存在**（用户没在远端建同名目录），所以命令里仍保留
   * `|| cd ~` 兜底——宁可退到 home 执行，也不要因一个 cd 失败让整条命令挂掉。
   */
  private fun resolveRemoteCwd(connection: RemoteSshConnection, requested: String): String {
    if (requested.isEmpty()) {
      return connection.remoteHome.orEmpty()
    }
    val config = connection.config
    val base = config?.remoteWorkspacePath?.trim().orEmpty()
    if (base.isEmpty()) {
      // 未配置远端工作区：cwd 若本来就是远端可用的绝对路径则照用，否则退回 $HOME。
      // 这里无法廉价判断，交给命令里的 `|| cd ~` 兜底，因此直接透传。
      return requested
    }
    return connection.remoteWorkspaceFor(requested) ?: requested
  }

  /**
   * 命令退出后把缓冲区里残留的输出抽干。
   *
   * <p>不能省：命令刚退出时 `available()` 可能仍是 0，而最后一段输出（例如 `echo` 的结尾、
   * 编译错误的最后一行）还压在 channel 缓冲区里。不等这一下，模型会拿到被截断的报错。
   * 用「连续几轮都读到 0 字节」作为结束判据，最多等 [DRAIN_TIMEOUT_MS]。
   */
  private fun drainRemaining(
      outIn: java.io.InputStream?,
      stderr: StringBuilder,
      stdout: StringBuilder,
      sink: ShellRequest.ShellOutputSink?,
      errIn: java.io.InputStream?
  ) {
    val buf = ByteArray(8 * 1024)
    val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
    var idleRounds = 0
    while (System.currentTimeMillis() < deadline && idleRounds < 3) {
      if (outIn == null && errIn == null) return
      var read = 0
      runCatching {
            if (outIn != null) while (outIn.available() > 0) {
              val n = outIn.read(buf)
              if (n <= 0) break
              read += n
              if (stdout.length < MAX_OUTPUT_BYTES) {
                stdout.append(String(buf, 0, n, StandardCharsets.UTF_8))
              }
              sink?.onOutput(String(buf, 0, n, StandardCharsets.UTF_8))
            }
          }
          .getOrNull()
      runCatching {
            if (errIn != null) {
              while (errIn.available() > 0) {
                val n = errIn.read(buf)
                if (n <= 0) break
                read += n
                if (stderr.length < MAX_OUTPUT_BYTES) {
                  stderr.append(String(buf, 0, n, StandardCharsets.UTF_8))
                }
              }
            }
          }
          .getOrNull()
      if (read == 0) {
        idleRounds++
        Thread.sleep(10)
      } else {
        idleRounds = 0
      }
    }
  }

  }
