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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.dart

import com.tom.rv2ide.lsp.common.ProcessLanguageServer
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.nio.file.Path

/**
 * Dart 语言服务器（Flutter 模板的主用服务器）。
 *
 * <p>直接用 Dart SDK 自带的 analysis_server 快照——补全/诊断/跳转/格式化由 Dart
 * 官方分析器提供，与 Flutter 工具链同源（`flutter analyze` 用的就是它）。
 *
 * <h3>启动链路（已在本机设备实测）</h3>
 *
 * ```
 * <dart-sdk>/bin/dart <dart-sdk>/bin/snapshots/analysis_server.dart.snapshot \
 *     --lsp --client-id=acs --client-version=1.0
 * ```
 *
 * <p>SDK 是 bionic ELF（NDK r27c 构建），可直接在 Android 上执行——`dart --version`
 * 实测输出 `Dart SDK version: 3.7.2 (stable) on "linux_arm64"`。不需要 Termux 的
 * 任何依赖（不像 TS 需要 node）。
 *
 * <h3>SDK 从哪来（分发决策）</h3>
 *
 * 169MB 的完整 SDK 不能进 APK，选择**按需下载**：包（67MB，压缩后）托管在
 * Gitee Release（先例：CodeGraph 组件包走同一通道，理由同为国内设备直连 GitHub
 * 不可靠），用户在 Flutter 项目首次打开 .dart 文件时收到引导提示，
 * 经设置页/提示一键下载安装到 `$HOME/acs/dart-sdk`（Termux 前缀之外，
 * 理由同 CodeGraphInstaller：apt 管理不了的外部文件不放进 $PREFIX）。
 * 安装逻辑见 app 层 `DartSdkInstaller`。
 *
 * <p>SDK 不存在时**不崩溃、不静默**：弹一次可读提示（含获取方式），编辑器保持
 * 纯文本模式。装好后重开项目即生效。
 */
class DartLanguageServer : ProcessLanguageServer() {

  companion object {

    const val SERVER_ID = "dart"

    /** 快照相对 SDK 根的路径。 */
    private const val SNAPSHOT_RELATIVE_PATH = "bin/snapshots/analysis_server.dart.snapshot"

    /** dart 可执行文件相对 SDK 根的路径。 */
    private const val DART_RELATIVE_PATH = "bin/dart"

    /**
     * SDK 安装根目录：`$HOME/acs/dart-sdk`。
     *
     * <p>与 CodeGraphInstaller 同一决策：不放进 `$PREFIX`（apt 管不到的外部
     * 文件会让 dpkg 报未知文件）；放 HOME 下卸载就是删目录。解包后的顶层目录
     * 就叫 `dart-sdk`，与本路径拼接后正好是 SDK 根。
     */
    const val SDK_DIR_NAME = "acs/dart-sdk"

    const val CLIENT_ID = "acs"
    const val CLIENT_VERSION = "1.0"

    /** SDK 未安装时的用户提示（含获取方式）。 */
    const val INSTALL_HINT =
        "Dart SDK 未安装。Flutter 项目的代码补全与诊断需要它（约 67MB）。\n" +
            "请在 设置 → 编辑器 → Dart 语言服务器 中下载，或手动解压到 " +
            "\$HOME/acs/dart-sdk。"
  }

  override val serverId: String = SERVER_ID

  override val fileExtensions: Set<String> = setOf("dart")

  override val unavailableMessage: String = INSTALL_HINT

  override fun languageIdFor(file: Path): String = "dart"

  /** SDK 根目录。 */
  private fun sdkDir(): File = File(Environment.HOME, SDK_DIR_NAME)

  override fun createCommand(fallbackFile: Path?): List<String>? {
    val sdk = sdkDir()
    val dart = File(sdk, DART_RELATIVE_PATH)
    val snapshot = File(sdk, SNAPSHOT_RELATIVE_PATH)
    if (!dart.isFile || !snapshot.isFile) {
      log.error(
          "Dart SDK not found: dart={} exists={}, snapshot={} exists={}",
          dart.absolutePath,
          dart.isFile,
          snapshot.absolutePath,
          snapshot.isFile)
      return null
    }
    return listOf(
        dart.absolutePath,
        snapshot.absolutePath,
        "--lsp",
        "--client-id=$CLIENT_ID",
        "--client-version=$CLIENT_VERSION",
    )
  }

  /**
   * 工作目录取项目根——分析服务器用它与 rootUri 定位 `pubspec.yaml` 和
   * `analysis_options.yaml`。指向别处会让包解析失败（`package:flutter/...`
   * 全部报未解析）。
   */
  override fun workingDirectory(fallbackFile: Path?): File {
    return projectRootDirOrNull() ?: fallbackFile?.parent?.toFile() ?: Environment.HOME
  }
}
