/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.tooling.impl

import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.IToolingApiClient
import com.tom.rv2ide.tooling.api.IToolingApiServer
import com.tom.rv2ide.tooling.api.messages.GradleDistributionParams
import com.tom.rv2ide.tooling.api.messages.GradleDistributionType
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.TaskExecutionMessage
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult.Reason.CANCELLATION_ERROR
import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import com.tom.rv2ide.tooling.api.messages.result.BuildResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.BUILD_CANCELLED
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.BUILD_FAILED
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.CONNECTION_CLOSED
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.CONNECTION_ERROR
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_DIRECTORY_INACCESSIBLE
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_DIRECTORY
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_FOUND
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_INITIALIZED
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.UNKNOWN
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_BUILD_ARGUMENT
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_CONFIGURATION
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_GRADLE_VERSION
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata
import com.tom.rv2ide.tooling.impl.internal.ProjectImpl
import com.tom.rv2ide.tooling.impl.net.SimpleHttpProxy
import com.tom.rv2ide.tooling.impl.sync.ModelBuilderException
import com.tom.rv2ide.tooling.impl.sync.RootModelBuilder
import com.tom.rv2ide.tooling.impl.sync.RootProjectModelBuilderParams
import com.tom.rv2ide.utils.StopWatch
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.BuildException
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.tooling.exceptions.UnsupportedBuildArgumentException
import org.gradle.tooling.exceptions.UnsupportedOperationConfigurationException
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.slf4j.LoggerFactory

