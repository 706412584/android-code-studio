/*
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

package com.tom.rv2ide.artificial.agent

import android.content.Context
import com.tom.rv2ide.ai.tool.BackgroundShellPort
import com.tom.rv2ide.ai.tool.ShellBackend
import com.tom.rv2ide.ai.tool.ShellBackendRegistry
import com.tom.rv2ide.ai.tool.ShellRequest
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * [BackgroundShellPort] 的 app 层实现：把一条命令丢到后台线程执行，输出写入文件。
 *
 * <p><b>为什么每个任务用一个新后端实例</b>：[TermuxShellBackend] 把 `boundService` /
 * `connection` 作为实例字段，每次 `execute` 都重新绑定——同一个实例并发执行会互相踩。
 * 后台任务与前台 `shell_execute` 可能同时跑，因此这里为每个任务新建一个后端实例，
 * 从根上避开这个竞态（代价是每任务一次 bindService，可接受）。
 *
 * <p><b>输出落盘</b>：命令输出写进 `filesDir/ai_bg_tasks/&lt;id&gt;.log`，而不是留在内存里。
 * 后台命令可能输出几十 MB（构建日志），留在内存会 OOM；落盘后 `task_output` 只读尾部片段，
 * 需要完整输出时模型可以用 `file_read` 按路径读。
 *
 * <p><b>已知限制</b>：现有的 [ShellBackend] 实现是「命令结束后一次性交付输出」，
 * 不是流式——因此构建进行中 `task_output block=false` 看不到中间输出，只有结束
 * （或超时/取消）后输出文件才有内容。本期的目标是「不阻塞循环 + 完成通知」，
 * 流式输出留待后端改造。
 *
 * <p><b>完成回调在线程池线程上调用</b>：调用方必须保证线程安全且不阻塞
 * （通常只是往一个队列里投一条通知）。
 */
