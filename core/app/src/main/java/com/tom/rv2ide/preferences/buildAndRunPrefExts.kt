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
import android.content.Intent
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.R as MainR
import com.tom.rv2ide.activities.TerminalActivity
import com.tom.rv2ide.fragments.onboarding.IdeSetupConfigurationFragment
import com.tom.rv2ide.fragments.onboarding.SdkVersion
import com.tom.rv2ide.models.IdeSetupArgument
import com.tom.rv2ide.utils.Environment
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
import com.tom.rv2ide.tasks.executeAsync
import com.tom.rv2ide.utils.Environment.GRADLE_USER_HOME
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashSuccess
import java.io.File
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
 * Android SDK 版本（构建工具链）。
 *
 * <p>此前 SDK 版本的选择/安装状态**只在首次引导**（`IdeSetupConfigurationFragment`）出现——
 * 装完进去后就再也看不到、也改不了（用户报的「构建和运行里看不到安卓 sdk 版本」）。
 * 这里把它补进设置，与「JDK 版本」并列放在 Gradle 组内（同属构建工具链）。
 *
 * <p>显示当前已装的 build-tools 版本（列 `ANDROID_HOME/build-tools` 目录，即 idesetup
 * `--sdk ver` 装的那一项），点开可从 [SdkVersion] 里选一个装/换；对话框里每个版本都
 * 标注「已安装 / 可安装」。
 *
 * <p>安装复用引导页那套：以 [TerminalActivity] 跑 `idesetup --sdk <ver>`（原生安装器，
 * 见 `IdesetupSession`）。不走复制的脚本——idesetup 是 ELF 可执行，含下载/校验逻辑。
 *
 * <p><b>可选版本的上限由 manifest 决定</b>：`--sdk` 装的是 **build-tools**，而
 * build-tools 包在上游与 CN 镜像里都只到 35.0.1（[SdkVersion] 即其镜像）。compileSdk
 * 能到 37 是另一条链路（platforms 目录），与这里无关。
 */
@Parcelize
private class SdkVersionPreference(
    override val key: String = "idepref_build_sdk_version",
    override val title: Int = string.idepref_sdk_version_title,
    override val icon: Int? = drawable.ic_android,
) : SingleChoicePreference() {

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    val ctx = preference.context
    val installed = installedSdkVersions()
    val installedSet = installed.toSet()
    val current = installed.lastOrNull()
    // 枚举里没有的「已装但非枚举版本」（如设备上的 33.0.0）也列出来，
    // 否则「已安装」显示与实际对不上。
    val candidates = (SdkVersion.entries.map { it.version } + installed).distinct().sorted()
    return candidates
        .map { v ->
          val label =
              if (v in installedSet) {
                ctx.getString(string.idepref_sdk_entry_installed, v)
              } else {
                ctx.getString(string.idepref_sdk_entry_available, v)
              }
          // data 放原始版本号：label 现在带后缀，不能再拿 label 当版本用。
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
    // 取 data 里的原始版本号：label 现在带「已安装/可安装」后缀，不能用它当版本。
    val version = entry?.data?.toString() ?: return
    if (version in installedSdkVersions()) {
      Toast.makeText(
              preference.context,
              preference.context.getString(string.idepref_sdk_already_installed, version),
              Toast.LENGTH_SHORT)
          .show()
      return
    }
    installSdk(preference.context, version)
  }

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { updateSummary(it, context) }
  }

  private fun updateSummary(preference: Preference, context: Context) {
    val installed = installedSdkVersions()
    preference.summary =
        if (installed.isEmpty()) {
          context.getString(string.idepref_sdk_not_installed)
        } else {
          context.getString(string.idepref_sdk_installed, installed.joinToString(", "))
        }
  }

  private fun installSdk(context: Context, version: String) {
    val args = mutableListOf<String>()
    args.add(IdeSetupArgument.INSTALL_DIR.argumentName)
    args.add(Environment.HOME.absolutePath)
    args.add(IdeSetupArgument.SDK_VERSION.argumentName)
    args.add(version)
    args.add(IdeSetupArgument.ASSUME_YES.argumentName)
    // CN 版传 Gitee manifest（与引导页一致）：默认 manifest 的 build-tools 走 GitHub，
    // 国内实测超时。常规版不传，走内建默认源。
    if (BuildConfig.CN_MIRROR) {
      args.add(IdeSetupArgument.MANIFEST.argumentName)
      args.add(IdeSetupConfigurationFragment.IDE_SETUP_MANIFEST_URL)
    }

    val intent = Intent(context, TerminalActivity::class.java).apply {
      putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP, true)
      putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP_ARGS, args.toTypedArray())
    }
    context.startActivity(intent)
  }

  /** 已安装的 build-tools 版本（即 idesetup `--sdk` 的产物），升序。 */
  private fun installedSdkVersions(): List<String> {
    val dir = File(Environment.ANDROID_HOME, "build-tools")
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
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
    addPreference(SdkVersionPreference())
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
