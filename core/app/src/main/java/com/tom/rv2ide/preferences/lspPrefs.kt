/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.preferences

import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.lsp.dart.DartSdkInstaller
import com.tom.rv2ide.lsp.kotlin.etc.LspFeatures
import com.tom.rv2ide.lsp.typescript.TypeScriptServerInstaller
import com.tom.rv2ide.preferences.internal.LSPPreferences
import com.tom.rv2ide.preferences.internal.LSPPreferences.ACS_KOTLIN_LSP_CURSOR_HOVER
import com.tom.rv2ide.preferences.internal.LSPPreferences.ACS_KOTLIN_LSP_DIAGNOSTICS
import com.tom.rv2ide.preferences.internal.LSPPreferences.ACS_KOTLIN_LSP_FORMAT_STYLE
import com.tom.rv2ide.preferences.observers.LSPStateObserver
import com.tom.rv2ide.resources.R.drawable
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.utils.Environment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

@Parcelize
class LSPPreferencesScreen(
    override val key: String = "idepref_editor_lsp",
    override val title: Int = string.servers,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(LSPOptions())
  }
}

@Parcelize
private class LSPOptions(
    override val key: String = "idepref_lsp_options",
    override val title: Int = string.lsp_options,
    override val summary: Int? = string.lsp_options_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(KotlinCategory())
    addPreference(TypeScriptCategory())
    addPreference(DartCategory())
  }
}

/**
 * TypeScript / JavaScript（React Native 模板）。
 *
 * TSLS 走 npm 装进**项目内** node_modules（与项目锁定的 TS 版本配套），因此安装
 * 入口在条目弹窗里：一键对当前项目根执行 npm install（见 [TypeScriptLSP]）。
 */
@Parcelize
private class TypeScriptCategory(
    override val key: String = "lsp_typescript_category",
    override val title: Int = string.typescript,
    override val summary: Int? = string.typescript_lsp_category_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(TypeScriptLSP())
  }
}

@Parcelize
private class TypeScriptLSP(
    override val key: String = "lsp_typescript_server",
    override val title: Int = string.lsp_options_typescript_title,
    override val summary: Int? = string.lsp_options_typescript_summary,
) : DialogPreference() {

  override fun onConfigureDialog(preference: Preference, dialog: MaterialAlertDialogBuilder) {
    super.onConfigureDialog(preference, dialog)
    val ctx = preference.context
    val projectDir = com.tom.rv2ide.projects.IProjectManager.getInstance().projectDirOrNull
    val installed = projectDir != null && TypeScriptServerInstaller.isInstalled(projectDir)

    // 装好后只需展示状态；未装则给一键安装按钮。
    dialog.setMessage(
        if (installed) {
          ctx.getString(string.typescript_lsp_install_success)
        } else {
          ctx.getString(string.typescript_lsp_hint)
        })

    if (!installed) {
      dialog.setNeutralButton(string.lsp_install_str) { _, _ -> installTypeScriptServer(ctx) }
    }
    dialog.setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
  }

  /**
   * 一键安装：在内置 Termux 里对**当前项目根**执行 npm install。
   *
   * <p>此前该条目只能提示「在项目根执行 npm install」，用户要求在设置里直接安装
   * （用户报的「点击 TS 提示在项目执行安装，没有可直接下载的吗」）。这里复用内置
   * node/npm，命令与提示里给出的完全一致（npmmirror 源）。
   */
  private fun installTypeScriptServer(ctx: android.content.Context) {
    val projectDir = com.tom.rv2ide.projects.IProjectManager.getInstance().projectDirOrNull
    if (projectDir == null) {
      MaterialAlertDialogBuilder(ctx)
          .setTitle(string.lsp_options_typescript_title)
          .setMessage(string.typescript_lsp_no_project)
          .setPositiveButton(android.R.string.ok, null)
          .show()
      return
    }

    val progressDialog =
        MaterialAlertDialogBuilder(ctx)
            .setTitle(string.lsp_options_typescript_title)
            .setMessage(string.typescript_lsp_install_running)
            .setCancelable(false)
            .create()
    progressDialog.show()

    CoroutineScope(Dispatchers.Main).launch {
      val result = TypeScriptServerInstaller.install(projectDir)
      progressDialog.dismiss()
      result
          .onSuccess {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(string.lsp_server_success_title)
                .setMessage(string.typescript_lsp_install_success)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            LSPStateObserver.notifyServerStateChanged()
          }
          .onFailure { e ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(string.lsp_server_error_title)
                .setMessage(ctx.getString(string.typescript_lsp_install_failed, e.message ?: ""))
                .setPositiveButton(android.R.string.ok, null)
                .show()
          }
    }
  }
}