class BackgroundShellRunner(
  private val appContext: Context,
  /** 任务结束时回调（后台线程）；参数为任务终态。 */
  private val onFinished: (BackgroundShellPort.TaskStatus) -> Unit = {}
) : BackgroundShellPort {

  private val log = LoggerFactory.getLogger(BackgroundShellRunner::class.java)

  private val counter = AtomicLong(0)

  /** 一次后台任务的可变状态。字段跨线程读写，因此全部 volatile。 */
  private class Task(
    val id: String,
    val command: String,
    val outputFile: File,
    val cancel: AtomicBoolean
  ) {
    val startedAt = System.currentTimeMillis()

    @Volatile var state: BackgroundShellPort.State = BackgroundShellPort.State.RUNNING

    @Volatile var exitCode = -1

    @Volatile var hasExitCode = false

    @Volatile var error = ""

    @Volatile var finishedAt = 0L

    /** 任务结束（无论成败）时放行；[await] 用它等待，避免忙等。 */
    val done = CountDownLatch(1)
  }

  private val tasks = ConcurrentHashMap<String, Task>()

  override fun start(
    command: String?,
    cwd: String?,
    backendId: String?,
    timeoutMs: Long
  ): BackgroundShellPort.TaskStatus {
    val cmd = command?.trim().orEmpty()
    if (cmd.isEmpty()) {
      return BackgroundShellPort.TaskStatus.startFailed(cmd, "命令为空")
    }

    // 每个任务一个独立注册表 + 后端实例（见类注释）。
    val registry = newRegistry()
    val resolution = registry.resolve(backendId)
    if (!resolution.isUsable) {
      return BackgroundShellPort.TaskStatus.startFailed(
        cmd, "没有可用的 shell 后端。" + resolution.fallbackReason)
    }
    val backend = resolution.backend

    val dir = File(appContext.filesDir, "ai_bg_tasks")
    if (!dir.exists() && !dir.mkdirs()) {
      return BackgroundShellPort.TaskStatus.startFailed(cmd, "无法创建后台任务输出目录")
    }
    val id = "bg_" + System.currentTimeMillis().toString(36) + "_" + counter.incrementAndGet()
    val outputFile = File(dir, "$id.log")

    val cancel = AtomicBoolean(false)
    val task = Task(id, cmd, outputFile, cancel)
    tasks[id] = task

    val effectiveTimeout = if (timeoutMs <= 0) ShellRequest.DEFAULT_TIMEOUT_MS else timeoutMs
    val request =
      ShellRequest(cmd, cwd.orEmpty(), effectiveTimeout, null) { cancel.get() }

    val thread =
      Thread(
        {
          var out: OutputStream? = null
          try {
            out = FileOutputStream(outputFile)
            val stream = out
            val sink =
              ShellRequest.ShellOutputSink { chunk ->
                if (chunk != null && chunk.isNotEmpty()) {
                  synchronized(stream) {
                    stream.write(chunk.toByteArray(Charsets.UTF_8))
                    stream.flush()
                  }
                }
              }
            val result = backend.execute(request, sink)
            task.exitCode = result.exitCode
            task.hasExitCode = result.exitCode >= 0
            when {
              cancel.get() -> task.state = BackgroundShellPort.State.STOPPED
              result.isTimedOut -> {
                task.state = BackgroundShellPort.State.FAILED
                task.error = "命令超时（${effectiveTimeout / 1000}s）"
              }
              result.exitCode == 0 -> task.state = BackgroundShellPort.State.DONE
              else -> {
                task.state = BackgroundShellPort.State.FAILED
                task.error = "退出码 ${result.exitCode}"
              }
            }
            // stderr 追加到输出文件尾部，让 task_output 一处就能看到全部诊断。
            if (result.stderr.isNotEmpty()) {
              synchronized(stream) {
                stream.write("\n--- stderr ---\n".toByteArray(Charsets.UTF_8))
                stream.write(result.stderr.toByteArray(Charsets.UTF_8))
                stream.flush()
              }
            }
          } catch (t: Throwable) {
            task.state = BackgroundShellPort.State.FAILED
            task.error = t.message ?: t.javaClass.simpleName
          } finally {
            try {
              out?.close()
            } catch (_: Throwable) {
              // 关闭失败不影响任务结论
            }
            task.finishedAt = System.currentTimeMillis()
            task.done.countDown()
            try {
              onFinished(statusOf(task))
            } catch (t: Throwable) {
              log.warn("后台任务完成回调失败: {}", id, t)
            }
          }
        },
        "bg-shell-$id"
      )
    thread.isDaemon = true
    thread.start()

    return statusOf(task)
  }

  override fun status(taskId: String?): BackgroundShellPort.TaskStatus {
    val task = taskId?.let { tasks[it] } ?: return unknown(taskId)
    return statusOf(task)
  }

  override fun await(taskId: String?, timeoutMs: Long): BackgroundShellPort.TaskStatus {
    val task = taskId?.let { tasks[it] } ?: return unknown(taskId)
    if (task.state == BackgroundShellPort.State.RUNNING) {
      try {
        task.done.await(if (timeoutMs <= 0) 0L else timeoutMs, TimeUnit.MILLISECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    return statusOf(task)
  }

  override fun stop(taskId: String?): Boolean {
    val task = taskId?.let { tasks[it] } ?: return false
    if (task.state != BackgroundShellPort.State.RUNNING) {
      return false
    }
    // 只置标志：后端的等待循环会看到并终止进程，随后正常走收尾路径。
    task.cancel.set(true)
    return true
  }

  override fun isAvailable(): Boolean = newRegistry().available().isNotEmpty()

  override fun unavailableReason(): String =
    if (isAvailable()) "" else "没有可用的 shell 后端"

  /** 构造一个全新的后端注册表（Termux + Shizuku）。 */
  private fun newRegistry(): ShellBackendRegistry {
    val registry = ShellBackendRegistry()
    try {
      registry.register(TermuxShellBackend(appContext))
    } catch (t: Throwable) {
      log.debug("注册 Termux 后端失败", t)
    }
    try {
      registry.register(ShizukuShellBackend(appContext))
    } catch (t: Throwable) {
      log.debug("注册 Shizuku 后端失败", t)
    }
    return registry
  }

  private fun statusOf(task: Task): BackgroundShellPort.TaskStatus =
    BackgroundShellPort.TaskStatus(
      task.id,
      task.command,
      task.state,
      task.exitCode,
      task.hasExitCode,
      task.outputFile.absolutePath,
      task.startedAt,
      task.finishedAt,
      task.error
    )

  private fun unknown(taskId: String?): BackgroundShellPort.TaskStatus =
    BackgroundShellPort.TaskStatus(
      taskId.orEmpty(),
      "",
      BackgroundShellPort.State.UNKNOWN,
      -1,
      false,
      "",
      0L,
      0L,
      ""
    )
}
