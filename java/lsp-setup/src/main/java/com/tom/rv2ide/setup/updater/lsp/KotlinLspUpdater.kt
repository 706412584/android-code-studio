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

package com.tom.rv2ide.setup.updater.lsp

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.tom.rv2ide.setup.R
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.setup.updater.LspUpdateDialog
import com.tom.rv2ide.setup.updater.lsp.data.LSPProperties
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.LspServerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Manages the update workflow for the Kotlin Language Server Protocol (LSP) component.
 *
 * This class is responsible for:
 * - Checking for available updates by fetching a remote manifest
 * - Comparing remote versions against the current installed version
 * - Downloading update packages when updates are available
 * - Cleaning up existing installations before extracting new versions
 * - Extracting the downloaded ZIP archive to the designated server directory
 * - Cleaning up temporary download files after successful extraction
 * - Updating the version information in the properties file upon successful installation
 * - Displaying user dialogs throughout the update process
 *
 * The updater fetches version metadata from a GitHub-hosted manifest file and
 * coordinates the download and installation of new LSP server versions.
 *
 * @property context The Android context used for displaying UI elements and accessing file storage.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinLspUpdater(private val context: Context) {

    private companion object {
        const val TAG = "KotlinLspUpdater"
    }

    /**
     * Checks for available updates by fetching the remote manifest and comparing versions.
     *
     * This function performs a network request to retrieve the latest version information
     * from the remote manifest. If an update is available, it displays an update dialog
     * to the user.
     *
     * @param currentVersion The currently installed version of the Kotlin LSP server.
     * @param notifyOnFailure 检查失败时是否弹 Toast。默认 `false`——**启动时静默**：
     *   manifest 在 GitHub raw，国内设备实测 50% 概率 503（8 次里 4 次失败），
     *   每次启动都因为网络抖动弹一句「检查更新失败」纯属噪声。只有用户主动点
     *   「检查更新」时才传 `true`（那时必须有反馈，否则会被当成功能坏了）。
     * @param onResult Optional callback invoked with the update availability status and
     * the remote version string. The first parameter indicates whether an update is available,
     * and the second parameter contains the remote version string if available.
     */
    fun checkForUpdates(
        currentVersion: String,
        notifyOnFailure: Boolean = false,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Gitee 优先、GitHub 兜底；清单按 id 取而不是取第一个——
                // 旧实现在清单顺序变化时会拿到别的服务器。
                val entry = LspServerSource.fetch("Kotlin")
                if (entry != null) {
                    val updateAvailable = isUpdateAvailable(entry.version, currentVersion)
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(updateAvailable, entry.version)
                        if (updateAvailable) {
                            showUpdateDialog(entry)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(false, null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LSP manifest 检查失败（通知用户=$notifyOnFailure）", e)
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, null)
                    if (notifyOnFailure) {
                        Toast.makeText(context, context.getString(R.string.lsp_update_check_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Determines whether an update is available by comparing version strings.
     *
     * @param remoteVersion The version string from the remote manifest.
     * @param currentVersion The currently installed version string.
     * @return `true` if the remote version greater than the current version, `false` otherwise.
     */
    private fun isUpdateAvailable(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = remoteVersion.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split(".").mapNotNull { it.toIntOrNull() }
        
        val maxLength = maxOf(remoteParts.size, currentParts.size)
        
        for (i in 0 until maxLength) {
            val remotePart = remoteParts.getOrNull(i) ?: 0
            val currentPart = currentParts.getOrNull(i) ?: 0
            
            if (remotePart > currentPart) {
                return true
            } else if (remotePart < currentPart) {
                return false
            }
        }
        
        return false
    }

    /**
     * Displays the initial update dialog prompting the user to download the new version.
     *
     * This dialog presents the user with options to proceed with the update or cancel.
     * If the user chooses to update, the download process begins and progress is shown.
     *
     * @param fetchedVersion The version string of the available update.
     * @param downloadUrl The URL from which to download the update package, or `null` if unavailable.
     */
    private fun showUpdateDialog(entry: LspServerSource.Entry) {
        val fetchedVersion = entry.version
        val candidates = LspServerSource.downloadCandidates(entry)
        LspUpdateDialog(context)
            .setTitle(context.getString(R.string.lsp_update_available_title))
            .setDescription(context.getString(R.string.lsp_update_available_description, fetchedVersion))
            .setPrimaryButton(context.getString(R.string.lsp_update_button)) { dialog ->
                dialog.operate { updater ->
                    updater.updateTitle(context.getString(R.string.lsp_downloading_title))
                    updater.updateDescription(context.getString(R.string.lsp_initializing_download))
                    updater.updateProgress(0)

                    try {
                        val fileName = candidates.first().substringAfterLast("/")
                        val downloadDir = context.getExternalFilesDir(null)
                        val file = File(downloadDir, fileName)

                        var lastProgress = -1
                        val downloaded =
                            LspServerSource.download(candidates, file) { read, total ->
                                if (total > 0) {
                                    val progress = ((read * 100) / total).toInt()
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        // 回调在非 suspend 上下文里，updater 的方法是 suspend；
                                        // 用独立协程切回主线程调用（不能直接 withContext）。
                                        CoroutineScope(Dispatchers.Main).launch {
                                            updater.updateProgress(progress)
                                            updater.updateDescription(
                                                context.getString(
                                                    R.string.lsp_downloading_progress, progress))
                                        }
                                    }
                                }
                            }
                        if (!downloaded || !LspServerSource.verify(file, entry.sha256)) {
                            file.delete()
                            throw IOException(
                                "下载失败或校验不通过（已尝试 ${candidates.size} 个源）")
                        }

                        updater.updateProgress(100)
                        updater.updateTitle(context.getString(R.string.lsp_download_complete_title))
                        updater.updateDescription(context.getString(R.string.lsp_cleaning_old_installation))

                        cleanupOldInstallation(updater)

                        updater.updateDescription(context.getString(R.string.lsp_extracting_files))

                        extractZipFile(file.absolutePath, updater)

                        file.delete()

                        updateVersionInProperties(fetchedVersion)

                        updater.updateTitle(context.getString(R.string.lsp_installation_complete_title))
                        updater.updateDescription(context.getString(R.string.lsp_installation_complete_description, fetchedVersion))

                        kotlinx.coroutines.delay(1500)

                        withContext(Dispatchers.Main) {
                            showSuccessDialog(fetchedVersion)
                        }

                    } catch (e: Exception) {
                        updater.updateTitle(context.getString(R.string.lsp_installation_failed_title))
                        updater.updateDescription(context.getString(R.string.lsp_error_message, e.message))

                        kotlinx.coroutines.delay(2000)

                        withContext(Dispatchers.Main) {
                            showErrorDialog(e.message ?: context.getString(R.string.lsp_unknown_error), entry)
                        }
                    }
                }
            }
            .setSecondaryButton(context.getString(R.string.lsp_cancel_button)) { dialog ->
                dialog.dismiss()
            }
            .reveal()
    }

    /**
     * Cleans up the existing Kotlin LSP installation directory before installing a new version.
     *
     * This ensures that no residual files from previous installations remain, which could
     * cause conflicts or unexpected behavior with the new version.
     *
     * @param updater The dialog updater for displaying cleanup progress.
     */
    private fun cleanupOldInstallation(updater: LspUpdateDialog.ProgressUpdater) {
        val targetDir = File(Environment.HOME, "acs/servers/kotlin")
        
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
    }

    /**
     * Extracts the contents of a ZIP file to the Kotlin LSP server directory.
     *
     * The extraction target is `Environment.HOME/acs/servers/kotlin/`. The directory
     * is created if it does not exist.
     *
     * @param zipFilePath The path to the ZIP file to extract.
     * @param updater The dialog updater for displaying extraction progress.
     * @throws Exception if extraction fails.
     */
    private suspend fun extractZipFile(zipFilePath: String, updater: LspUpdateDialog.ProgressUpdater) {
        val targetDir = File(Environment.HOME, "acs/servers/kotlin")
        targetDir.mkdirs()

        ZipInputStream(File(zipFilePath).inputStream()).use { zipInputStream ->
            var entry = zipInputStream.nextEntry
            var fileCount = 0

            while (entry != null) {
                val entryFile = File(targetDir, entry.name)

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { output ->
                        zipInputStream.copyTo(output)
                    }
                    fileCount++
                    updater.updateDescription(context.getString(R.string.lsp_extracting_progress, fileCount))
                }

                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        }
    }

    /**
     * Updates the Kotlin LSP version in the properties file after a successful installation.
     *
     * This function writes or overwrites the `KotlinLspVersion` key in the properties file
     * located at `Environment.ACSIDE`. If the key already exists, its value is updated;
     * otherwise, the key is created with the new version value.
     *
     * @param version The new version string to write to the properties file.
     */
    private fun updateVersionInProperties(version: String) {
        try {
            LSPProperties.writePropertyValue(
                filePath = Environment.ACSIDE.toString(),
                key = "KotlinLspVersion",
                value = version
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Displays a success dialog after the update has been downloaded, extracted, and installed successfully.
     *
     * @param version The version string of the successfully installed update.
     */
    private fun showSuccessDialog(version: String) {
        LspUpdateDialog(context)
            .setTitle(context.getString(R.string.lsp_update_success_title))
            .setDescription(context.getString(R.string.lsp_update_success_description, version))
            .setPrimaryButton(context.getString(R.string.lsp_ok_button)) { dialog ->
                dialog.dismiss()
            }
            .reveal()
    }

    /**
     * Displays an error dialog when the download or installation fails.
     *
     * This dialog provides the user with an option to retry the download or cancel.
     *
     * @param error The error message describing why the installation failed.
     * @param entry 失败时使用的清单条目（重试直接复用，避免重新拉清单）。
     */
    private fun showErrorDialog(error: String, entry: LspServerSource.Entry) {
        LspUpdateDialog(context)
            .setTitle(context.getString(R.string.lsp_installation_failed_title))
            .setDescription(context.getString(R.string.lsp_installation_failed_description, error))
            .setPrimaryButton(context.getString(R.string.lsp_retry_button)) { dialog ->
                dialog.dismiss()
                showUpdateDialog(entry)
            }
            .setSecondaryButton(context.getString(R.string.lsp_cancel_button)) { dialog ->
                dialog.dismiss()
            }
            .reveal()
    }
}