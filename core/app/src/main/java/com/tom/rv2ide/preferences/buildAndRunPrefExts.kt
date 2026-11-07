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

package com.tom.rv2ide.preferences

import android.content.Context
import android.widget.TextView
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R as MainR
import com.tom.rv2ide.app.configuration.IJdkDistributionProvider
import com.tom.rv2ide.models.JdkDistribution
import com.tom.rv2ide.preferences.internal.BuildPreferences.CUSTOM_GRADLE_INSTALLATION
import com.tom.rv2ide.preferences.internal.BuildPreferences.ENABLE_BUILD_OUTPUT
import com.tom.rv2ide.preferences.internal.BuildPreferences.DEPENDENCIES_UPDATER
import com.tom.rv2ide.preferences.internal.BuildPreferences.KT_INDEXING_NOTIFICATION
import com.tom.rv2ide.preferences.internal.BuildPreferences.GRADLE_CLEAR_CACHE
import com.tom.rv2ide.preferences.internal.BuildPreferences.GRADLE_COMMANDS
import com.tom.rv2ide.preferences.internal.BuildPreferences.INSTALL_VIA_SHIZUKU
import com.tom.rv2ide.preferences.internal.BuildPreferences.LAUNCH_APP_AFTER_INSTALL
import com.tom.rv2ide.preferences.internal.BuildPreferences.PREF_JAVA_HOME
import com.tom.rv2ide.preferences.internal.BuildPreferences.gradleInstallationDir
import com.tom.rv2ide.preferences.internal.BuildPreferences.installViaShizuku
import com.tom.rv2ide.preferences.internal.BuildPreferences.isBuildCacheEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isBuildOutputEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isDependenciesUpdaterEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isKtIndexingNotificationEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isDebugEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isInfoEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isOfflineEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isScanEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isStacktraceEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isWarningModeAllEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.javaHome
import com.tom.rv2ide.preferences.internal.BuildPreferences.launchAppAfterInstall
import com.tom.rv2ide.resources.R.drawable
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.handlers.system.installer.SdkManagerInstaller
import com.tom.rv2ide.tasks.executeAsync
import com.tom.rv2ide.utils.Environment.GRADLE_USER_HOME
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashSuccess
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.reflect.KMutableProperty0
import kotlinx.parcelize.Parcelize

@Parcelize
class BuildAndRunPreferences(
    override val key: String = "idepref_build_n_run",
    override val title: Int = string.idepref_build_title,
    override val summary: Int? = string.idepref_buildnrun_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(GradleOptions())
    addPreference(RunOptions())
  }
}

/**
 * SDK 组件版本（build-tools / platforms 共用）。
 *
 * <p>此前这两项只在首次引导出现，装完就再也改不了（用户报的「看不到安卓 sdk 版本」）。
 *
 * <p><b>两条独立的链路，不要混为一谈</b>：
 * - **build-tools**（aapt2/d8/zipalign）：AGP 最低要求（AGP 9.2.0 要 ≥36.0.0）
 * - **platforms**（android-XX 的 android.jar）：`compileSdk` 的目标
 *
 * <p>安装改用官方 **`sdkmanager`**（见 [SdkManagerInstaller]），不再走 `idesetup --sdk`：
 * 后者依赖 AndroidIDE 镜像 manifest，其 build-tools **最高只到 35.0.1**（上游从未构建
 * 更高版本），给不了 AGP 9 要的 36；而 sdkmanager 直连 Google 仓库，实测可装
 * build-tools 至 37.0.0、platforms 至 android-36。
 *
 * <p>可选列表来自 `sdkmanager --list`（慢，走网络），故**在 create 时后台预热缓存**；
 * 用户点开对话框时通常已就绪（未就绪则先显示已安装项，下次打开补全）。
 */