/**
 * Dart（Flutter 模板）。
 *
 * 走独立下载通道（[DartSdkInstaller]）而不是 LSPPreference 的 manifest 模式：
 * Dart SDK 不是「语言服务器 zip」而是完整 SDK（67MB），分发地址与校验逻辑都在
 * DartSdkInstaller 里，复用它比把 SDK 塞进 manifest 更直接。
 */
@Parcelize
private class DartCategory(
    override val key: String = "lsp_dart_category",
    override val title: Int = string.dart,
    override val summary: Int? = string.dart_lsp_category_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(DartLSP())
  }
}

@Parcelize
private class DartLSP(
    override val key: String = "lsp_dart_server",
    override val title: Int = string.lsp_options_dart_title,
    override val summary: Int? = string.lsp_options_dart_summary,
) : DialogPreference() {

  override fun onConfigureDialog(preference: Preference, dialog: MaterialAlertDialogBuilder) {
    super.onConfigureDialog(preference, dialog)
    val ctx = preference.context
    val installed = DartSdkInstaller.isInstalled()
    dialog.setMessage(
        if (installed) {
          ctx.getString(
              string.dart_sdk_size,
              android.text.format.Formatter.formatShortFileSize(
                  ctx, DartSdkInstaller.installedSizeBytes()))
        } else {
          ctx.getString(string.dart_sdk_not_installed)
        })

    if (installed) {
      dialog.setNeutralButton(string.lsp_server_uninstall) { _, _ ->
        MaterialAlertDialogBuilder(ctx)
            .setTitle(string.lsp_server_uninstall_title)
            .setMessage(string.dart_sdk_uninstall_message)
            .setPositiveButton(string.lsp_server_uninstall) { _, _ ->
              DartSdkInstaller.uninstall()
              LSPStateObserver.notifyServerStateChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
      }
    } else {
      dialog.setNeutralButton(string.updater_download) { _, _ -> downloadDartSdk(ctx) }
    }

    dialog.setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
  }

  private fun downloadDartSdk(ctx: android.content.Context) {
    val progressLayout =
        android.widget.LinearLayout(ctx).apply {
          orientation = android.widget.LinearLayout.VERTICAL
          setPadding(48, 32, 48, 16)
        }
    val progressText =
        MaterialTextView(ctx).apply {
          text = ctx.getString(string.dart_sdk_downloading)
          textSize = 15f
        }
    val progressBar =
        android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
          isIndeterminate = false
          max = 100
        }
    progressLayout.addView(progressText)
    progressLayout.addView(progressBar)

    val progressDialog =
        MaterialAlertDialogBuilder(ctx)
            .setTitle(string.lsp_options_dart_title)
            .setView(progressLayout)
            .setCancelable(false)
            .create()
    progressDialog.show()

    CoroutineScope(Dispatchers.Main).launch {
      val result =
          DartSdkInstaller.install(
              ctx.applicationContext,
              object : DartSdkInstaller.ProgressListener {
                override fun onStage(message: String) {
                  progressText.text = message
                }

                override fun onProgress(fraction: Float?) {
                  progressBar.progress = ((fraction ?: 0f) * 100).toInt()
                }
              })
      progressDialog.dismiss()
      result
          .onSuccess {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(string.lsp_server_success_title)
                .setMessage(string.dart_sdk_install_success)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            LSPStateObserver.notifyServerStateChanged()
          }
          .onFailure { e ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(string.lsp_server_error_title)
                .setMessage(ctx.getString(string.dart_sdk_install_failed, e.message ?: ""))
                .setPositiveButton(android.R.string.ok, null)
                .show()
          }
    }
  }
}

// New Kotlin Category
@Parcelize
private class KotlinCategory(
    override val key: String = "lsp_kotlin_category",
    override val title: Int = string.kotlin,
    override val summary: Int? = string.kotlin_lsp_category_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(KotlinLSP())
    addPreference(KotlinHover())
    addPreference(KotlinDiagnostics())
    addPreference(KotlinFormatStyle())
  }
}

@Parcelize
private class KotlinLSP(
    override val key: String = "lsp_kotlin_server",
    override val title: Int = string.lsp_options_kotlin_title,
    override val summary: Int? = string.lsp_options_kotlin_summary,
) :
    LSPPreference(
        hint = string.server_status,
        getValue = { getStatus(isKotlinServerInstalled()) },
        serverId = "Kotlin",
        isInstalled = ::isKotlinServerInstalled,
    )

