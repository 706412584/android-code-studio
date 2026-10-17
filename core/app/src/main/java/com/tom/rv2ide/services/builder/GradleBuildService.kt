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
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.services.builder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.blankj.utilcode.util.ResourceUtils
import com.blankj.utilcode.util.ZipUtils
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.R.*
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.managers.ToolsManager
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import com.tom.rv2ide.projects.builder.BuildOutcome
import com.tom.rv2ide.projects.builder.BuildOutputBuffer
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.projects.internal.ProjectManagerImpl
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.services.builder.ToolingServerRunner.OnServerStartListener
import com.tom.rv2ide.tasks.runOnUiThread
import com.tom.rv2ide.tooling.api.Aapt2Config
import com.tom.rv2ide.tooling.api.IToolingApiClient
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.LogMessageParams
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import com.tom.rv2ide.tooling.api.messages.result.BuildResult
import com.tom.rv2ide.tooling.api.messages.result.GradleWrapperCheckResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata
import com.tom.rv2ide.tooling.events.ProgressEvent
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import org.slf4j.LoggerFactory

/**
 * A foreground service that handles interaction with the Gradle Tooling API.
 *
 * <p><b>职责边界（P1 起）</b>：本类是**宿主壳**——负责前台通知、Lookup 注册、以及
 * 把编辑器项目的路径交给 [ToolingConnection]。真正的 tooling 进程管理、构建状态与
 * 输出缓冲都在连接里，这样同一个连接类可以被注册表复用于后台项目（见
 * `multiproject-build-plan.md`）。
 *
 * <p>Service 在同进程内恒为单例，因此「每项目一个 Service」不可行；可行的是
 * 「每项目一个连接」，本类只承载编辑器那一个。
 *
 * @author Akash Yadav
 */
