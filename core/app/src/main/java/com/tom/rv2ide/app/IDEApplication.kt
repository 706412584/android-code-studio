/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.tom.rv2ide.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.blankj.utilcode.util.ThrowableUtils.getFullStackTrace
import com.google.android.material.color.DynamicColors
import com.termux.app.TermuxApplication
import com.termux.shared.reflection.ReflectionUtils
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.activities.CrashHandlerActivity
import com.tom.rv2ide.activities.editor.IDELogcatReader
import com.tom.rv2ide.buildinfo.BuildInfo
import com.tom.rv2ide.editor.schemes.IDEColorSchemeProvider
import com.tom.rv2ide.eventbus.events.preferences.PreferenceChangeEvent
import com.tom.rv2ide.events.AppEventsIndex
import com.tom.rv2ide.events.EditorEventsIndex
import com.tom.rv2ide.events.LspApiEventsIndex
import com.tom.rv2ide.events.LspJavaEventsIndex
import com.tom.rv2ide.managers.ToolsManager
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.preferences.internal.StatPreferences
import com.tom.rv2ide.resources.localization.LocaleProvider
import com.tom.rv2ide.stats.AndroidIDEStats
import com.tom.rv2ide.stats.StatUploadWorker
import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import com.tom.rv2ide.treesitter.TreeSitter
import com.tom.rv2ide.ui.themes.IDETheme
import com.tom.rv2ide.ui.themes.IThemeManager
import com.tom.rv2ide.utils.ChartMemoryCleanupTask
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.MemoryManager
import com.tom.rv2ide.utils.RecyclableObjectPool
import com.tom.rv2ide.utils.VMUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.MemoryProfiler
import io.github.mohammedbaqernull.seasonal.SeasonalEffects
import io.github.miyazkaori.silentinstaller.SilentInstaller
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.File
import java.io.FileOutputStream
import java.lang.Thread.UncaughtExceptionHandler
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

class IDEApplication : TermuxApplication() {

  private var uncaughtExceptionHandler: UncaughtExceptionHandler? = null
  private var ideLogcatReader: IDELogcatReader? = null
  private var memoryManager: MemoryManager? = null
  private var chartCleanupTask: ChartMemoryCleanupTask? = null
  private var memoryProfiler: MemoryProfiler? = null