/**
 * Implementation for the Gradle Tooling API server.
 *
 * @author Akash Yadav
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
internal class ToolingApiServerImpl(private val project: ProjectImpl) : IToolingApiServer {

  private var client: IToolingApiClient? = null
  private var connector: GradleConnector? = null
  private var connection: ProjectConnection? = null
  private var lastInitParams: InitializeProjectParams? = null
  private var _buildCancellationToken: CancellationTokenSource? = null
  private var httpProxy: SimpleHttpProxy? = null

  private val cancellationTokenAccessLock = ReentrantLock(/* fair= */ true)
  private var buildCancellationToken: CancellationTokenSource?
    get() = cancellationTokenAccessLock.withLock { _buildCancellationToken }
    set(value) = cancellationTokenAccessLock.withLock { _buildCancellationToken = value }

  /** Whether the project has been initialized or not. */
  var isInitialized: Boolean = false
    private set

  /** Whether a build or project synchronization is in progress. */
  private var isBuildInProgress: Boolean = false

  /** Whether the server has a live connection to Gradle. */
  val isConnected: Boolean
    get() = connector != null || connection != null

  companion object {

    private val log = LoggerFactory.getLogger(ToolingApiServerImpl::class.java)

    /**
     * Time duration for which the the Tooling API server waits after calling
     * [DefaultGradleConnector.close] and before exiting the server's process.
     *
     * This delay should be long enough to let the tooling API stop the daemon but short enough so
     * that the server's process is not kept alive for longer duration.
     */
    const val DELAY_BEFORE_EXIT_MS = 1000L
  }

  override fun metadata(): CompletableFuture<ToolingServerMetadata> {
    return CompletableFuture.supplyAsync {
      ToolingServerMetadata(ProcessHandle.current().pid().toInt())
    }
  }

  override fun initialize(params: InitializeProjectParams): CompletableFuture<InitializeResult> {
    return runBuild {
      try {
        log.debug("Received project initialization request with params: {}", params)

        Main.checkGradleWrapper()

        if (buildCancellationToken != null) {
          cancelCurrentBuild().get()
        }

        val projectDirectory = File(params.directory)
        val failureReason = validateProjectDirectory(projectDirectory)

        if (failureReason != null) {
          log.error("Cannot initialize project: {}", failureReason)
          return@runBuild InitializeResult(false, failureReason)
        }

        // Ensure Gradle sees UTF-8/locale early via project gradle.properties
        ensureProjectGradleProperties(projectDirectory)

        // 修掉历史版本写坏的空 buildToolsVersion（r19 及更早生成的项目里可能有）。
        // 放在连接之前：那行会让 AGP 在配置阶段就抛 `Invalid revision:`，
        // 连项目模型都读不出来，必须在交给 Gradle 之前清掉。
        healEmptyBuildToolsVersion(projectDirectory)

        val stopWatch = StopWatch("Connection to project")
        val isReinitializing = connector != null && connection != null && params == lastInitParams

        if (isReinitializing) {
          log.info("Project is being reinitialized")
          log.info("Reusing connector instance...")
        } else {
          // a new project is being initialized
          // or the project is being initialized with different parameters
          connector?.disconnect()

          connector = GradleConnector.newConnector().forProjectDirectory(projectDirectory)
          setupConnectorForGradleInstallation(this.connector!!, params.gradleDistribution)
          stopWatch.lap("Connector created")
        }

        lastInitParams = params

        val connector =
            checkNotNull(connector) {
              "Unable to create gradle connector for project directory: ${params.directory}"
            }

        notifyBeforeBuild(BuildInfo(emptyList()))

        if (isReinitializing) {
          log.info("Reusing project connection...")
        } else {
          connection = connector.connect()
        }

        val connection =
            checkNotNull(this.connection) {
              "Unable to create project connection for project directory: ${params.directory}"
            }

        stopWatch.lapFromLast("Project connection established")

        this.buildCancellationToken = GradleConnector.newCancellationTokenSource()

        // start local HTTP proxy for Gradle network (before exposing port)
        if (httpProxy == null) {
          httpProxy = SimpleHttpProxy(0).also { it.start() }
        }
        // Expose proxy port to child process via system property
        val proxyPort = httpProxy?.port ?: -1
        if (proxyPort > 0) {
          try {
            System.setProperty("ANDROIDIDE_PROXY_PORT", proxyPort.toString())
          } catch (_: Throwable) {}
        }

        val project =
            try {
              val modelBuilderParams =
                  RootProjectModelBuilderParams(connection, this.buildCancellationToken!!.token())
              val impl =
                  RootModelBuilder(params).build(modelBuilderParams) as? ProjectImpl?
                      ?: throw ModelBuilderException("Failed to build project model")
              impl
            } catch (err: Throwable) {
              throw err
            }

        stopWatch.lapFromLast("Project read successful")
        stopWatch.log()

        this.project.setFrom(project)
        this.isInitialized = true

        notifyBuildSuccess(emptyList())
        return@runBuild InitializeResult(true)
      } catch (err: Throwable) {
        log.error("Failed to initialize project", err)
        notifyBuildFailure(emptyList())
        return@runBuild InitializeResult(false, getTaskFailureType(err))
      }
    }
  }

  private fun ensureProjectGradleProperties(projectDir: File) {
    try {
      val propsFile = File(projectDir, "gradle.properties")
      val props = java.util.Properties()
      if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }

      val jvmArgsKey = "org.gradle.jvmargs"
      val enforced =
          "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Duser.language=en -Duser.country=US"
      val current = props.getProperty(jvmArgsKey)?.trim().orEmpty()
      if (!current.contains("file.encoding")) {
        props.setProperty(jvmArgsKey, if (current.isBlank()) enforced else "$current $enforced")
      }
      props.setProperty("systemProp.file.encoding", "UTF-8")
      props.setProperty("systemProp.sun.jnu.encoding", "UTF-8")
      props.setProperty("systemProp.user.language", "en")
      props.setProperty("systemProp.user.country", "US")
      props.setProperty("org.gradle.daemon", "true")
      props.setProperty("org.gradle.daemon.idletimeout", "10800000")  // 3 hours
      props.setProperty("org.gradle.parallel", "true")

      val memoryConfig = "-Xmx2048m -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
      val existingJvmArgs = props.getProperty(jvmArgsKey)?.trim().orEmpty()
      if (!existingJvmArgs.contains("-Xmx")) {
        props.setProperty(jvmArgsKey, memoryConfig)
      }
            
      propsFile.outputStream().use { out ->
        props.store(out, "AndroidCS: enforce UTF-8 & locale for Gradle daemon")
      }
    } catch (_: Throwable) {
      // best-effort; ignore
    }
  }

  /**
   * 清掉工程里 `buildToolsVersion ''`（空值）那一行。
   *
   * <p><b>为什么需要</b>：某个版本的模板会在 `app/build.gradle` 里写出
   * `buildToolsVersion ''`——空串不是合法的 build-tools 版本，AGP 直接抛
   * `Invalid revision:`，**整个项目初始化失败**（不是构建失败，是连项目模型都
   * 读不出来）。生成侧已修（空值不再落盘），但**已经生成出来的工程**不会自己好，
   * 每次打开都失败。这里在初始化前就地清掉，用户无感。
   *
   * <p><b>为什么敢自动改用户文件</b>：① 空串是 bug 产物，不可能是用户手写的
   * 有效值；② 删掉这行等于恢复「不指定 buildToolsVersion」的原语义（由 AGP
   * 自挑要求的最低版本），不会改变工程的既有行为；③ 只动这一行，其余内容一字不碰。
   *
   * <p>失败时静默（best-effort）：改不动就让 Gradle 去报真实错误，不在这里抛。
   */
  private fun healEmptyBuildToolsVersion(projectDir: File) {
    try {
      // 两个位置都试：模块级 build.gradle 可能在根目录（老布局）或 app/ 子目录。
      val candidates =
          listOf(
              File(projectDir, "app/build.gradle"),
              File(projectDir, "app/build.gradle.kts"),
              File(projectDir, "build.gradle"),
              File(projectDir, "build.gradle.kts"),
          )
      for (file in candidates) {
        if (!file.isFile) continue
        val original = file.readText()
        val healed = stripEmptyBuildToolsVersion(original)
        if (healed != original) {
          // 原子写：先写同目录临时文件再改名。直接 writeText 是先截断再写，
          // 中途失败（磁盘满/IO 错）会留下**截断的 build.gradle**——那比原来的
          // 空值 bug 更糟（整个工程语法都坏了）。改名在同分区是原子的。
          val tmp = File(file.parentFile, "${file.name}.heal-tmp")
          tmp.writeText(healed)
          if (!tmp.renameTo(file)) {
            tmp.delete()
            log.warn("Could not replace {} while healing buildToolsVersion", file.absolutePath)
          } else {
            log.info("Removed empty buildToolsVersion from {}", file.absolutePath)
          }
        }
      }
    } catch (e: Throwable) {
      log.warn("Failed to heal empty buildToolsVersion in {}", projectDir.absolutePath, e)
    }
  }

  private fun validateProjectDirectory(projectDirectory: File) =
      when {
        !projectDirectory.exists() -> PROJECT_NOT_FOUND
        !projectDirectory.isDirectory -> PROJECT_NOT_DIRECTORY
        !projectDirectory.canRead() -> PROJECT_DIRECTORY_INACCESSIBLE
        else -> null
      }

  override fun isServerInitialized(): CompletableFuture<Boolean> {
    return CompletableFuture.supplyAsync { isInitialized }
  }

  override fun getRootProject(): CompletableFuture<IProject> {
    return CompletableFuture.supplyAsync {
      assertProjectInitialized()
      return@supplyAsync this.project
    }
  }

  override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> {
    return runBuild {
      if (!isServerInitialized().get()) {
        log.error("Cannot execute tasks: {}", PROJECT_NOT_INITIALIZED)
        return@runBuild TaskExecutionResult(false, PROJECT_NOT_INITIALIZED)
      }

      val lastInitParams = this.lastInitParams
      if (lastInitParams != null) {
        val projectDirectory = File(lastInitParams.directory)
        val failureReason = validateProjectDirectory(projectDirectory)
        if (failureReason != null) {
          log.error("Cannot execute tasks: {}", failureReason)
          return@runBuild TaskExecutionResult(isSuccessful = false, failureReason)
        }
      }

      log.debug("Received request to run tasks: {}", message)

      Main.checkGradleWrapper()

      val connection =
          checkNotNull(this.connection) {
            "ProjectConnection has not been initialized. Cannot execute tasks."
          }

      val builder = connection.newBuild()

      // System.in and System.out are used for communication between this server and the
      // client.
      val out = LoggingOutputStream()
      builder.setStandardInput("NoOp".byteInputStream())
      builder.setStandardError(out)
      builder.setStandardOutput(out)
      builder.forTasks(*message.tasks.filter { it.isNotBlank() }.toTypedArray())
      Main.finalizeLauncher(builder)

      this.buildCancellationToken = GradleConnector.newCancellationTokenSource()
      builder.withCancellationToken(this.buildCancellationToken!!.token())

      notifyBeforeBuild(BuildInfo(message.tasks))

      try {
        builder.run()
        this.buildCancellationToken = null
        notifyBuildSuccess(message.tasks)
        return@runBuild TaskExecutionResult.SUCCESS
      } catch (error: Throwable) {
        notifyBuildFailure(message.tasks)
        return@runBuild TaskExecutionResult(false, getTaskFailureType(error))
      }
    }
  }

  private fun setupConnectorForGradleInstallation(
      connector: GradleConnector,
      params: GradleDistributionParams,
  ) {
    when (params.type) {
      GradleDistributionType.GRADLE_WRAPPER -> {
        log.info("Using Gradle wrapper for build...")
      }

      GradleDistributionType.GRADLE_INSTALLATION -> {
        val file = File(params.value)
        if (!file.exists() || !file.isDirectory) {
          log.error("Specified Gradle installation does not exist: {}", params)
          return
        }

        log.info("Using Gradle installation: {}", file.canonicalPath)
        connector.useInstallation(file)
      }

      GradleDistributionType.GRADLE_VERSION -> {
        log.info("Using Gradle version '{}'", params.value)
        connector.useGradleVersion(params.value)
      }
    }
  }

  private fun notifyBuildFailure(tasks: List<String>) {
    client?.onBuildFailed(BuildResult((tasks)))
  }

  private fun notifyBuildSuccess(tasks: List<String>) {
    client?.onBuildSuccessful(BuildResult(tasks))
  }

  private fun notifyBeforeBuild(buildInfo: BuildInfo) {
    client?.prepareBuild(buildInfo)
  }

  override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
    return CompletableFuture.supplyAsync {
      if (this.buildCancellationToken == null) {
        return@supplyAsync BuildCancellationRequestResult(
            false,
            BuildCancellationRequestResult.Reason.NO_RUNNING_BUILD,
        )
      }

      try {
        this.buildCancellationToken!!.cancel()
        this.buildCancellationToken = null
      } catch (e: Exception) {
        val failureReason = CANCELLATION_ERROR
        failureReason.message = "${failureReason.message}: ${e.message}"
        return@supplyAsync BuildCancellationRequestResult(false, failureReason)
      }

      return@supplyAsync BuildCancellationRequestResult(true, null)
    }
  }

  override fun shutdown(): CompletableFuture<Void> {
    return CompletableFuture.supplyAsync {
      log.info("Shutting down Tooling API Server...")

      connection?.close()
      connector?.disconnect()
      connection = null
      connector = null

      // Stop all daemons
      log.info("Stopping all Gradle Daemons...")
      DefaultGradleConnector.close()

      // stop proxy
      try {
        httpProxy?.stop()
      } catch (_: Throwable) {}
      httpProxy = null

      // update the initialization flag before cancelling future
      this.isInitialized = false

      // cancelling this future will finish the Tooling API server process
      // see com.tom.rv2ide.tooling.impl.Main.main(String[])
      Main.future?.cancel(true)

      this.client = null
      this.buildCancellationToken = null // connector.disconnect() cancels any running builds
      this.lastInitParams = null
      Main.future = null
      Main.client = null

      null
    }
  }

  private fun getTaskFailureType(error: Throwable): Failure =
      when (error) {
        is BuildException -> BUILD_FAILED
        is BuildCancelledException -> BUILD_CANCELLED
        is UnsupportedOperationConfigurationException -> UNSUPPORTED_CONFIGURATION
        is UnsupportedVersionException -> UNSUPPORTED_GRADLE_VERSION
        is UnsupportedBuildArgumentException -> UNSUPPORTED_BUILD_ARGUMENT
        is GradleConnectionException -> CONNECTION_ERROR
        is java.lang.IllegalStateException -> CONNECTION_CLOSED
        else -> UNKNOWN
      }

  private inline fun <T : Any?> supplyAsync(crossinline action: () -> T): CompletableFuture<T> =
      CompletableFuture.supplyAsync { action() }

  private inline fun <T : Any?> runBuild(crossinline action: () -> T): CompletableFuture<T> =
      supplyAsync {
        if (isBuildInProgress) {
          log.error("Cannot run build, build is already in prorgess!")
          throw IllegalStateException("Build is already in progress")
        }

        isBuildInProgress = true
        try {
          action()
        } finally {
          isBuildInProgress = false
        }
      }

  private fun assertProjectInitialized() {
    if (!isServerInitialized().get()) {
      throw CompletionException(IllegalStateException("Project is not initialized!"))
    }
  }

  fun connect(client: IToolingApiClient) {
    this.client = client
  }
}

/**
 * 从 build.gradle 文本里删掉空值的 `buildToolsVersion` 行（Groovy 或 KTS 写法）。
 *
 * <p>只匹配**空值**：`buildToolsVersion ''` / `buildToolsVersion ""` /
 * `buildToolsVersion = ""`（KTS）。有值的行原样保留——用户显式指定过的不该被改。
 *
 * <p>抽成顶层纯函数以便 JVM 单测（不碰文件系统）。行为细节：
 * - 连同该行**行尾换行**一起删，不留空行；
 * - 行内其它内容（注释、缩进）不匹配，避免误删。
 */
internal fun stripEmptyBuildToolsVersion(source: String): String {
  // 行首空白 → buildToolsVersion → 可选的 "=" (KTS) → 空引号 → 行尾只允许空白。
  // 行尾**不**容忍 `// 注释`：模板不会写那种形态，多一层容忍反而增加误伤面。
  val pattern =
      Regex("""^[ \t]*buildToolsVersion[ \t]*(=[ \t]*)?(['"]{2})[ \t]*$(?:\r?\n)?""", RegexOption.MULTILINE)
  return pattern.replace(source, "")
}
