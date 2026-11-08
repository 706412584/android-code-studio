/*
 *  This file is part of AndroidTC.
 *
 *  AndroidTC is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidTC is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with AndroidTC.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 ** @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

package com.tom.androidcodestudio.project.manager.builder.toplevel

import java.io.File

/** Enum representing supported Settings Gradle file types. */
enum class SettingsGradleFileType(val extension: String, val fileName: String) {
  GROOVY("gradle", "settings.gradle"),
  KTS("gradle.kts", "settings.gradle.kts"),
}

/**
 * Data class representing the settings.gradle configuration.
 *
 * @property pluginManagementBody Custom body content for pluginManagement block
 * @property dependencyResolutionBody Custom body content for dependencyResolutionManagement block
 * @property rootProjectName The root project name
 * @property includes List of modules to include (e.g., ":app", ":feature:auth")
 */
data class SettingsGradleConfig(
    val pluginManagementBody: String? = null,
    val dependencyResolutionBody: String? = null,
    val rootProjectName: String,
    val includes: List<String> = emptyList(),
)

/** Interface for writing settings.gradle files. */
interface TLSettingsGradle {

  /**
   * Generates the settings.gradle file content.
   *
   * @param fileType The type of settings file (Groovy or KTS)
   * @param config The settings configuration
   * @return The generated settings file content as a String
   */
  fun generate(fileType: SettingsGradleFileType, config: SettingsGradleConfig): String

  /**
   * Writes the generated content to a file.
   *
   * @param outputDir The directory where the settings file will be created
   * @param fileType The type of settings file (Groovy or KTS)
   * @param config The settings configuration
   * @return The created File object
   */
  fun writeToFile(
      outputDir: File,
      fileType: SettingsGradleFileType,
      config: SettingsGradleConfig,
  ): File
}

/** Default implementation of TLSettingsGradle for generating settings.gradle files. */
class SettingsGradleWriter : TLSettingsGradle {

  override fun generate(fileType: SettingsGradleFileType, config: SettingsGradleConfig): String {
    return when (fileType) {
      SettingsGradleFileType.GROOVY -> generateGroovy(config)
      SettingsGradleFileType.KTS -> generateKts(config)
    }
  }

  override fun writeToFile(
      outputDir: File,
      fileType: SettingsGradleFileType,
      config: SettingsGradleConfig,
  ): File {
    if (!outputDir.exists()) {
      outputDir.mkdirs()
    }

    val content = generate(fileType, config)
    val file = File(outputDir, fileType.fileName)
    file.writeText(content)

    return file
  }

  /** Generates Groovy-style settings.gradle content. */
  private fun generateGroovy(config: SettingsGradleConfig): String {
    val builder = StringBuilder()

    // pluginManagement block
    if (config.pluginManagementBody != null) {
      builder.appendLine("pluginManagement {")
      builder.appendLine(config.pluginManagementBody.trimIndent().prependIndent("    "))
      builder.appendLine("}")
      builder.appendLine()
    }

    // dependencyResolutionManagement block
    if (config.dependencyResolutionBody != null) {
      builder.appendLine("dependencyResolutionManagement {")
      builder.appendLine(config.dependencyResolutionBody.trimIndent().prependIndent("    "))
      builder.appendLine("}")
      builder.appendLine()
    }

    // rootProject.name
    builder.appendLine("rootProject.name = \"${config.rootProjectName}\"")

    // include modules
    if (config.includes.isNotEmpty()) {
      builder.appendLine()
      config.includes.forEach { module -> builder.appendLine("include(\"$module\")") }
    }

    return builder.toString()
  }

  /** Generates Kotlin DSL-style settings.gradle.kts content. */
  private fun generateKts(config: SettingsGradleConfig): String {
    val builder = StringBuilder()

    // pluginManagement block
    if (config.pluginManagementBody != null) {
      builder.appendLine("pluginManagement {")
      builder.appendLine(config.pluginManagementBody.trimIndent().prependIndent("    "))
      builder.appendLine("}")
      builder.appendLine()
    }

    // dependencyResolutionManagement block
    if (config.dependencyResolutionBody != null) {
      builder.appendLine("dependencyResolutionManagement {")
      builder.appendLine(config.dependencyResolutionBody.trimIndent().prependIndent("    "))
      builder.appendLine("}")
      builder.appendLine()
    }

    // rootProject.name
    builder.appendLine("rootProject.name = \"${config.rootProjectName}\"")

    // include modules
    if (config.includes.isNotEmpty()) {
      builder.appendLine()
      config.includes.forEach { module -> builder.appendLine("include(\"$module\")") }
    }

    return builder.toString()
  }
}

