/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.handlers.system.installer

import android.util.Log
import com.tom.rv2ide.utils.Environment
import java.io.File

/**
 * 用官方 **`sdkmanager`** 安装 SDK 组件（build-tools / platforms）。
 *
 * <p><b>为什么弃用 `idesetup --sdk`</b>：那走的是 AndroidIDE 的镜像 manifest，其
 * build-tools 包**最高只到 35.0.1**（上游从未构建更高版本），而 AGP 9.x 要求
 * build-tools ≥ 36.0.0——镜像链路给不了。实测设备上的 `sdkmanager`（随 cmdline-tools
 * 一起装的官方工具）**能直连 Google 仓库**，可列出并安装 build-tools 至 37.0.0、
 * platforms 至 android-36，且是官方源、不依赖镜像同步。
 *
 * <p><b>调用方式</b>：`sdkmanager` 的 shebang 是 `#!/usr/bin/env sh`，而 Android 上没有
 * `/usr/bin/env`，**直接执行会报 "No such file or directory"**，必须 `sh <path>` 调起。
 * 这也正是这里用 ProcessBuilder 显式传 `[sh, sdkmanager, ...args]` 的原因。
 *
 * <p><b>JAVA_HOME 必需</b>：sdkmanager 是 Java 程序，环境里没有 JAVA_HOME 会启动失败。
 * 优先用随 IDE 分发的 JDK（`$PREFIX/lib/jvm/java-17-openjdk`），找不到再扫 lib/jvm 下的 jdk 目录。
 */
class SdkManagerInstaller {

  companion object {
    private const val TAG = "SdkManagerInstaller"

    /** sdkmanager 可执行文件（随 cmdline-tools 安装；不存在说明还没做过首次引导）。 */
    @JvmStatic
    fun sdkManagerFile(): File? =
        File(Environment.ANDROID_HOME, "cmdline-tools/latest/bin/sdkmanager").takeIf {
          it.exists()
        }

    /** 随 IDE 分发的 JDK；找不到返回 null（调用方据此报错而非静默失败）。 */
    @JvmStatic
    fun javaHome(): File? {
      val preferred = File(Environment.PREFIX, "lib/jvm/java-17-openjdk")
      if (preferred.isDirectory) return preferred
      // 兜底：lib/jvm 下任意一个 jdk 目录（版本变了也不至于挂）。
      return File(Environment.PREFIX, "lib/jvm")
          .listFiles()
          ?.firstOrNull { it.isDirectory && it.name.contains("jdk") }
    }

    /** platforms 目录里已安装的 API level（如 "android-36"）。 */
    @JvmStatic
    fun installedPlatforms(): List<String> =
        File(Environment.ANDROID_HOME, "platforms")
            .listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** build-tools 目录里已安装的版本（如 "36.0.0"）。 */
    @JvmStatic
    fun installedBuildTools(): List<String> =
        File(Environment.ANDROID_HOME, "build-tools")
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
  }

  /**
   * 跑 `sdkmanager --list` 拉**可用**包，解析成 build-tools / platforms 两个列表。
   *
   * <p>只用正式版（过滤掉 `-rc` 与 `build-tools;34.0.0` 这种无小版本的同名项由调用方去重）。
   * 网络不通时返回 null（调用方回退到"只显示已安装"）。
   */
  fun listAvailable(onOutput: ((String) -> Unit)? = null): AvailableSdk? {
    val lines = mutableListOf<String>()
    val code = runSdkManager(listOf("--list"), null) { line ->
      lines.add(line)
      onOutput?.invoke(line)
    }
    if (code != 0) {
      Log.e(TAG, "sdkmanager --list failed, exit=$code")
      return null
    }
    val text = lines.joinToString("\n")
    val buildTools =
        Regex("""build-tools;([0-9][0-9.]*)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .filterNot { it.contains("rc") }
            .distinct()
            .sortedWith(versionComparator)
            .toList()
    val platforms =
        Regex("""platforms;android-([0-9]+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 0 }
            .toList()
    return AvailableSdk(buildTools, platforms)
  }

  /**
   * 安装一个包（如 `build-tools;36.0.0` / `platforms;android-36`）。
   *
   * <p>先接受许可（`--licenses` 需要交互，这里用 `yes` 管道喂），再安装——首次用
   * sdkmanager 时若许可未接受会直接失败。
   */
  fun install(packageId: String, onOutput: (String) -> Unit): Boolean {
    acceptLicenses(onOutput)
    val code = runSdkManager(listOf("--install", packageId), packageId) { onOutput(it) }
    Log.d(TAG, "install $packageId exit=$code")
    return code == 0
  }

  private fun acceptLicenses(onOutput: (String) -> Unit) {
    // 许可已由调用方在首装时接受过时这里会立刻返回，代价很小。
    runSdkManager(listOf("--licenses"), null) { onOutput(it) }
  }

  /**
   * 跑一次 sdkmanager。返回退出码（失败返回 -1）。
   *
   * @param args sdkmanager 的参数（不含可执行文件本身）
   * @param answer 需要交互应答时喂给 stdin 的行（如许可提示的 "y"）；null 表示不喂
   */
  private fun runSdkManager(
      args: List<String>,
      answer: String?,
      onOutput: (String) -> Unit,
  ): Int {
    val sm = sdkManagerFile()
    if (sm == null) {
      onOutput("[错误] 找不到 sdkmanager（cmdline-tools 未安装）。请先完成首次设置。")
      return -1
    }
    val jdk = javaHome()
    if (jdk == null) {
      onOutput("[错误] 找不到 JDK，无法运行 sdkmanager。")
      return -1
    }
    val sh = shell()
    val cmd = listOf(sh.absolutePath, sm.absolutePath) + args
    return try {
      val pb = ProcessBuilder(cmd)
      val env = pb.environment()
      env["HOME"] = Environment.HOME.absolutePath
      env["PREFIX"] = Environment.PREFIX.absolutePath
      env["JAVA_HOME"] = jdk.absolutePath
      env["PATH"] =
          listOf(
                  Environment.BIN_DIR.absolutePath,
                  File(Environment.ANDROID_HOME, "cmdline-tools/latest/bin").absolutePath,
                  "/system/bin",
              )
              .joinToString(":")
      env["LD_LIBRARY_PATH"] = Environment.LIB_DIR.absolutePath
      env["TMPDIR"] = Environment.TMP_DIR.absolutePath
      pb.redirectErrorStream(true)

      val process = pb.start()
      if (answer != null) {
        process.outputStream.bufferedWriter().use { w ->
          repeat(50) { w.write("$answer\n") }
        }
      } else {
        // 不喂应答但进程可能等 stdin，显式关掉避免挂住。
        runCatching { process.outputStream.close() }
      }
      process.inputStream.bufferedReader().useLines { lines -> lines.forEach { onOutput(it) } }
      process.waitFor()
    } catch (e: Exception) {
      Log.e(TAG, "runSdkManager failed: ${args.joinToString(" ")}", e)
      onOutput("[错误] ${e.message}")
      -1
    }
  }

  private fun shell(): File =
      when {
        Environment.LOGIN_SHELL.exists() -> Environment.LOGIN_SHELL
        Environment.BASH_SHELL.exists() -> Environment.BASH_SHELL
        else -> File("/system/bin/sh")
      }

  /** `sdkmanager --list` 解析结果。 */
  data class AvailableSdk(val buildTools: List<String>, val platforms: List<String>)

  private val versionComparator = Comparator<String> { a, b ->
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
      val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
      if (c != 0) return@Comparator c
    }
    0
  }
}
