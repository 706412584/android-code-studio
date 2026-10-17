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

package com.tom.rv2ide.services.builder

import com.tom.rv2ide.projects.builder.BuildOutcome
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.projects.builder.BuildOutputBuffer
import com.tom.rv2ide.tooling.api.ForwardingToolingApiClient
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.IToolingApiClient
import com.tom.rv2ide.tooling.api.IToolingApiServer
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.LogMessageParams
import com.tom.rv2ide.tooling.api.messages.TaskExecutionMessage
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import com.tom.rv2ide.tooling.api.messages.result.BuildResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata
import com.tom.rv2ide.tooling.events.ProgressEvent
import java.io.InputStream
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * 一个项目 ↔ 一个 tooling JVM 子进程。
 *
 * <p><b>为什么需要它</b>：`GradleBuildService` 是 Android 前台 Service，而 Service
 * 在同进程内恒为单例（多次 bindService 只触发一次 onCreate）。但 tooling server
 * 本身是普通 JVM 子进程，可以并存。把「runner + 构建状态 + 输出缓冲」抽到本类后，
 * 注册表可以按项目持有多个连接，而 Service 只作为其中一个连接的宿主。
 *
 * <p><b>硬边界</b>：本类**不得**触碰 `ProjectManagerImpl`、`ProjectInitializedEvent`
 * 或 LSP——那些是编辑器全局单例，后台项目不能污染它们。项目路径一律由构造参数显式
 * 传入。
 *
 * <p>线程模型与原来的 Service 一致：tooling 回调来自 server 线程；`isBuildInProgress`
 * 与 `buildOutput` 的写入都在 `performBuildTasks` 的异步链上。
 */
