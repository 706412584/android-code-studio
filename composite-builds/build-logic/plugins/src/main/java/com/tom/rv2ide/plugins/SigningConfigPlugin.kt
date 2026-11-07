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

package com.tom.rv2ide.plugins

import com.android.build.api.dsl.CommonExtension
import com.tom.rv2ide.build.config.SigningConfig
import com.tom.rv2ide.build.config.loadSigningProperties
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Configures the signing keys to application modules.
 *
 * @author Akash Yadav
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class SigningConfigPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.run {
      val signing = findSigningConfiguration()
      if (signing == null) {
        return
      }

      val signingKey = file(signing.storeFile)
      if (!signingKey.exists()) {
        return
      }

      // Create and apply the signing config。
      // AGP 9 里 BaseExtension 不再是注册的扩展类型（注册的是 LibraryExtensionImpl /
      // ApplicationExtensionImpl），改用 CommonExtension——getSigningConfigs() /
      // getBuildTypes() 两版都有。
      extensions.getByType(CommonExtension::class.java).let { extension ->
        val config =
            extension.signingConfigs.create("common") {
              storeFile = signingKey
              keyAlias = signing.keyAlias
              storePassword = signing.storePassword
              keyPassword = signing.keyPassword
            }

        // 给每个 buildType 设签名。**必须走反射**：AGP 9 把 `signingConfig` 从其
        // 公开接口 `com.android.build.api.dsl.BuildType` 移除了（只剩模块级的
        // ApplicationVariantDimension/LibraryVariantDimension 有），而
        // `extension.buildTypes` 在编译期返回的是那个公开接口，直接写
        // `buildType.signingConfig = config` 会报 Unresolved reference。
        // 但运行时实现类（android.build.gradle.internal.dsl.BuildType，旧 DSL，
        // 因 android.newDsl=false 仍在用）**仍有** setSigningConfig——两版 AGP 都有。
        // 故反射调用，兼容 AGP 8 与 9。
        //
        // 选取必须**确定性**：AGP 9.2.0 的实现类有 5 个 arity-1 的 setSigningConfig
        // 重载（ApkSigningConfig / builder.model.SigningConfig / internal.dsl.SigningConfig /
        // internal.dsl.InternalSigningConfig / Object），而 `Class.getMethods()` 的顺序
        // JVM 未定义。这里显式要求参数类型为公开的 ApkSigningConfig，绕开顺序依赖。
        //
        // 找不到时**必须报错**，不能像以前那样静默 warn 后跳过：漏设签名会让 APK 变成
        // 未签名（或沿用默认签名），用户无法覆盖安装，且完全静默。宁可构建失败。
        val expectedParam = "com.android.build.api.dsl.ApkSigningConfig"
        extension.buildTypes.forEach { buildType ->
          val setter =
              buildType.javaClass.methods.firstOrNull {
                it.name == "setSigningConfig" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name == expectedParam
              }
          if (setter == null) {
            throw GradleException(
                "buildType '${buildType.name}' 找不到 setSigningConfig($expectedParam)。" +
                    "AGP 的签名 API 可能已变更——继续构建会产出无法覆盖安装的包，故中止。")
          }
          setter.invoke(buildType, config)
        }

        logger.lifecycle("Signing configured successfully for all build types")
        logger.lifecycle("Key store: ${signingKey.absolutePath}")
        logger.lifecycle("Key alias: ${signing.keyAlias}")
      }
    }
  }

  private fun Project.findSigningConfiguration(): SigningConfig? {
    val envStoreFile = System.getenv("SIGNING_STORE_FILE") ?: "signing/signing-key.jks"
    val envStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    val envKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    val envKeyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "AndroidCS"

    if (envStorePassword != null && envKeyPassword != null) {
      return SigningConfig(
          storeFile = envStoreFile,
          storePassword = envStorePassword,
          keyPassword = envKeyPassword,
          keyAlias = envKeyAlias,
      )
    }

    val gradleStoreFile = findProperty("signingStoreFile") as? String ?: "signing/signing-key.jks"
    val gradleStorePassword = findProperty("signingStorePassword") as? String
    val gradleKeyPassword = findProperty("signingKeyPassword") as? String
    val gradleKeyAlias = findProperty("signingKeyAlias") as? String ?: "AndroidCS"

    if (gradleStorePassword != null && gradleKeyPassword != null) {
      return SigningConfig(
          storeFile = gradleStoreFile,
          storePassword = gradleStorePassword,
          keyPassword = gradleKeyPassword,
          keyAlias = gradleKeyAlias,
      )
    }

    val localSigning = loadSigningProperties()
    if (localSigning != null) {
      logger.lifecycle("Using signing configuration from local.properties")
    }

    return localSigning
  }
}