/** Builder class for creating SettingsGradleConfig instances with a fluent API. */
class SettingsGradleConfigBuilder {
  private var pluginManagementBody: String? = null
  private var dependencyResolutionBody: String? = null
  private var rootProjectName: String = ""
  private val includes: MutableList<String> = mutableListOf()

  fun pluginManagement(body: String) = apply { this.pluginManagementBody = body }

  fun dependencyResolution(body: String) = apply { this.dependencyResolutionBody = body }

  fun rootProjectName(name: String) = apply { this.rootProjectName = name }

  fun include(vararg modules: String) = apply { this.includes.addAll(modules) }

  fun include(modules: List<String>) = apply { this.includes.addAll(modules) }

  fun build(): SettingsGradleConfig {
    require(rootProjectName.isNotBlank()) { "Root project name cannot be blank" }
    return SettingsGradleConfig(
        pluginManagementBody = pluginManagementBody,
        dependencyResolutionBody = dependencyResolutionBody,
        rootProjectName = rootProjectName,
        includes = includes.toList(),
    )
  }
}

/** DSL function for creating SettingsGradleConfig instances. */
fun settingsGradleConfig(block: SettingsGradleConfigBuilder.() -> Unit): SettingsGradleConfig {
  return SettingsGradleConfigBuilder().apply(block).build()
}

/** Helper object with predefined common repository configurations. */
object RepositoryPresets {

  /**
   * 阿里云镜像前置的三个仓库。
   *
   * <p><b>为什么需要</b>：`dl.google.com`（google()）与 `repo1.maven.org`（mavenCentral()）
   * 在国内不可达/高延迟，新建项目首次 sync 会卡在解析 AGP 与 Kotlin 插件。
   * CMP 模板早因依赖体量大（KGP+CMP 约 300MB）加过镜像，实测「阿里云镜像前置后
   * sync 成功」；这里推广到全部模板。
   *
   * <p><b>镜像在前、官方在后</b>：命中镜像走国内快路，未命中回落官方，
   * 因此对可用性零影响——镜像挂了只是慢，不会失败。
   *
   * <p>三者的分工（均实测）：`repository/google` ← google()（AGP、AndroidX）；
   * `repository/central` ← mavenCentral()（Kotlin、第三方）；
   * `repository/gradle-plugin` ← gradlePluginPortal()。
   * 注意 `gradle-plugin` 里**没有** AGP 9.2.0（实测 404，AGP 只在 google 下）。
   */
  private const val MIRROR_GRADLE_PLUGIN =
      "maven { url = uri(\"https://maven.aliyun.com/repository/gradle-plugin\") }"

  private const val MIRROR_GOOGLE =
      "maven { url = uri(\"https://maven.aliyun.com/repository/google\") }"

  private const val MIRROR_CENTRAL =
      "maven { url = uri(\"https://maven.aliyun.com/repository/central\") }"

  /** Standard Android repositories for Groovy. */
  val STANDARD_GROOVY =
      """
          repositories {
              $MIRROR_GRADLE_PLUGIN
              $MIRROR_GOOGLE
              $MIRROR_CENTRAL
              google()
              mavenCentral()
              gradlePluginPortal()
          }
      """
          .trimIndent()

  /** Standard Android repositories for Kotlin DSL. */
  val STANDARD_KTS =
      """
          repositories {
              $MIRROR_GRADLE_PLUGIN
              $MIRROR_GOOGLE
              $MIRROR_CENTRAL
              google()
              mavenCentral()
              gradlePluginPortal()
          }
      """
          .trimIndent()

  /**
   * Standard dependency resolution for Groovy。
   *
   * <p>mode 用 `PREFER_PROJECT` 而非 `FAIL_ON_PROJECT_REPOS`：某些插件的配置阶段
   * 会往 project 级别注入自己的仓库（CMP 的 wasm target 注入 `nodejs.org/dist`），
   * FAIL_ON_PROJECT_REPOS 会直接让 configuration 失败——CMP 模板实测抓到过
   * 「repository ... was added by unknown code」。这里沿用同样的取舍。
   */
  val DEPENDENCY_RESOLUTION_GROOVY =
      """
          repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
          repositories {
              $MIRROR_GRADLE_PLUGIN
              $MIRROR_GOOGLE
              $MIRROR_CENTRAL
              google()
              mavenCentral()
              gradlePluginPortal()
          }
      """
          .trimIndent()

  /** Standard dependency resolution for Kotlin DSL（取舍同上）。 */
  val DEPENDENCY_RESOLUTION_KTS =
      """
          repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
          repositories {
              $MIRROR_GRADLE_PLUGIN
              $MIRROR_GOOGLE
              $MIRROR_CENTRAL
              google()
              mavenCentral()
              gradlePluginPortal()
          }
      """
          .trimIndent()
}