internal class ToolingConnection(
    val projectPath: String,
    private val sink: BuildNotificationSink?,
    private val clientFactory: () -> IToolingApiClient,
) : BuildService, IToolingApiClient, ToolingServerRunner.Observer, AutoCloseable {

  companion object {
    private val log = LoggerFactory.getLogger(ToolingConnection::class.java)
  }

  override val isBuildInProgress: Boolean
    get() = _isBuildInProgress

  @Volatile private var _isBuildInProgress = false

  override val buildOutput = BuildOutputBuffer()

  @Volatile private var _lastBuildOutcome: BuildOutcome? = null

  override val lastBuildOutcome: BuildOutcome?
    get() = _lastBuildOutcome

  @Volatile private var isToolingServerStarted = false

  private var server: IToolingApiServer? = null
  private var _toolingApiClient: ForwardingToolingApiClient? = null
  private var toolingServerRunner: ToolingServerRunner? = null
  private var outputReaderJob: Job? = null
  private var eventListener: GradleBuildService.EventListener? = null

  private val connectionScope =
      CoroutineScope(Dispatchers.IO + CoroutineName("ToolingConnection[$projectPath]"))

  /** 构建期间注入的 init script 路径；按连接隔离，避免并发连接互相覆盖。 */
  @Volatile private var loggerInitScriptPath: String? = null

  override fun recordRejectedBuildAttempt() {
    _lastBuildOutcome = BuildOutcome(false, System.currentTimeMillis())
  }

  override fun isToolingServerStarted(): Boolean = isToolingServerStarted && server != null

  // ---- ToolingServerRunner.Observer ----

  override fun onListenerStarted(
      server: IToolingApiServer,
      projectProxy: IProject,
      errorStream: InputStream,
  ) {
    startServerOutputReader(errorStream)
    this.server = server
    isToolingServerStarted = true
  }

  override fun onServerExited(exitCode: Int) {
    log.warn("[{}] Tooling API process terminated with exit code: {}", projectPath, exitCode)
    sink?.onServerExited()
  }

  override fun getClient(): IToolingApiClient {
    if (_toolingApiClient == null) {
      _toolingApiClient = ForwardingToolingApiClient(this)
    }
    return _toolingApiClient!!
  }

  // ---- 生命周期 ----

  fun startToolingServer(
      envs: Map<String, String>,
      listener: ToolingServerRunner.OnServerStartListener?,
  ) {
    if (toolingServerRunner?.isStarted != true) {
      toolingServerRunner = ToolingServerRunner(listener, this).also { it.startAsync(envs) }
      return
    }
    if (listener != null) {
      listener.onServerStarted(toolingServerRunner!!.pid!!)
    } else {
      toolingServerRunner!!.setListener(null)
    }
  }

  fun setEventListener(listener: GradleBuildService.EventListener?) {
    this.eventListener = listener
  }

  fun setServerListener(listener: ToolingServerRunner.OnServerStartListener?) {
    toolingServerRunner?.setListener(listener)
  }

  /** 关闭 tooling 进程与读线程。可重复调用。 */
  override fun close() {
    server?.also { s ->
      try {
        s.shutdown().get(1, TimeUnit.SECONDS)
      } catch (e: Throwable) {
        log.error("[{}] Failed to shutdown Tooling API server", projectPath, e)
      }
    }
    server = null
    toolingServerRunner?.release()
    toolingServerRunner = null
    _toolingApiClient?.client = null
    _toolingApiClient = null
    outputReaderJob?.cancel()
    outputReaderJob = null
    isToolingServerStarted = false
    _isBuildInProgress = false
  }

  // ---- IToolingApiClient ----

  override fun logMessage(params: LogMessageParams) {
    val logger = LoggerFactory.getLogger(params.tag)
    when (params.level) {
      'D' -> logger.debug(params.message)
      'W' -> logger.warn(params.message)
      'E' -> logger.error(params.message)
      'I' -> logger.info(params.message)
      else -> logger.trace(params.message)
    }
  }

  override fun logOutput(line: String) {
    eventListener?.onOutput(line)
    buildOutput.append(line)
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
    sink?.update(BuildNotificationState.IN_PROGRESS)
    eventListener?.prepareBuild(buildInfo)
  }

  override fun onBuildSuccessful(result: BuildResult) {
    sink?.update(BuildNotificationState.SUCCESS)
    eventListener?.onBuildSuccessful(result.tasks)
  }

  override fun onBuildFailed(result: BuildResult) {
    sink?.update(BuildNotificationState.FAILED)
    eventListener?.onBuildFailed(result.tasks)
  }

  override fun onProgressEvent(event: ProgressEvent) {
    eventListener?.onProgressEvent(event)
  }

  override fun getBuildArguments(): CompletableFuture<List<String>> {
    // P1 保持与原实现一致：init script 路径由本连接持有（而不是 System.setProperty），
    // 这样多个连接并发构建时不会互相覆盖。脚本的生成由宿主注入（见
    // GradleBuildService.createLoggerInitScript），连接层只消费路径。
    val args = ArrayList<String>()
    val initScript = loggerInitScriptPath
    if (initScript != null) {
      args.add("--init-script")
      args.add(initScript)
    }
    return CompletableFuture.completedFuture(args)
  }

  fun setLoggerInitScriptPath(path: String?) {
    loggerInitScriptPath = path
  }

  override fun metadata(): CompletableFuture<ToolingServerMetadata> {
    checkServerStarted()
    return server!!.metadata()
  }

  override fun initializeProject(
      params: InitializeProjectParams
  ): CompletableFuture<InitializeResult> {
    checkServerStarted()
    Objects.requireNonNull(params)
    return performBuildTasks(server!!.initialize(params))
  }

  override fun executeTasks(vararg tasks: String): CompletableFuture<TaskExecutionResult> {
    checkServerStarted()
    val message = TaskExecutionMessage(listOf(*tasks))
    val startedAt = System.currentTimeMillis()
    return performBuildTasks(server!!.executeTasks(message))
        .whenComplete { result, error ->
          val successful = error == null && result != null && result.isSuccessful
          _lastBuildOutcome = BuildOutcome(successful, startedAt)
        }
  }

  override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
    checkServerStarted()
    return server!!.cancelCurrentBuild()
  }

  /**
   * 检查 wrapper 是否齐备；缺失时由宿主决定是否安装。
   *
   * <p>P1 只在编辑器连接上使用：wrapper 的检查与安装涉及全局 `ProjectManagerImpl`，
   * 后台连接不应触碰（见类注释的硬边界）。因此这里只做**只读**检查，路径来自
   * 构造参数而不是全局单例。
   */
  override fun checkGradleWrapperAvailability():
      CompletableFuture<com.tom.rv2ide.tooling.api.messages.result.GradleWrapperCheckResult> {
    val root = java.io.File(projectPath)
    val complete =
        root.isDirectory &&
            java.io.File(root, "gradlew").exists() &&
            java.io.File(root, "gradle/wrapper/gradle-wrapper.jar").exists() &&
            java.io.File(root, "gradle/wrapper/gradle-wrapper.properties").exists()
    return CompletableFuture.completedFuture(
        com.tom.rv2ide.tooling.api.messages.result.GradleWrapperCheckResult(complete))
  }

  // ---- 内部 ----

  private fun <T> performBuildTasks(future: CompletableFuture<T>): CompletableFuture<T> {
    return CompletableFuture.runAsync(this::onPrepareBuildRequest)
        .handleAsync { _, _ ->
          try {
            future.get()
          } catch (e: Throwable) {
            throw CompletionException(e)
          }
        }
        .handle { result, _ ->
          _isBuildInProgress = false
          result
        }
  }

  private fun onPrepareBuildRequest() {
    checkServerStarted()
    if (_isBuildInProgress) {
      throw BuildInProgressException()
    }
    buildOutput.clear()
    _lastBuildOutcome = null
    _isBuildInProgress = true
  }

  private fun checkServerStarted() {
    if (!isToolingServerStarted()) {
      throw com.tom.rv2ide.services.ToolingServerNotStartedException()
    }
  }

  private fun startServerOutputReader(input: InputStream) {
    if (outputReaderJob?.isActive == true) {
      return
    }
    outputReaderJob =
        connectionScope.launch {
          val reader = input.bufferedReader()
          try {
            reader.forEachLine { line ->
              LoggerFactory.getLogger("ToolingApiErrorStream").error(line)
            }
          } catch (e: Throwable) {
            if (e !is CancellationException) {
              log.error("[{}] Failed to read tooling server output", projectPath, e)
            }
          }
        }
  }
}
