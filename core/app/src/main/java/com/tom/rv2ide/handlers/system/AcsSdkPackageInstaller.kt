/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.handlers.system

import android.content.Context
import com.tom.rv2ide.activities.IdeConfigurations.AcsCommandInterface
import com.tom.rv2ide.activities.IdeConfigurations.AcsProvider
import com.tom.rv2ide.activities.IdeConfigurations.getCpuArchitecture
import com.tom.rv2ide.handlers.system.installer.IPackageInstaller
import com.tom.rv2ide.utils.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 ACS Provider（Gitee 镜像清单）安装 NDK / CMake。
 *
 * <p><b>为什么需要它</b>：SDK 管理器（[com.tom.rv2ide.handlers.system.installer.SdkManagerInstaller]）
 * 走官方 `sdkmanager` + `dl.google.com`，仓库地址不可注入；国内下载失败时没有换源点。
 * 而 Gitee 上已有 `android-native-kit`（NDK）与 `android-cmake` 的分卷包，IDE 配置页
 * 通过 [AcsProvider] 已经在用同一条链路。这里把它做成非 UI 函数，供 SDK 管理器在
 * sdkmanager 失败后兜底。
 *
 * <p><b>布局对齐</b>：资产解压后是单个顶层目录（如 `android-ndk-r28`），重命名成
 * `<version>` 后即与 sdkmanager 的 `$SDK/ndk/<version>` 一致。
 */
object AcsSdkPackageInstaller {

  /** SDK 组件 id → ACS 清单里的包 id。 */
  private val ACS_PACKAGE_IDS = mapOf("ndk" to "android-native-kit", "cmake" to "android-cmake")

  /**
   * @return true 表示安装完成且目标目录已就位
   */
  suspend fun install(
      context: Context,
      componentId: String,
      version: String,
      onOutput: (String) -> Unit,
  ): Boolean =
      withContext(Dispatchers.IO) {
        val packageId = ACS_PACKAGE_IDS[componentId] ?: return@withContext false
        val architecture =
            when (getCpuArchitecture()) {
              "armeabi-v7a" -> AcsCommandInterface.Architecture.ARM_V7A
              "arm64-v8a" -> AcsCommandInterface.Architecture.ARM64_V8A
              "x86_64" -> AcsCommandInterface.Architecture.X86_64
              else -> {
                onOutput("[ACS] 不支持的架构：${getCpuArchitecture()}")
                return@withContext false
              }
            }

        try {
          onOutput("[ACS] 读取 $packageId $version 的包信息…")
          val filenameResult =
              AcsCommandInterface.getPackageField(
                  manifestUrl = AcsProvider.getManifestUrl,
                  architecture = architecture,
                  packageId = packageId,
                  field = AcsCommandInterface.ManifestField.FILENAME,
                  version = version,
              )
          if (!filenameResult.success) {
            onOutput("[ACS] 获取文件名失败：${filenameResult.errorOutput}")
            return@withContext false
          }
          val filename = filenameResult.output.trim()
          if (filename.isEmpty()) {
            onOutput("[ACS] 清单未提供文件名")
            return@withContext false
          }

          onOutput("[ACS] 从镜像下载 $filename…")
          val downloadResult = AcsProvider.acsRunner(packageId, version, architecture)
          if (!downloadResult.output.contains("successfully", ignoreCase = true)) {
            onOutput("[ACS] 下载失败：${downloadResult.output} ${downloadResult.errorOutput}")
            return@withContext false
          }

          val targetParent = File(Environment.HOME, "android-sdk/$componentId")
          targetParent.mkdirs()
          val archive = File(File(Environment.HOME, "acs"), filename)
          if (!archive.isFile) {
            onOutput("[ACS] 下载文件不存在：${archive.absolutePath}")
            return@withContext false
          }

          onOutput("[ACS] 解压到 ${targetParent.absolutePath}…")
          IPackageInstaller().extractXzArchive(archive.absolutePath, targetParent.absolutePath)

          // 解压产物通常是单个顶层目录（android-ndk-r28 / cmake-*），重命名到版本号。
          val children = targetParent.listFiles()?.filter { it.isDirectory }.orEmpty()
          if (children.size == 1 && children[0].name != version) {
            val renamed = File(targetParent, version)
            if (!children[0].renameTo(renamed)) {
              onOutput("[ACS] 目录重命名失败：${children[0].name} -> $version")
              return@withContext false
            }
          }

          val installed = File(targetParent, version).isDirectory
          onOutput(if (installed) "[ACS] 安装完成：$componentId $version" else "[ACS] 安装后未找到目标目录")
          installed
        } catch (e: Exception) {
          onOutput("[ACS] 安装异常：${e.message}")
          false
        }
      }
}