  init {
    RecyclableObjectPool.DEBUG = BuildConfig.DEBUG
  }

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)

    // 热更新：必须在 attachBaseContext 里加载已应用的补丁——DEX 要在任何业务类被加载前
    // 注入到 ClassLoader，资源要在 Activity 创建前挂到 AssetManager。放到 onCreate 就晚了
    // （此时主线程已开始加载应用类）。
    //
    // 未应用过补丁时内部会立即返回，不产生任何副作用；异常全部被 SDK 吞进日志，
    // 不会因补丁损坏而拦住应用启动。
    try {
      com.orange.update.HotUpdateHelper.init(this)
      com.orange.update.HotUpdateHelper.getInstance().loadPatchIfNeeded()
    } catch (e: Throwable) {
      log.error("Failed to load hot-update patch", e)
    }

    SilentInstaller.init(this)
    // Load native libraries after base context is attached
    TreeSitter.loadLibrary()
    if (!VMUtils.isJvm()) {
      try {} catch (e: Throwable) {
        log.error("Failed to load TreeSitter library", e)
      }
    }
  }

  @OptIn(DelicateCoroutinesApi::class)
  override fun onCreate() {
    instance = this
    uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, th -> handleCrash(thread, th) }

    super.onCreate()

    // agent 的协议/工具层是纯 Java 模块，错误默认只写 java.util.logging（Android 上不落 logcat）。
    // 接到应用日志设施后，模型请求失败、工具执行失败才能在 IDE 日志里看到。
    com.tom.rv2ide.ai.tool.api.ErrorLog.setSink { type, summary, throwable, details ->
      log.error("ai[$type] $summary\n$details", throwable)
    }

    // 内存采样：每 60s 往日志缓冲写一行内存画像（超阈值升级为 WARN）。
    // 「有时候卡顿」类问题的排查入口——`ide_log_read` 一查就能看到内存轨迹
    // （例：连续几条 java 420→480→510MB 即 OOM 前兆）。旁路诊断，零业务耦合。
    //
    // 采样线程同时驱动 MemoryPressureGuard：堆 ≥85% 时释放下面注册的纯缓存，
    // ≥92% 时再释放并升级告警。这样「涨到 490MB」不再只能等 OOM。
    registerMemoryReleasers()
    com.tom.rv2ide.artificial.agent.tool.MemorySampler.start(this)

    if (GeneralPreferences.snowfallOverlay && isSnowfallSeasonActive()) {
      SeasonalEffects.init(this)
      SeasonalEffects.enableChristmas()
      SeasonalEffects.setSnowflakeCount(20)
    }

    if (BuildConfig.DEBUG) {
      StrictMode.setVmPolicy(
          StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy()).penaltyLog().detectAll().build()
      )
      if (DevOpsPreferences.dumpLogs) {
        startLogcatReader()
      }
      // initializeMemoryProfiler()
    }

    EventBus.builder()
        .addIndex(AppEventsIndex())
        .addIndex(EditorEventsIndex())
        .addIndex(LspApiEventsIndex())
        .addIndex(LspJavaEventsIndex())
        .installDefaultEventBus(true)

    EventBus.getDefault().register(this)

    AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)

    if (IThemeManager.getInstance().getCurrentTheme() == IDETheme.MATERIAL_YOU) {
      DynamicColors.applyToActivitiesIfAvailable(this)
    }

    EditorColorScheme.setDefault(SchemeAndroidIDE.newInstance(null))

    ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
    GlobalScope.launch { IDEColorSchemeProvider.init() }

    // Initialize memory management
    // initializeMemoryManagement()
    extractLoggerRuntime()
    extractJetbrainsMono()

    // DISABLED: Plugin system completely disabled to prevent Tooling API issues
    // initializePluginSystem()
  }

  /**
   * Check if the snowfall season is currently active.
   * Snowfall is active until January 5, 2026.
   */
  private fun isSnowfallSeasonActive(): Boolean {
    val currentDate = java.time.LocalDate.now()
    val endDate = java.time.LocalDate.of(2026, 1, 5)
    return currentDate.isBefore(endDate) || currentDate.isEqual(endDate)
  }
  
  fun showChangelog() {
    val intent = Intent(Intent.ACTION_VIEW)
    var version = BuildInfo.VERSION_NAME_SIMPLE
    if (!version.startsWith('v')) {
      version = "v${version}"
    }
    intent.data = Uri.parse("${BuildInfo.REPO_URL}/releases/tag/${version}")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      startActivity(intent)
    } catch (th: Throwable) {
      log.error("Unable to start activity to show changelog", th)
      flashError("Unable to start activity")
    }
  }

  fun reportStatsIfNecessary() {

    if (!StatPreferences.statOptIn) {
      log.info("Stat collection is disabled.")
      return
    }

    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request =
        PeriodicWorkRequestBuilder<StatUploadWorker>(Duration.ofHours(24))
            .setInputData(AndroidIDEStats.statData.toInputData())
            .setConstraints(constraints)
            .addTag(StatUploadWorker.WORKER_WORK_NAME)
            .build()

    val workManager = WorkManager.getInstance(this)

    log.info("reportStatsIfNecessary: Enqueuing StatUploadWorker...")
    val operation =
        workManager.enqueueUniquePeriodicWork(
            StatUploadWorker.WORKER_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )

    operation.state.observeForever(
        object : Observer<Operation.State> {
          override fun onChanged(value: Operation.State) {
            operation.state.removeObserver(this)
            log.debug("reportStatsIfNecessary: WorkManager enqueue result: {}", value)
          }
        }
    )
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun onPrefChanged(event: PreferenceChangeEvent) {
    val enabled = event.value as? Boolean?
    if (
        event.key == GeneralPreferences.UI_MODE &&
            GeneralPreferences.uiMode != AppCompatDelegate.getDefaultNightMode()
    ) {
      AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)
    } else if (event.key == GeneralPreferences.SELECTED_LOCALE) {

      // Use empty locale list if the locale has been reset to 'System Default'
      val selectedLocale = GeneralPreferences.selectedLocale
      val localeListCompat =
          selectedLocale?.let { LocaleListCompat.create(LocaleProvider.getLocale(selectedLocale)) }
              ?: LocaleListCompat.getEmptyLocaleList()

      AppCompatDelegate.setApplicationLocales(localeListCompat)
    }
  }

  private fun extractJetbrainsMono() {
    try {
      val fontsDir = File(Environment.HOME, ".androidide/ui")

      if (!fontsDir.exists()) {
        fontsDir.mkdirs()
      }
      val targetFont = File(fontsDir, "jetbrains-mono.ttf")

      assets.open("fonts/jetbrains-mono.ttf").use { input ->
        FileOutputStream(targetFont).use { output -> input.copyTo(output) }
      }
    } catch (e: Exception) {
      log.error("Failed to extract jetbrains-mono.ttf font", e)
    }
  }
  
  private fun extractLoggerRuntime() {
    try {
      val pluginsDir = File(Environment.HOME, "plugins/logger")

      if (!pluginsDir.exists()) {
        pluginsDir.mkdirs()
        log.info("Created directory: ${pluginsDir.absolutePath}")
      }

      val targetFile = File(pluginsDir, "logger-runtime.aar")

      // 走 getCommonAsset，与 GradleBuildService.getLoggerRuntimeAar() 保持同一路径。
      // 这两处原先一个读 `assets/logger-runtime.aar`、一个读
      // `assets/data/common/logger-runtime.aar`，而资产只被放进其中一个位置，
      // 导致必有一方取不到文件。现在统一由 AndroidIDEAssetsPlugin 生成到 data/common。
      assets.open(ToolsManager.getCommonAsset("logger-runtime.aar")).use { input ->
        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
      }

      log.info("Successfully extracted logger-runtime.aar to: ${targetFile.absolutePath}")
    } catch (e: Exception) {
      log.error("Failed to extract logger-runtime.aar", e)
    }
  }

  private fun handleCrash(thread: Thread, th: Throwable) {
    // writeException(th)

    // OOM 兜底：先尽量释放可回收缓存，再决定是否真的崩。
    //
    // 走到这里说明某个线程没有接住 OutOfMemoryError（agent 循环与图片解码路径已各自
    // 有 catch，这里覆盖其余路径，例如 Compose 重组、Markdown 渲染、编辑器）。
    // 此时堆已满，但「释放缓存 + 提示用户」仍可能让进程活下来——用户至少能保存
    // 正在编辑的文件，而不是直接丢失未保存内容。
    //
    // 不能保证一定恢复：若释放后仍无法分配，继续走下面的崩溃上报流程。
    if (th is OutOfMemoryError) {
      val released =
          try {
            com.tom.rv2ide.artificial.agent.MemoryPressureGuard.releaseNow()
          } catch (ignored: Throwable) {
            0
          }
      Log.e("IDEApplication", "OutOfMemoryError: 已释放 $released 个缓存，尝试恢复", th)

      // 给 GC 一次机会，然后看是否真的有可分配空间。用一个小数组探测而不是
      // 直接继续——探测失败就老老实实走崩溃上报。
      System.gc()
      val headroom = Runtime.getRuntime().maxMemory() -
          (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
      if (headroom > OOM_RECOVERY_MIN_HEADROOM_BYTES) {
        try {
          val intent =
              Intent().apply {
                action = CrashHandlerActivity.REPORT_ACTION
                putExtra(CrashHandlerActivity.TRACE_KEY, getFullStackTrace(th))
                putExtra(
                    CrashHandlerActivity.OOM_RECOVERED_KEY,
                    "内存不足，已释放缓存并恢复。若界面异常，请重启应用；" +
                        "建议压缩会话上下文或新建会话。",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
          startActivity(intent)
          // **不**调用原 uncaughtExceptionHandler，也不 exitProcess：
          // 让它回到原线程继续（该线程上的当前操作已失败，但进程还在）。
          return
        } catch (error: Throwable) {
          Log.e("IDEApplication", "OOM 恢复提示失败，转为常规崩溃上报", error)
        }
      }
    }

    try {

      val intent = Intent()
      intent.action = CrashHandlerActivity.REPORT_ACTION
      intent.putExtra(CrashHandlerActivity.TRACE_KEY, getFullStackTrace(th))
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      startActivity(intent)
      if (uncaughtExceptionHandler != null) {
        uncaughtExceptionHandler!!.uncaughtException(thread, th)
      }

      exitProcess(1)
    } catch (error: Throwable) {
      Log.e("IDEApplication", "Unable to show crash handler activity", error)
    }
  }

  private fun cancelStatUploadWorker() {
    log.info("Opted-out of stat collection. Cancelling StatUploadWorker if enqueued...")
    val operation =
        WorkManager.getInstance(this).cancelUniqueWork(StatUploadWorker.WORKER_WORK_NAME)
    operation.state.observeForever(
        object : Observer<Operation.State> {
          override fun onChanged(value: Operation.State) {
            operation.state.removeObserver(this)
            log.info("StatUploadWorker: Cancellation result state: {}", value)
          }
        }
    )
  }

  private fun startLogcatReader() {
    if (ideLogcatReader != null) {
      // already started
      return
    }

    log.info("Starting logcat reader...")
    ideLogcatReader = IDELogcatReader().also { it.start() }
  }

  private fun stopLogcatReader() {
    log.info("Stopping logcat reader...")
    ideLogcatReader?.stop()
    ideLogcatReader = null
  }

  /** Initialize memory management system. RE-ENABLED with fixes for Tooling API compatibility. */
  private fun initializeMemoryManagement() {
    try {
      memoryManager = MemoryManager.getInstance(this)
      chartCleanupTask = ChartMemoryCleanupTask()

      // Register chart cleanup task
      memoryManager?.registerCleanupTask(chartCleanupTask!!)

      // Start memory monitoring with reduced frequency to avoid Tooling API conflicts
      memoryManager?.startMonitoring()

      Log.i("IDEApplication", "Memory management system initialized with Tooling API compatibility")
    } catch (e: Exception) {
      Log.e("IDEApplication", "Failed to initialize memory management", e)
    }
  }

  /** Get the chart cleanup task for registering charts. */
  fun getChartCleanupTask(): ChartMemoryCleanupTask? {
    return chartCleanupTask
  }

  /** Get the memory manager instance. */
  fun getMemoryManager(): MemoryManager? {
    return memoryManager
  }

  private fun initializeMemoryProfiler() {
    try {
      memoryProfiler = MemoryProfiler.getInstance(this)
      memoryProfiler?.startMonitoring()
      Log.i("IDEApplication", "Memory profiler initialized and started")
    } catch (e: Exception) {
      Log.e("IDEApplication", "Failed to initialize memory profiler", e)
    }
  }

  fun getMemoryProfiler(): MemoryProfiler? {
    return memoryProfiler
  }

  /**
   * 注册「可随时丢弃」的缓存释放器。
   *
   * <p>只注册**纯缓存**：丢掉后下一次渲染重算，用户看不到任何差异。不要注册会话历史、
   * 打开的文件、运行中的任务——那些丢了就是数据损坏。
   *
   * <p>释放器必须幂等且不阻塞：它们会在采样线程（60s 周期）与 OOM 兜底路径上被调用。
   */
  private fun registerMemoryReleasers() {
    com.tom.rv2ide.artificial.agent.MemoryPressureGuard.register("markdown-preprocess") {
      com.tom.rv2ide.artificial.agent.compose.components.style.MarkdownPreprocessor.clearCache()
    }
    com.tom.rv2ide.artificial.agent.MemoryPressureGuard.register("latex-bitmaps") {
      com.tom.rv2ide.artificial.agent.compose.components.markdown.clearLatexBitmapCache()
    }
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    // 系统主动回收压力（切后台、内存紧张）时先丢纯缓存，比等 OOM 好。
    if (level >= TRIM_MEMORY_RUNNING_LOW) {
      com.tom.rv2ide.artificial.agent.MemoryPressureGuard.releaseNow()
    }
  }

  companion object {

    /**
     * OOM 恢复所需的最小剩余堆（字节）。
     *
     * <p>取 8MB：释放缓存后若连这个量都腾不出来，说明进程已无救，
     * 继续运行只会在下一次分配时再崩一次（而且可能崩在更危险的位置，
     * 例如写文件中途）。
     */
    private const val OOM_RECOVERY_MIN_HEADROOM_BYTES = 8L * 1024 * 1024

    private val log = LoggerFactory.getLogger(IDEApplication::class.java)

    @JvmStatic
    lateinit var instance: IDEApplication
      private set
  }
}
