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
import android.widget.TextView
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R as MainR
import com.tom.rv2ide.activities.SdkManagerActivity
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
 * 「SDK 管理器」入口。
 *
 * <p>此前这里是两个单选对话框（Build-tools 版本 / 平台版本）。改为一处入口进独立界面——
 * 版本项动辄十几个，弹窗既难看又难选，且弹窗表达不了「多个版本共存」。
 *
 * <p>组件清单、已装/可装版本、安装动作都在 [SdkManagerActivity] 里，后端是官方
 * `sdkmanager`（直连 Google 仓库，能装 build-tools 至 37、platforms 至 android-36）。
 */
// SimpleClickablePreference 是 final（Kotlin 类默认不可继承），故用工厂函数返回实例，
// 而不是继承它——它的 onClick lambda 参数正是为此设计。
private fun SdkManagerEntryPreference(): SimpleClickablePreference =
    SimpleClickablePreference(
        key = "idepref_sdk_manager",
        title = string.idepref_sdk_manager_title,
        summary = string.idepref_sdk_manager_summary,
        icon = drawable.ic_android,
    ) { preference ->
      preference.context.startActivity(
          Intent(preference.context, SdkManagerActivity::class.java)
      )
      true
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
    addPreference(SdkManagerEntryPreference())
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
