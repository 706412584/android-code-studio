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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.tooling.api.Aapt2Config
import com.tom.rv2ide.tooling.api.messages.AndroidInitializationParams
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * 后台项目构建：为「没有在编辑器里打开」的项目建立独立 tooling 连接。
 *
 * <p><b>为什么不能用 Service 的编辑器连接</b>：Service 只有一个编辑器连接，绑在
 * `ProjectManagerImpl` 的当前项目上。后台构建若复用它会两件事同时出错——构建打到
 * 错误的项目，以及 tooling server 的 `initializeProject` 把编辑器状态切走。
 *
 * <p><b>实现方式</b>：复用同一个 [GradleBuildService]（Service 层是单例，无法每项目
 * 一个），但在 Service 内为后台项目单独创建 [ToolingConnection]。连接互不共享
 * tooling 进程，因此可以真正并行（受 P5 的并发上限约束）。
 *
 * <p><b>硬边界</b>：本类不得触碰 `ProjectManagerImpl`、LSP、索引。项目路径完全由
 * 调用方显式传入。
 */
internal object BackgroundBuildManager {

  private val log = LoggerFactory.getLogger(BackgroundBuildManager::class.java)

  /** 连接建立超时：tooling JVM 冷启动约 3~8 秒，留足余量。 */
  private const val CONNECT_TIMEOUT_MS = 30_000L

  /** 初始化（sync）超时：首次同步可能拉依赖，给得比连接宽。 */
  private const val INIT_TIMEOUT_MS = 180_000L

  /**
   * 为指定项目取得（必要时创建）一个后台构建连接。
   *
   * <p>已存在且 tooling server 已启动时直接复用；否则新建连接并初始化项目。
   *
   * @return 可用连接；无法建立时返回 null（调用方负责给出错误文案）
   */
  fun getOrCreate(context: Context, projectPath: String): BuildService? {
    val path = projectPath.trim()
    if (path.isEmpty()) {
      return null
    }
    val registry = BuildConnectionRegistryHolder.get() ?: return null

    // 已登记且工具服务在跑：直接复用。编辑器的项目也会命中这一支。
    registry.find(path)?.let { existing ->
      if (existing.isToolingServerStarted()) {
        return existing
      }
    }

    val root = File(path)
    if (!root.isDirectory) {
      log.warn("Background build requested for non-directory: {}", path)
      return null
    }

    val service = bindBuildService(context) ?: return null
    val connection = service.createBackgroundConnection(path) ?: return null

    // 启动 tooling server 并等待就绪。
    val started = java.util.concurrent.CountDownLatch(1)
    connection.startToolingServer(buildEnvironment(context), { started.countDown() })
    if (!started.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      log.error("Tooling server for {} did not start within {}ms", path, CONNECT_TIMEOUT_MS)
      connection.close()
      return null
    }

    // 初始化项目：只做 sync，绝不触发编辑器事件。
    val params =
        InitializeProjectParams(
            root.absolutePath,
            gradleDistributionParams,
            AndroidInitializationParams.DEFAULT,
        )
    return try {
      val result = connection.initializeProject(params).get(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      if (result == null) {
        log.error("Project initialization returned null for {}", path)
        connection.close()
        null
      } else {
        registry.register(path, connection, BuildConnectionRegistry.Owner.BACKGROUND)
        connection
      }
    } catch (e: Exception) {
      log.error("Failed to initialize background project {}", path, e)
      connection.close()
      null
    }
  }

  /** 绑定 Service 并取回实例；失败返回 null。 */
  private fun bindBuildService(context: Context): GradleBuildService? {
    // 已绑定过（编辑器打开着）：直接用现成实例。
    (com.tom.rv2ide.lookup.Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
            as? GradleBuildService)
        ?.let {
          return it
        }

    val appContext = context.applicationContext
    val latch = java.util.concurrent.CountDownLatch(1)
    var bound: GradleBuildService? = null
    val connection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = (service as? GradleServiceBinder)?.service
            latch.countDown()
          }

          override fun onServiceDisconnected(name: ComponentName?) {
            latch.countDown()
          }
        }
    val ok =
        appContext.bindService(
            Intent(appContext, GradleBuildService::class.java),
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
    if (!ok) {
      log.error("Unable to bind to GradleBuildService for background build")
      return null
    }
    if (!latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      log.error("Timed out binding to GradleBuildService")
      return null
    }
    return bound
  }

  /** tooling server 的启动环境（与编辑器路径同源）。 */
  private fun buildEnvironment(context: Context): Map<String, String> {
    val envs = TermuxShellEnvironment().getEnvironment(context, false)
    val aapt2 = Environment.AAPT2
    if (aapt2.isFile) {
      envs[Aapt2Config.ENV_AAPT2_OVERRIDE] = aapt2.absolutePath
    }
    return envs
  }

  /** 后台连接是否需要把 logsender 的 init script 一起传下去。 */
  internal fun loggerInitScriptEnabled(): Boolean = DevOpsPreferences.logsenderEnabled
}