@Parcelize
class SdkPackagePreference(
    override val key: String = "idepref_build_sdk_version",
    override val title: Int = string.idepref_sdk_version_title,
    override val icon: Int? = drawable.ic_android,
    /** sdkmanager 的包前缀：`build-tools` 或 `platforms`。 */
    private val packageId: String = "build-tools",
    /** 版本项在列表里的显示前缀：platforms 用 "android-"，build-tools 用 ""。 */
    private val entryPrefix: String = "",
) : SingleChoicePreference() {

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    val ctx = preference.context
    val installed = installedVersions()
    val installedSet = installed.toSet()
    val current = installed.lastOrNull()
    // 可用列表来自缓存（后台预热）；没有缓存时只列已安装的，别让对话框空着。
    val available = availableVersions()
    val candidates = (available + installed).distinct().sortedWith(sdkVersionComparator)
    return candidates
        .map { v ->
          // 已安装的直接显示版本号（summary 已写「已安装：…」，逐项再标是冗余）；
          // 只有**未安装**的标「（可安装）」——那才是用户需要知道的新信息。
          val label =
              if (v in installedSet) {
                v
              } else {
                ctx.getString(string.idepref_sdk_entry_available, v)
              }
          PreferenceChoices.Entry(label, v == current, v)
        }
        .toTypedArray()
  }

  override fun onChoiceConfirmed(
      preference: Preference,
      entry: PreferenceChoices.Entry?,
      position: Int,
  ) {
    super.onChoiceConfirmed(preference, entry, position)
    // 取 data 里的原始版本号：label 可能带「（可安装）」后缀，不能用它当版本。
    val version = entry?.data?.toString() ?: return
    if (version in installedVersions()) {
      Toast.makeText(
              preference.context,
              preference.context.getString(string.idepref_sdk_already_installed, version),
              Toast.LENGTH_SHORT)
          .show()
      return
    }
    installPackage(preference, version)
  }

  override fun onCreatePreference(context: Context): Preference {
    ensureAvailableLoaded()
    return super.onCreatePreference(context).also { updateSummary(it, context) }
  }

  private fun updateSummary(preference: Preference, context: Context) {
    val installed = installedVersions()
    preference.summary =
        if (installed.isEmpty()) {
          context.getString(string.idepref_sdk_not_installed)
        } else {
          context.getString(string.idepref_sdk_installed, installed.joinToString(", "))
        }
  }

  /**
   * 在后台跑 `sdkmanager --install <pkg>` 并弹出实时输出。
   *
   * <p>输出弹窗是一块可滚动的 [TextView]，逐行追加——安装要下几十 MB，没有输出用户会
   * 以为卡死。完成后刷新本项 summary。
   */
  private fun installPackage(preference: Preference, version: String) {
    val context = preference.context
    val fullId =
        if (entryPrefix.isEmpty()) "$packageId;$version" else "$packageId;$entryPrefix$version"

    val output = TextView(context).apply {
      textSize = 12f
      setPadding(24, 16, 24, 0)
      typeface = android.graphics.Typeface.MONOSPACE
    }
    val scroll =
        android.widget.ScrollView(context).apply {
          addView(output)
          // 限高：sdkmanager 输出可能很长。
          post { scrollTo(0, output.height) }
        }
    val dialog =
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(string.idepref_sdk_installing, version))
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .create()
    dialog.show()

    val cancelled = AtomicBoolean(false)
    dialog.setOnDismissListener { cancelled.set(true) }

    val ctx = context.applicationContext
    CoroutineScope(Dispatchers.IO).launch {
      val installer = SdkManagerInstaller()
      val ok =
          installer.install(fullId) { line ->
            CoroutineScope(Dispatchers.Main).launch {
              if (!cancelled.get()) {
                output.append(line + "\n")
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
              }
            }
          }
      CoroutineScope(Dispatchers.Main).launch {
        if (!cancelled.get()) {
          dialog.dismiss()
          Toast.makeText(
                  ctx,
                  if (ok) {
                    ctx.getString(string.idepref_sdk_install_ok, version)
                  } else {
                    ctx.getString(string.idepref_sdk_install_failed, version)
                  },
                  Toast.LENGTH_LONG)
              .show()
          updateSummary(preference, ctx)
        }
      }
    }
  }

  /** 已安装版本（读磁盘目录），升序。 */
  private fun installedVersions(): List<String> =
      if (packageId == "platforms") {
        SdkManagerInstaller.installedPlatforms().map { it.removePrefix(entryPrefix) }
      } else {
        SdkManagerInstaller.installedBuildTools()
      }

  /** 可安装版本（来自后台预热的缓存；未就绪时为空）。 */
  private fun availableVersions(): List<String> {
    val sdk = cachedAvailable ?: return emptyList()
    return if (packageId == "platforms") sdk.platforms else sdk.buildTools
  }

  private fun ensureAvailableLoaded() {
    if (cachedAvailable != null || loadingAvailable) return
    loadingAvailable = true
    CoroutineScope(Dispatchers.IO).launch {
      val result = runCatching { SdkManagerInstaller().listAvailable() }.getOrNull()
      if (result != null) cachedAvailable = result
      loadingAvailable = false
    }
  }

  private companion object {
    /** 进程级缓存：`sdkmanager --list` 走网络、耗时较长，避免每次开对话框都跑。 */
    @Volatile var cachedAvailable: SdkManagerInstaller.AvailableSdk? = null

    @Volatile var loadingAvailable = false

    /** 版本号比较：按月分段数字比，避免 "9" > "10" 这类字符串排序错误。 */
    val sdkVersionComparator = Comparator<String> { a, b ->
      val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
      val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
      for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return@Comparator c
      }
      0
    }
  }
}

