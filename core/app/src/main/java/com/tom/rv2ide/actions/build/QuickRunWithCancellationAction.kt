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

package com.tom.rv2ide.actions.build

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.content.ContextCompat
import com.tom.rv2ide.R.string
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.BaseBuildAction
import com.tom.rv2ide.actions.getContext
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.openApplicationModuleChooser
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.managers.PreferenceManager
import com.tom.rv2ide.models.ApkMetadata
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.services.builder.GradleBuildService
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata
import com.tom.rv2ide.ui.InstallingDialog
import com.tom.rv2ide.utils.ApkInstaller
import com.tom.rv2ide.utils.InstallationResultHandler
import com.tom.rv2ide.utils.IntentUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.resolveAttr
import io.github.miyazkaori.silentinstaller.SilentInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

const val INSTALL_VIA_SHIZUKU = "ide.build.run.installViaShizuku"

/**
 * The 'Quick Run' and 'Cancel build' action in the editor activity.
 *
 * If a build is in progress, executing this action cancels the build. Otherwise, the selected build
 * variant is built and installed to the device.
 *
 * @author Akash Yadav
 */
class QuickRunWithCancellationAction(context: Context, override val order: Int) :
    BaseBuildAction() {

  private val prefManager: PreferenceManager
    get() = BaseApplication.getBaseInstance().prefManager

  companion object {

    private val log = LoggerFactory.getLogger(QuickRunWithCancellationAction::class.java)
  }

  init {
    label = context.getString(R.string.quick_run_debug)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run_outline)
    enabled = false
  }

  override val id: String = "ide.editor.build.quickRun"

  // Execute on UI thread as this action might try to show dialogs to the user
  override var requiresUIThread: Boolean = true

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val context =
        data.getActivity()
            ?: run {
              markInvisible()
              return
            }

    if (data.isBuildInProgress()) {
      label = context.getString(R.string.title_cancel_build)
      icon = ContextCompat.getDrawable(context, R.drawable.ic_stop_daemons)
    } else {
      label = context.getString(R.string.quick_run_debug)
      icon = ContextCompat.getDrawable(context, R.drawable.ic_run_outline)
    }

    visible = true
    enabled = true
  }

  override fun createColorFilter(data: ActionData): ColorFilter? {
    return data.getContext()?.let {
      PorterDuffColorFilter(
          it.resolveAttr(if (data.isBuildInProgress()) R.attr.colorError else R.attr.colorSuccess),
          PorterDuff.Mode.SRC_ATOP,
      )
    }
  }

  override suspend fun execAction(data: ActionData): Boolean {
    if (data.isBuildInProgress()) {
      return cancelBuild()
    }

    return quickRun(data)
  }

  private fun quickRun(data: ActionData): Boolean {
    openApplicationModuleChooser(data) { module ->
      val activity = data.requireActivity()

      val variant =
          module.getSelectedVariant()
              ?: run {
                activity.flashError(activity.getString(R.string.err_selected_variant_not_found))
                return@openApplicationModuleChooser
              }

      val taskName = "${module.path}:${variant.mainArtifact.assembleTaskName}"
      log.info(
          "Running task '$taskName' to assemble variant '${variant.name}' of project '${module.path}'"
      )

      onModuleSelected(data, module, variant, taskName)
    }
    return true
  }

  private fun onModuleSelected(
      data: ActionData,
      module: AndroidModule,
      variant: BasicAndroidVariantMetadata,
      taskName: String,
  ) {

    val buildService =
        this.buildService
            ?: run {
              log.error("Cannot execute task '{}'. BuildService not found.", taskName)
              return
            }

    val activity =
        data.getActivity()
            ?: run {
              log.error(
                  "Cannot execute task '{}'. Activity instance not provided in ActionData.",
                  taskName,
              )
              return
            }

    // 多标签页：切到另一个项目后，Service 的 requireConnection() 会为**新项目**新建
    // 连接，而新连接的 tooling server 还没启动。原先这里只 `isToolingServerStarted()`
    // 判断一次就返回（用户看到「工具服务不可用」），更糟的是在它之后直接
    // executeTasks()——那条路径会抛 ToolingServerNotStartedException，而它在
    // Default 调度器的协程里、没人捕获，直接 FATAL 把整个进程打掉：用户看到的是
    // 「点绿色构建按钮后标签只剩一个」，因为进程重启后会话表全丢。
    //
    // 改为走 Service 的 ensureToolingServerStarted：server 已就绪时同步回调，
    // 否则登记回调、启动完成后再继续。异常也在协程内兜住。
    val gradleService = buildService as? GradleBuildService
    if (gradleService == null) {
      runBuild(data, activity, buildService, module, variant, taskName)
      return
    }

    var handled = false
    val proceed = {
      if (!handled) {
        handled = true
        runBuild(data, activity, buildService, module, variant, taskName)
      }
    }
    val startedNow = gradleService.ensureToolingServerStarted { proceed() }
    if (!startedNow) {
      // 正在启动：给用户一个明确反馈，避免以为点击没生效而重复点。
      activity.runOnUiThread { activity.setStatus(activity.getString(string.msg_initializing_project)) }
    }
  }

  private fun runBuild(
      data: ActionData,
      activity: EditorHandlerActivity,
      buildService: BuildService,
      module: AndroidModule,
      variant: BasicAndroidVariantMetadata,
      taskName: String,
  ) {
    actionScope
        .launch(Dispatchers.Default) {
          // 整个构建链都必须兜住异常：actionScope 是普通 CoroutineScope（无
          // SupervisorJob），未捕获异常会走到线程的默认 handler，直接 FATAL 杀进程。
          // 之前只包了 executeTasks，saveAllResult() / handleResult() 抛异常同样致命。
          try {
            activity.saveAllResult()

            val result = withContext(Dispatchers.IO) { buildService.executeTasks(taskName).get() }

            log.debug("Task execution result: {}", result)

            if (result?.isSuccessful != true) {
              log.error("Tasks failed to execute: '{}'", taskName)
              return@launch
            }

            handleResult(data, result, module, variant)
          } catch (e: Throwable) {
            log.error("Failed to execute task '{}'", taskName, e)
            activity.runOnUiThread {
              activity.flashError(
                  e.message ?: activity.getString(com.tom.rv2ide.resources.R.string.build_status_failed))
            }
          }
        }
        .invokeOnCompletion { error ->
          if (error != null) {
            log.error("Failed to run '{}'", taskName, error)
          }
        }
  }

  private fun cancelBuild(): Boolean {
    log.info("Sending build cancellation request...")
    val builder = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
    if (builder?.isToolingServerStarted() != true) {
      flashError(com.tom.rv2ide.projects.R.string.msg_tooling_server_unavailable)
      return false
    }

    builder?.cancelCurrentBuild()?.whenComplete { result, error ->
      if (error != null) {
        log.error("Failed to send build cancellation request", error)
        return@whenComplete
      }

      if (!result.wasEnqueued) {
        log.warn(
            "Unable to enqueue cancellation request reason={} reason.message={}",
            result.failureReason,
            result.failureReason!!.message,
        )
        return@whenComplete
      }

      log.info("Build cancellation request was successfully enqueued...")
    }

    return true
  }

  private fun handleResult(
      data: ActionData,
      result: TaskExecutionResult?,
      module: AndroidModule,
      variant: BasicAndroidVariantMetadata,
  ) {
    if (result == null || !result.isSuccessful) {
      log.debug("Cannot install APK. Task execution failed.")
      return
    }

    log.debug("Installing APK(s) for project: '{}' variant: '{}'", module.path, variant.name)

    val main = variant.mainArtifact
    val outputListingFile = main.assembleTaskOutputListingFile
    if (outputListingFile == null) {
      log.error("No output listing file provided with project model")
      return
    }

    log.trace("Parsing metadata")
    val apkFile = ApkMetadata.findApkFile(outputListingFile)
    if (apkFile == null) {
      log.error("No apk file specified in output listing file: {}", outputListingFile)
      return
    }

    if (!apkFile.exists()) {
      log.error("APK file specified in output listing file does not exist! {}", apkFile)
      return
    }

    install(data, apkFile)
  }

  private fun install(data: ActionData, apk: File) {
    val activity =
        data.getActivity()
            ?: run {
              log.error("Cannot install APK. Unable to get activity instance.")
              return
            }

    if (!apk.exists()) {
      log.error("APK file does not exist!")
      return
    }

    log.debug("Installing APK: {}", apk)

    if (prefManager.getBoolean(INSTALL_VIA_SHIZUKU, false)) {
      var installingDialog: InstallingDialog? = null
      activity.runOnUiThread {
        installingDialog = InstallingDialog.create(activity, "Installing…")
        installingDialog?.show()
      }

      log.info("Installing APK via shizuku: {}", apk)
      SilentInstaller.install(
          apk,
          object : SilentInstaller.InstallCallback {
            override fun onPermissionDenied() {
              activity.runOnUiThread {
                installingDialog?.dismiss()
                flashError("Permission denied")
                // fallback
                activity.runOnUiThread {
                  ApkInstaller.installApk(
                      activity,
                      InstallationResultHandler.createEditorActivitySender(activity),
                      apk,
                      activity.installationSessionCallback(),
                  )
                }
              }
            }

            override fun onSuccess(status: Int, message: String) {
              activity.runOnUiThread { installingDialog?.dismiss() }
              openApplicationModuleChooser(data) { app ->
                val variant = app.getSelectedVariant()

                log.debug("Selected variant: {}", variant?.name)

                if (variant == null) {
                  activity.runOnUiThread { flashError("Selected build variant not found") }
                  return@openApplicationModuleChooser
                }

                val applicationId = variant.mainArtifact.applicationId
                if (applicationId == null) {
                  log.error(
                      "Unable to launch application. variant.mainArtifact.applicationId is null"
                  )
                  activity.runOnUiThread {
                    flashError("Cannot run application. Unable to determine package name.")
                  }
                  return@openApplicationModuleChooser
                }

                log.info("Launching application: {}", applicationId)

                IntentUtils.launchApp(data.requireActivity(), applicationId, logError = false)
              }
            }

            override fun onFailure(status: Int, message: String, tr: Throwable?) {
              activity.runOnUiThread {
                installingDialog?.dismiss()
                flashError("Install failure: $message")
              }
              log.error(message)
            }
          },
      )

      return
    }

    activity.runOnUiThread {
      ApkInstaller.installApk(
          activity,
          InstallationResultHandler.createEditorActivitySender(activity),
          apk,
          activity.installationSessionCallback(),
      )
    }
  }

  private fun ActionData.isBuildInProgress(): Boolean {
    val context = getActivity()
    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
    return context?.editorViewModel?.let { it.isInitializing || it.isBuildInProgress } == true ||
        buildService?.isBuildInProgress == true
  }
}