class GradleBuildService :
    Service(), BuildService, IToolingApiClient, ToolingServerRunner.Observer {

  private var mBinder: GradleServiceBinder? = null

  /**
   * 编辑器项目对应的连接。
   *
   * <p>惰性创建：`onCreate` 时项目可能尚未打开（`ProjectManagerImpl.projectDirPath`
   * 为空）。连接按首次使用时的路径创建；路径变化时重建（用户切换项目）。
   */
  private var connection: ToolingConnection? = null
  private var connectionProjectPath: String = ""

  private var notificationManager: NotificationManager? = null

  /** 前台通知的接收方；把连接的状态变化映射成通知文案。 */
  private val notificationSink =
      object : BuildNotificationSink {
        override fun update(state: BuildNotificationState) {
          val message =
              when (state) {
                BuildNotificationState.IDLE -> getString(R.string.build_status_idle)
                BuildNotificationState.IN_PROGRESS -> getString(R.string.build_status_in_progress)
                BuildNotificationState.SUCCESS -> getString(R.string.build_status_sucess)
                BuildNotificationState.FAILED -> getString(R.string.build_status_failed)
              }
          updateNotification(message, state == BuildNotificationState.IN_PROGRESS)
        }

        override fun onServerExited() {
          stopForeground(STOP_FOREGROUND_REMOVE)
        }
      }

  companion object {
    private val log = LoggerFactory.getLogger(GradleBuildService::class.java)
    private val NOTIFICATION_ID = R.string.app_name
  }

  // ---- BuildService 委托 ----

  override val isBuildInProgress: Boolean
    get() = connection?.isBuildInProgress == true

  override val buildOutput: BuildOutputBuffer
    get() = requireConnection().buildOutput

  override val lastBuildOutcome: BuildOutcome?
    get() = connection?.lastBuildOutcome

  override fun recordRejectedBuildAttempt() {
    connection?.recordRejectedBuildAttempt()
  }

  override fun isToolingServerStarted(): Boolean = connection?.isToolingServerStarted() == true

  // ---- IToolingApiClient 委托 ----

  override fun logMessage(params: LogMessageParams) {
    connection?.logMessage(params)
  }

  override fun logOutput(line: String) {
    connection?.logOutput(line)
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
    connection?.prepareBuild(buildInfo)
  }

  override fun onBuildSuccessful(result: BuildResult) {
    connection?.onBuildSuccessful(result)
  }

  override fun onBuildFailed(result: BuildResult) {
    connection?.onBuildFailed(result)
  }

  override fun onProgressEvent(event: ProgressEvent) {
    connection?.onProgressEvent(event)
  }

  override fun getBuildArguments(): CompletableFuture<List<String>> {
    // 原实现的两项参数（AAPT2 override、logsender 开关）保持不变；
    // init script 由连接持有路径，避免并发连接互相覆盖进程级属性。
    val extraArgs = ArrayList<String>()
    extraArgs.add("-Pandroid.aapt2FromMavenOverride=" + Environment.AAPT2.absolutePath)
    extraArgs.add("${com.tom.rv2ide.tooling.api.LogSenderConfig.PROPERTY_LOGSENDER_ENABLED}=" +
        "${DevOpsPreferences.logsenderEnabled}")

    if (DevOpsPreferences.logsenderEnabled) {
      connection?.setLoggerInitScriptPath(createLoggerInitScript().absolutePath)
    } else {
      connection?.setLoggerInitScriptPath(null)
    }
    connection?.getBuildArguments()?.get()?.let { extraArgs.addAll(it) }

    if (BuildPreferences.isStacktraceEnabled) extraArgs.add("--stacktrace")
    if (BuildPreferences.isInfoEnabled) extraArgs.add("--info")
    if (BuildPreferences.isDebugEnabled) extraArgs.add("--debug")
    if (BuildPreferences.isScanEnabled) extraArgs.add("--scan")
    if (BuildPreferences.isWarningModeAllEnabled) {
      extraArgs.add("--warning-mode")
      extraArgs.add("all")
    }
    if (BuildPreferences.isBuildCacheEnabled) extraArgs.add("--build-cache")
    if (BuildPreferences.isOfflineEnabled) extraArgs.add("--offline")
    return CompletableFuture.completedFuture(extraArgs)
  }

  override fun checkGradleWrapperAvailability(): CompletableFuture<GradleWrapperCheckResult> {
    return if (isGradleWrapperAvailable)
        CompletableFuture.completedFuture(GradleWrapperCheckResult(true))
    else installWrapper()
  }

  override fun metadata(): CompletableFuture<ToolingServerMetadata> = requireConnection().metadata()

  override fun initializeProject(
      params: InitializeProjectParams
  ): CompletableFuture<InitializeResult> = requireConnection().initializeProject(params)

  override fun executeTasks(vararg tasks: String): CompletableFuture<TaskExecutionResult> =
      requireConnection().executeTasks(*tasks)

  override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> =
      requireConnection().cancelCurrentBuild()

  // ---- ToolingServerRunner.Observer 委托 ----

  override fun onListenerStarted(
      server: com.tom.rv2ide.tooling.api.IToolingApiServer,
      projectProxy: com.tom.rv2ide.tooling.api.IProject,
      errorStream: java.io.InputStream,
  ) {
    val conn = requireConnection()
    conn.onListenerStarted(server, projectProxy, errorStream)
    Lookup.getDefault().update(BuildService.KEY_PROJECT_PROXY, projectProxy)
  }

  override fun onServerExited(exitCode: Int) {
    connection?.onServerExited(exitCode)
  }

  override fun getClient(): IToolingApiClient {
    // 必须返回**本 Service**（而不是连接的 client）：ToolingServerRunner 用它做
    // LSP 回调入口，回调会经过本类的委托再落到连接。
    return this
  }

  // ---- 生命周期 ----

  override fun onCreate() {
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    showNotification(getString(R.string.build_status_idle), false)
    Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, this)
  }

  override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
    // No point in restarting the service if it really gets killed.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    mBinder?.release()
    mBinder = null

    log.info("Service is being destroyed. Dismissing the shown notification...")
    notificationManager!!.cancel(NOTIFICATION_ID)

    val lookup = Lookup.getDefault()
    lookup.unregister(BuildService.KEY_BUILD_SERVICE)
    lookup.unregister(BuildService.KEY_PROJECT_PROXY)

    if (connectionProjectPath.isNotEmpty()) {
      BuildConnectionRegistryHolder.get()?.remove(connectionProjectPath)
    }
    connection?.close()
    connection = null
  }

  override fun onBind(intent: Intent): IBinder? {
    if (mBinder == null) {
      mBinder = GradleServiceBinder(this)
    }
    return mBinder
  }

  // ---- 连接管理 ----

  /**
   * 取当前编辑器项目的连接；项目路径变化时重建。
   *
   * <p>路径来自 `ProjectManagerImpl`（编辑器单例）——**只有这里**可以读它。
   * 后台连接由注册表创建并显式传入路径，不经过本方法。
   */
  private fun requireConnection(): ToolingConnection {
    val projectDir = currentProjectPath()
    val existing = connection
    if (existing != null && existing.projectPath == projectDir) {
      return existing
    }
    // 项目切换：旧连接必须关掉，否则会留下一个孤儿 tooling 进程。
    existing?.close()
    val fresh = ToolingConnection(projectDir, notificationSink) { this }
    // 连接新建时补上此前登记的监听器（见 setEventListener）。
    fresh.setEventListener(pendingEventListener)
    connection = fresh
    connectionProjectPath = projectDir
    // 登记进注册表：工具层按会话 cwd 查找连接时能命中编辑器项目。
    // 只有编辑器连接写注册表；后台连接（P3）由注册表自己创建并登记。
    if (projectDir.isNotEmpty()) {
      BuildConnectionRegistryHolder.get()
          ?.register(projectDir, fresh, BuildConnectionRegistry.Owner.EDITOR)
    }
    return fresh
  }

  private fun currentProjectPath(): String {
    return try {
      ProjectManagerImpl.getInstance().projectDirPath ?: ""
    } catch (e: Exception) {
      log.warn("Unable to resolve project directory", e)
      ""
    }
  }

  // ---- 通知 ----

  private fun showNotification(
      message: String,
      @Suppress("SameParameterValue") isProgress: Boolean,
  ) {
    log.info("Showing notification to user...")
    createNotificationChannels()
    startForeground(NOTIFICATION_ID, buildNotification(message, isProgress))
  }

  private fun createNotificationChannels() {
    val buildNotificationChannel =
        NotificationChannel(
            BaseApplication.NOTIFICATION_GRADLE_BUILD_SERVICE,
            getString(string.title_gradle_service_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
    NotificationManagerCompat.from(this).createNotificationChannel(buildNotificationChannel)
  }

  private fun buildNotification(message: String, isProgress: Boolean): Notification {
    val ticker = getString(R.string.title_gradle_service_notification_ticker)
    val title = getString(R.string.title_gradle_service_notification)
    val launch = packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
    val intent = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT)
    val builder =
        Notification.Builder(this, BaseApplication.NOTIFICATION_GRADLE_BUILD_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_notification)
            .setTicker(ticker)
            .setWhen(System.currentTimeMillis())
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(intent)

    if (isProgress) {
      builder.setProgress(100, 0, true)
    }
    return builder.build()
  }

  private fun updateNotification(message: String, isProgress: Boolean) {
    runOnUiThread { doUpdateNotification(message, isProgress) }
  }

  private fun doUpdateNotification(message: String, isProgress: Boolean) {
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
        NOTIFICATION_ID,
        buildNotification(message, isProgress),
    )
  }

  // ---- wrapper ----

  /** Creates a Gradle init script that injects the logger plugin into user projects. */
  private fun createLoggerInitScript(): File {
    val initScript = File(Environment.TMP_DIR, "ide-logger-init.gradle")
    initScript.writeText(
        """
          allprojects {
              afterEvaluate {
                  if (plugins.hasPlugin('com.android.application') ||
                      plugins.hasPlugin('com.android.library')) {

                      android {
                          compileOptions {
                              coreLibraryDesugaringEnabled = true
                          }
                      }

                      dependencies {
                          implementation files('${getLoggerRuntimeAar().absolutePath}')
                          coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'
                      }
                  }
              }
          }
      """
            .trimIndent()
    )
    return initScript
  }

  private fun getLoggerPluginDir(): File {
    val dir = File(Environment.HOME, "plugins/logger")
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir
  }

  private fun getLoggerRuntimeAar(): File {
    val aar = File(getLoggerPluginDir(), "logger-runtime.aar")
    if (!aar.exists()) {
      if (
          !ResourceUtils.copyFileFromAssets(
              ToolsManager.getCommonAsset("logger-runtime.aar"),
              aar.absolutePath,
          )
      ) {
        log.error("Failed to extract logger-runtime.aar from assets")
      }
    }
    return aar
  }

  private val isGradleWrapperAvailable: Boolean
    get() {
      val projectManager = ProjectManagerImpl.getInstance()
      val projectDir = projectManager.projectDirPath
      if (projectDir.isNullOrEmpty()) {
        return false
      }
      val projectRoot = projectManager.projectDir ?: return false
      if (!projectRoot.exists()) {
        return false
      }
      val gradlew = File(projectRoot, "gradlew")
      val gradleWrapperJar = File(projectRoot, "gradle/wrapper/gradle-wrapper.jar")
      val gradleWrapperProps = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
      return gradlew.exists() && gradleWrapperJar.exists() && gradleWrapperProps.exists()
    }

  private fun installWrapper(): CompletableFuture<GradleWrapperCheckResult> {
    eventListenerForWrapper()?.also { eventListener ->
      eventListener.onOutput("-------------------- NOTE --------------------")
      eventListener.onOutput(getString(R.string.msg_installing_gradlew))
      eventListener.onOutput("----------------------------------------------")
    }
    return CompletableFuture.supplyAsync { doInstallWrapper() }
  }

  private fun eventListenerForWrapper(): EventListener? = wrapperListener

  /** 供 installWrapper 输出提示；由 setEventListener 同步。 */
  @Volatile private var wrapperListener: EventListener? = null

  private fun doInstallWrapper(): GradleWrapperCheckResult {
    val extracted = File(Environment.TMP_DIR, "gradle-wrapper.zip")
    if (
        !ResourceUtils.copyFileFromAssets(
            ToolsManager.getCommonAsset("gradle-wrapper.zip"),
            extracted.absolutePath,
        )
    ) {
      log.error("Unable to extract gradle-plugin.zip from IDE resources.")
      return GradleWrapperCheckResult(false)
    }
    try {
      val projectDir = ProjectManagerImpl.getInstance().projectDir
      val files = ZipUtils.unzipFile(extracted, projectDir)
      if (files != null && files.isNotEmpty()) {
        return GradleWrapperCheckResult(true)
      }
    } catch (e: IOException) {
      log.error("An error occurred while extracting Gradle wrapper", e)
    }
    return GradleWrapperCheckResult(false)
  }

  // ---- 编辑器侧入口（供 ProjectHandlerActivity 使用） ----

  internal fun setServerListener(listener: OnServerStartListener?) {
    requireConnection().setServerListener(listener)
  }

  internal fun startToolingServer(listener: OnServerStartListener?) {
    val conn = requireConnection()
    val envs = TermuxShellEnvironment().getEnvironment(this, false)
    val aapt2 = Environment.AAPT2
    if (aapt2.isFile) {
      envs[Aapt2Config.ENV_AAPT2_OVERRIDE] = aapt2.absolutePath
    } else {
      log.warn(
          "AAPT2 not yet extracted at {}; skipping the override fallback. " +
              "The RPC path still supplies it when it succeeds.",
          aapt2,
      )
    }
    conn.startToolingServer(envs, listener)
  }

  fun setEventListener(eventListener: EventListener?): GradleBuildService {
    wrapperListener = eventListener
    // 连接可能尚未创建（首次构建前）。此时只记下来，在 requireConnection()
    // 新建连接时补上——否则第一次构建的进度不会流向构建面板。
    pendingEventListener = wrap(eventListener)
    connection?.setEventListener(pendingEventListener)
    return this
  }

  /** 尚未有连接时暂存的事件监听器（已包装）。 */
  @Volatile private var pendingEventListener: EventListener? = null

  private fun wrap(listener: EventListener?): EventListener? {
    return if (listener == null) {
      null
    } else
        object : EventListener {
          override fun prepareBuild(buildInfo: BuildInfo) {
            runOnUiThread { listener.prepareBuild(buildInfo) }
          }

          override fun onBuildSuccessful(tasks: List<String?>) {
            runOnUiThread { listener.onBuildSuccessful(tasks) }
          }

          override fun onProgressEvent(event: ProgressEvent) {
            runOnUiThread { listener.onProgressEvent(event) }
          }

          override fun onBuildFailed(tasks: List<String?>) {
            runOnUiThread { listener.onBuildFailed(tasks) }
          }

          override fun onOutput(line: String?) {
            runOnUiThread { listener.onOutput(line) }
          }
        }
  }

  /** Handles events received from a Gradle build. */
  interface EventListener {

    fun prepareBuild(buildInfo: BuildInfo)

    fun onBuildSuccessful(tasks: List<String?>)

    fun onProgressEvent(event: ProgressEvent)

    fun onBuildFailed(tasks: List<String?>)

    fun onOutput(line: String?)
  }
}