@Parcelize
private class EnableBuildOutput(
    override val key: String = ENABLE_BUILD_OUTPUT,
    override val title: Int = R.string.idepref_enableBuildOutput_title,
    override val summary: Int? = R.string.idepref_enableBuildOutput_summary,
    override val icon: Int? = drawable.ic_text,
) :
    SwitchPreference(
        setValue = ::isBuildOutputEnabled::set,
        getValue = ::isBuildOutputEnabled::get,
    )
    
@Parcelize
private class DependenciesUpdater(
    override val key: String = DEPENDENCIES_UPDATER,
    override val title: Int = R.string.idepref_dependencies_updater_title,
    override val summary: Int? = R.string.idepref_dependencies_updater_summary,
    override val icon: Int? = MainR.drawable.ic_update,
) :
    SwitchPreference(
        setValue = ::isDependenciesUpdaterEnabled::set,
        getValue = ::isDependenciesUpdaterEnabled::get,
    )

@Parcelize
private class KotlinIndexingNotification(
    override val key: String = KT_INDEXING_NOTIFICATION,
    override val title: Int = R.string.idepref_kotlin_indexing_notif_title,
    override val summary: Int? = R.string.idepref_kotlin_indexing_notif_summary,
    override val icon: Int? = MainR.drawable.ic_notification,
) :
    SwitchPreference(
        setValue = ::isKtIndexingNotificationEnabled::set,
        getValue = ::isKtIndexingNotificationEnabled::get,
    )

@Parcelize
private class GradleOptions(
    override val key: String = "idepref_build_gradle",
    override val title: Int = string.gradle,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(EnableBuildOutput())
    addPreference(DependenciesUpdater())
    addPreference(KotlinIndexingNotification())
    addPreference(GradleCommands())
    addPreference(GradleDistrubution())
    addPreference(GradleJDKVersionPreference())
    // build-tools 与 platforms 是两个独立概念，各一项：
    // 前者是 aapt2/d8 那套工具（AGP 有最低版本要求），后者是 compileSdk 的目标。
    addPreference(
        SdkPackagePreference(
            key = "idepref_build_tools_version",
            title = string.idepref_build_tools_title,
            packageId = "build-tools",
        )
    )
    addPreference(
        SdkPackagePreference(
            key = "idepref_platform_version",
            title = string.idepref_platform_title,
            packageId = "platforms",
            entryPrefix = "android-",
        )
    )
    addPreference(GradleClearCache())
  }
}

@Parcelize
private class GradleCommands(
    override val key: String = GRADLE_COMMANDS,
    override val title: Int = string.idepref_build_customgradlecommands_title,
    override val summary: Int? = string.idepref_build_customgradlecommands_summary,
    override val icon: Int? = drawable.ic_bash_commands,
) : PropertyBasedMultiChoicePreference() {

  override fun getProperties(): Map<String, KMutableProperty0<Boolean>> {
    return linkedMapOf(
        "--stacktrace" to ::isStacktraceEnabled,
        "--info" to ::isInfoEnabled,
        "--debug" to ::isDebugEnabled,
        "--scan" to ::isScanEnabled,
        "--warning-mode all" to ::isWarningModeAllEnabled,
        "--build-cache" to ::isBuildCacheEnabled,
        "--offline" to ::isOfflineEnabled,
    )
  }
}

