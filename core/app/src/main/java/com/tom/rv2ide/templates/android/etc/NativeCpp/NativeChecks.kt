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

package com.tom.rv2ide.templates.android.etc.NativeCpp

import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.GeneralFileUtils
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

object Check {

  /** Returns all installed NDK versions sorted from highest to lowest. */
  fun getAllNdkVersions(): List<String> {
    val ndkDir = File(Environment.HOME, "android-sdk/ndk")
    if (!ndkDir.exists() || !ndkDir.isDirectory) return emptyList()

    return GeneralFileUtils.listDirsInDirectory(ndkDir)
        .map { it.name to versionStringToList(it.name) }
        .filter { it.second.isNotEmpty() }
        .sortedWith(
            Comparator { a, b ->
              compareVersionLists(b.second, a.second) // reversed for descending
            }
        )
        .map { it.first }
  }

  /**
   * Returns the highest installed NDK version as a string, e.g. "27.3.13750724", or null if no NDK
   * versions are found.
   */
  fun getHighestNdkVersion(): String? = getAllNdkVersions().firstOrNull()

  /**
   * Returns the highest installed CMake version as a string, e.g. "3.22.1", or null if no CMake
   * versions are found.
   */
  fun getHighestCMakeVersion(): String? = getAllCMakeVersions().firstOrNull()

  /**
   * 取**当前设备上真正能运行**的最高 CMake 版本。
   *
   * <p><b>为什么不能直接用 [getHighestCMakeVersion]</b>：那个只按目录名排序，不校验二进制。
   * SDK 仓库里的 CMake 包是分架构的，而 `sdkmanager` 在某些设备/旧版 cmdline-tools 上会
   * 装到与设备不匹配的架构。实测（黑鲨 SKW-A0，aarch64）：
   *
   * ```
   * cmake/3.22.1/bin/cmake: ELF 64-bit LSB x86-64   ← 手机上无法执行
   * cmake/4.1.2/bin/cmake:  ELF 64-bit LSB arm64    ← 正常
   * ```
   *
   * 而 3.22.1 的版本号更高排序在前，于是模板把 `version '3.22.1'` 写进 build.gradle，
   * AGP 照此调用 x86-64 的二进制，构建以 `not executable: 64-bit ELF file` 失败。
   * 这个错误看起来像「NDK/SDK 版本冲突」，实际是架构不匹配。
   *
   * <p>判据用「能不能跑起来」而不是「目录是否存在」——[validateCMakeVersion] 真的执行
   * `cmake --version` 并检查退出码，这是唯一可靠的判据。
   *
   * <p>全部版本都不可运行时返回 null，由调用方给出可操作的提示，而不是写一个必然失败的
   * 版本号进 build.gradle 让用户去猜。
   */
  fun getHighestRunnableCMakeVersion(): String? =
      getAllCMakeVersions().firstOrNull { validateCMakeVersion(it) != null }

  /**
   * 目录存在、但二进制在本设备上跑不起来的 CMake 版本。
   *
   * <p>用于给用户一条**可操作**的错误信息：只报「CMake 不可用」会让人以为一个都没装，
   * 而实际上他装了好几个、只是架构不对（见 [getHighestRunnableCMakeVersion] 的说明）。
   * 把版本号列出来，用户才知道该删哪个或该换装哪个。
   *
   * <p>返回空列表表示「装了但都正常」或「一个都没装」——两者都不需要额外提示。
   */
  fun getBrokenCMakeVersions(): List<String> =
      getAllCMakeVersions().filter { validateCMakeVersion(it) == null }

  /** Checks if at least one NDK is installed. */
  fun isAtLeastOneInstalled(): Boolean = getAllNdkVersions().isNotEmpty()

  /** Validates a specific NDK version by running clang -v. */
  fun validateNdkVersion(version: String): Boolean {
    val ndkPath = File(Environment.HOME, "android-sdk/ndk/$version")
    val clangPath = findClangExecutable(ndkPath) ?: return false

    return try {
      val process = ProcessBuilder(clangPath.absolutePath, "-v").redirectErrorStream(true).start()

      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroy()
        false
      } else {
        val output = process.inputStream.bufferedReader().readText()
        process.exitValue() == 0 || output.contains("clang version")
      }
    } catch (e: Exception) {
      false
    }
  }

  /** Finds all CMake installations in android-sdk/cmake directory. */
  fun getAllCMakeVersions(): List<String> {
    val cmakeDir = File(Environment.HOME, "android-sdk/cmake")
    if (!cmakeDir.exists() || !cmakeDir.isDirectory) return emptyList()

    return GeneralFileUtils.listDirsInDirectory(cmakeDir)
        .map { it.name to versionStringToList(it.name) }
        .filter { it.second.isNotEmpty() }
        .sortedWith(
            Comparator { a, b ->
              compareVersionLists(b.second, a.second) // reversed for descending
            }
        )
        .map { it.first }
  }

  /** Validates a specific CMake version by running cmake --version. */
  fun validateCMakeVersion(version: String): String? {
    val cmakePath = findCMakeExecutable(version) ?: return null

    return try {
      val process =
          ProcessBuilder(cmakePath.absolutePath, "--version").redirectErrorStream(true).start()

      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroy()
        null
      } else {
        if (process.exitValue() == 0) cmakePath.absolutePath else null
      }
    } catch (e: Exception) {
      null
    }
  }

  /** Finds the cmake binary for a given version. */
  private fun findCMakeExecutable(version: String): File? {
    val cmakeDir = File(Environment.HOME, "android-sdk/cmake/$version/bin")
    if (!cmakeDir.exists()) return null

    val cmakeFile = File(cmakeDir, "cmake")
    return if (cmakeFile.exists() && cmakeFile.canExecute()) cmakeFile else null
  }

  /**
   * Converts a version string like "27.3.13750724" into a list of integers [27, 3, 13750724] for
   * proper numerical comparison.
   */
  private fun versionStringToList(version: String): List<Int> =
      version.split('.').mapNotNull { it.toIntOrNull() }

  /**
   * Compares two version lists element by element. Returns: negative if v1 < v2, positive if v1 >
   * v2, zero if equal
   */
  private fun compareVersionLists(v1: List<Int>, v2: List<Int>): Int {
    val minSize = minOf(v1.size, v2.size)
    for (i in 0 until minSize) {
      val cmp = v1[i].compareTo(v2[i])
      if (cmp != 0) return cmp
    }
    return v1.size.compareTo(v2.size)
  }

  /** Finds the clang binary inside the NDK directory. */
  private fun findClangExecutable(ndkPath: File): File? {
    val prebuiltDir = File(ndkPath, "toolchains/llvm/prebuilt")
    if (!prebuiltDir.exists()) return null

    prebuiltDir.walkTopDown().forEach { file ->
      if (file.isFile && file.name == "clang") return file
    }
    return null
  }
}
