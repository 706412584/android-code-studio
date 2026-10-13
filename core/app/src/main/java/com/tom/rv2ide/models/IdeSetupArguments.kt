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

package com.tom.rv2ide.models

/**
 * Configuration options for IDESetup
 *
 * @property argumentName The name of the argument.
 * @property requiresValue Whether the argument must have a value.
 * @author Akash Yadav
 */
enum class IdeSetupArgument(val argumentName: String, val requiresValue: Boolean = false) {
  INSTALL_DIR("--install-dir", true),
  WITH_GIT("--with-git"),
  ASSUME_YES("--assume-yes"),
  WITH_OPENSSH("--with-openssh"),
  SDK_VERSION("--sdk", true),
  JDK_VERSION("--jdk", true),
  NDK_VERSION("--ndk", false),

  /**
   * manifest 文件地址（工具链下载源清单）。
   *
   * <p>默认 manifest 与各包全部托管在 GitHub（`AndroidIDE-Rv2Official` 与
   * `AndroidIDEOfficial` 两个仓库）——国内设备实测 build-tools 包下载连接
   * `github.com` 超时（3 次重试全部 15s 0 字节），首装卡死。Gitee 镜像
   * manifest 把 build-tools 的 aarch64/arm URL 指向 Gitee Release（实测
   * 1.6MB/s），其余条目保持上游（SDK/cmdline-tools 的 GitHub 下载实测可用）。
   *
   * <p>idesetup 的 `-m`/`--manifest` 参数实测均生效（见设备验证记录）。
   */
  MANIFEST("--manifest", true),
}