@Parcelize
private class GradleDistrubution(
    override val key: String = CUSTOM_GRADLE_INSTALLATION,
    override val title: Int = string.idepref_title_customGradleInstallation,
    override val summary: Int? = string.idepref_msg_customGradleInstallation,
    override val icon: Int? = drawable.ic_gradle,
) : EditTextPreference() {

  override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
    gradleInstallationDir = newValue as String? ?: ""
    return true
  }

  override fun onConfigureTextInput(input: TextInputLayout) {
    input.setStartIconDrawable(drawable.ic_gradle)
    input.setHint(string.msg_gradle_installation_path)
    input.helperText = input.context.getString(string.msg_gradle_installation_input_help)
    input.isCounterEnabled = false
    input.editText!!.setText(gradleInstallationDir)
  }
}

@Parcelize
private class GradleClearCache(
    override val key: String = GRADLE_CLEAR_CACHE,
    override val title: Int = string.idepref_build_clearCache_title,
    override val summary: Int? = string.idepref_build_clearCache_summary,
    override val icon: Int? = drawable.ic_delete,
    override val dialogMessage: Int? = string.msg_clear_cache,
) : DialogPreference() {

  override fun onConfigureDialog(preference: Preference, dialog: MaterialAlertDialogBuilder) {
    super.onConfigureDialog(preference, dialog)
    dialog.setPositiveButton(string.yes) { dlg, _ ->
      dlg.dismiss()
      executeAsync(callable = this::deleteCaches) {
        if (it == true) {
          flashSuccess(string.deleted)
        } else {
          flashError(string.delete_failed)
        }
      }
    }
    dialog.setNegativeButton(string.no) { dlg, _ -> dlg.dismiss() }
  }

  private fun deleteCaches(): Boolean {
    val caches = File(GRADLE_USER_HOME, "caches")
    if (caches.exists()) {
      return caches.deleteRecursively()
    }
    return false
  }
}

@Parcelize
private class RunOptions(
    override val key: String = "ide.build.runOptions",
    override val title: Int = R.string.title_run_options,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(InstallViaShizuku())
    addPreference(LaunchAppAfterInstall())
  }
}

@Parcelize
private class InstallViaShizuku(
    override val key: String = INSTALL_VIA_SHIZUKU,
    override val title: Int = R.string.idepref_installViaShizuku_title,
    override val summary: Int? = R.string.idepref_installViaShizuku_summary,
    override val icon: Int? = drawable.ic_apk_install,
) : SwitchPreference(setValue = ::installViaShizuku::set, getValue = ::installViaShizuku::get)

@Parcelize
private class LaunchAppAfterInstall(
    override val key: String = LAUNCH_APP_AFTER_INSTALL,
    override val title: Int = R.string.idepref_launchAppAfterInstall_title,
    override val summary: Int? = R.string.idepref_launchAppAfterInstall_summary,
    override val icon: Int? = drawable.ic_open_external,
) :
    SwitchPreference(
        setValue = ::launchAppAfterInstall::set,
        getValue = ::launchAppAfterInstall::get,
    )

@Parcelize
class GradleJDKVersionPreference(
    override val key: String = PREF_JAVA_HOME,
    override val title: Int = R.string.idepref_jdkVersion_title,
    override val icon: Int? = R.drawable.ic_language_java,
) : SingleChoicePreference() {

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    val distributions = IJdkDistributionProvider.getInstance().installedDistributions
    check(distributions.isNotEmpty()) { "No JDK installations are available." }

    return distributions
        .map { dist -> PreferenceChoices.Entry(dist.javaVersion, javaHome == dist.javaHome, dist) }
        .toTypedArray()
  }

  override fun onChoiceConfirmed(
      preference: Preference,
      entry: PreferenceChoices.Entry?,
      position: Int,
  ) {
    super.onChoiceConfirmed(preference, entry, position)
    javaHome = (entry?.data as? JdkDistribution?)?.javaHome ?: ""
    updatePreference(preference)
  }

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { preference -> updatePreference(preference) }
  }

  private fun updatePreference(preference: Preference) {
    val jdkDistProvider = IJdkDistributionProvider.getInstance()
    val javaVersion = jdkDistProvider.forJavaHome(javaHome)?.javaVersion ?: "<unknown>"

    preference.summary =
        preference.context.getString(R.string.idepref_jdkVersion_summary, javaVersion)
    preference.isEnabled = jdkDistProvider.installedDistributions.size > 1
  }
}