@Parcelize
private class KotlinHover(
    override val key: String = ACS_KOTLIN_LSP_CURSOR_HOVER,
    override val title: Int = string.acs_lsp_hover_title,
    override val icon: Int? = drawable.ic_tooltip,
) :
    SwitchPreference(
        setValue = LSPPreferences::cursorHover::set,
        getValue = LSPPreferences::cursorHover::get,
    ) {

  @IgnoredOnParcel
  override val summary: Int?
    get() =
        if (isKotlinServerInstalled()) {
          string.acs_lsp_hover_summary
        } else {
          string.kotlin_server_required
        }

  override fun onCreatePreference(
      context: android.content.Context
  ): androidx.preference.Preference {
    val pref = super.onCreatePreference(context)
    pref.isEnabled = isKotlinServerInstalled()
    return pref
  }
}

@Parcelize
private class KotlinDiagnostics(
    override val key: String = ACS_KOTLIN_LSP_DIAGNOSTICS,
    override val title: Int = string.acs_lsp_diagnostics_title,
    override val icon: Int? = drawable.ic_diagnostics,
) :
    SwitchPreference(
        setValue = LSPPreferences::codeDiagnostics::set,
        getValue = LSPPreferences::codeDiagnostics::get,
    ) {

  @IgnoredOnParcel
  override val summary: Int?
    get() =
        if (isKotlinServerInstalled()) {
          string.acs_lsp_diagnostics_summary
        } else {
          string.kotlin_server_required
        }

  override fun onCreatePreference(
      context: android.content.Context
  ): androidx.preference.Preference {
    val pref = super.onCreatePreference(context)
    pref.isEnabled = isKotlinServerInstalled()
    return pref
  }
}

@Parcelize
private class KotlinFormatStyle(
    override val key: String = ACS_KOTLIN_LSP_FORMAT_STYLE,
    override val title: Int = string.acs_lsp_kotlin_code_style_title,
    override val icon: Int? = drawable.ic_format_code,
) : SingleChoicePreference() {

  @IgnoredOnParcel override val dialogCancellable = true

  @IgnoredOnParcel
  override val summary: Int?
    get() =
        if (isKotlinServerInstalled()) {
          string.acs_lsp_kotlin_code_style_summary
        } else {
          string.kotlin_server_required
        }

  companion object {
    private const val STYLE_KOTLINLANG = "kotlinlang"
    private const val STYLE_GOOGLE = "google"
    private const val STYLE_FACEBOOK = "facebook"
    private val STYLES = listOf(STYLE_GOOGLE, STYLE_KOTLINLANG, STYLE_FACEBOOK)
    private const val DEFAULT_STYLE = STYLE_GOOGLE
  }

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    val currentStyle = LSPPreferences.codeFormatStyle
    return STYLES.mapIndexed { index, style ->
          PreferenceChoices.Entry(style, currentStyle == style, style)
        }
        .toTypedArray()
  }

  override fun onChoiceConfirmed(
      preference: Preference,
      entry: PreferenceChoices.Entry?,
      position: Int,
  ) {
    val selectedStyle = STYLES.getOrNull(position) ?: DEFAULT_STYLE
    LSPPreferences.codeFormatStyle = selectedStyle
    LspFeatures.setCodeFormatStyle(selectedStyle)
  }

  override fun onCreatePreference(
      context: android.content.Context
  ): androidx.preference.Preference {
    val pref = super.onCreatePreference(context)
    pref.isEnabled = isKotlinServerInstalled()
    return pref
  }
}

/* DEPRECATED! clang lsp installation handled via terminal
@Parcelize
private class CCPPCategory(
    override val key: String = "lsp_ccpp_category",
    override val title: Int = string.c_cpp,
    override val summary: Int? = string.ccpp_lsp_category_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(CCPPLSP())
  }
}

@Parcelize
private class CCPPLSP(
    override val key: String = "lsp_c_cpp_server",
    override val title: Int = string.lsp_options_c_cpp_title,
    override val summary: Int? = string.lsp_options_c_cpp_summary,
) :
    LSPPreference(
        hint = string.server_status,
        getValue = { getStatus(isCCppServerInstalled()) },
        serverId = "C_CPP",
        isInstalled = ::isCCppServerInstalled,
    )
*/

private fun getStatus(installed: Boolean): String = if (installed) "installed" else "not installed"

private fun isKotlinServerInstalled(): Boolean {
  val serverDir = File(Environment.HOME, "acs/servers/kotlin/server")
  return serverDir.exists() && serverDir.isDirectory && serverDir.listFiles()?.isNotEmpty() == true
}

private fun isCCppServerInstalled(): Boolean {
  val serverDir = File(Environment.HOME, "acs/servers/c_cpp")
  return serverDir.exists() && serverDir.isDirectory && serverDir.listFiles()?.isNotEmpty() == true
}
