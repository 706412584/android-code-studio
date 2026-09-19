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

// import com.tom.rv2ide.plugins.tasks.SetupAapt2Task
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.tom.rv2ide.build.config.BuildConfig
import com.tom.rv2ide.build.config.downloadVersion
import com.tom.rv2ide.plugins.tasks.AddAndroidJarToAssetsTask
import com.tom.rv2ide.plugins.tasks.AddFileToAssetsTask
import com.tom.rv2ide.plugins.tasks.GenerateInitScriptTask
import com.tom.rv2ide.plugins.tasks.GradleWrapperGeneratorTask
import com.tom.rv2ide.plugins.util.SdkUtils.getAndroidJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.configurationcache.extensions.capitalized

/**
 * Handles asset copying and generation.
 *
 * @author Akash Yadav
 */
class AndroidIDEAssetsPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.run {
      val wrapperGeneratorTaskProvider =
          tasks.register("generateGradleWrapper", GradleWrapperGeneratorTask::class.java)

      val androidComponentsExtension =
          extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

      // val setupAapt2TaskTaskProvider = tasks.register("setupAapt2", SetupAapt2Task::class.java)

      val addAndroidJarTaskProvider =
          tasks.register("addAndroidJarToAssets", AddAndroidJarToAssetsTask::class.java) {
            androidJar = androidComponentsExtension.getAndroidJar(assertExists = true)
          }

      androidComponentsExtension.onVariants { variant ->
        val variantNameCapitalized = variant.name.replaceFirstChar { it.uppercase() }
        
        // variant.sources.jniLibs?.addGeneratedSourceDirectory(setupAapt2TaskTaskProvider,
        // SetupAapt2Task::outputDirectory)

        variant.sources.assets?.addGeneratedSourceDirectory(
            wrapperGeneratorTaskProvider,
            GradleWrapperGeneratorTask::outputDirectory,
        )

        variant.sources.assets?.addGeneratedSourceDirectory(
            addAndroidJarTaskProvider,
            AddAndroidJarToAssetsTask::outputDirectory,
        )

        // Init script generator
        val generateInitScript =
            tasks.register(
                "generate${variantNameCapitalized}InitScript",
                GenerateInitScriptTask::class.java,
            ) {
              mavenGroupId.set(BuildConfig.packageName)
              downloadVersion.set(this@run.downloadVersion)
            }

        variant.sources.assets?.addGeneratedSourceDirectory(
            generateInitScript,
            GenerateInitScriptTask::outputDir,
        )

        // Tooling API JAR copier
        val copyToolingApiJar =
            tasks.register(
                "copy${variantNameCapitalized}ToolingApiJar",
                AddFileToAssetsTask::class.java,
            ) {
              val implPath = ":tooling:impl"
              val toolingApi =
                  checkNotNull(rootProject.findProject(implPath)) {
                    "Cannot find the Tooling Impl module with project path: '$implPath'"
                  }
              dependsOn(toolingApi.tasks.getByName("copyJar"))

              val toolingApiJar = toolingApi.layout.buildDirectory.file("libs/tooling-api-all.jar")

              inputFile.set(toolingApiJar)
              baseAssetsPath.set("data/common")
            }

        variant.sources.assets?.addGeneratedSourceDirectory(
            copyToolingApiJar,
            AddFileToAssetsTask::outputDirectory,
        )

        // Logger runtime AAR copier
        //
        // `external:logwire` 产出 `logger-runtime.aar`，运行期由
        // `GradleBuildService` 解出后写进 Gradle init script，给**每个用户项目**
        // 注入 `implementation files(...)`。该 AAR 缺失会让用户项目构建失败，
        // 而 App 自身仍能正常构建——所以这个缺失长期没有被发现。
        //
        // 原先 logwire 用 `gradle.projectsEvaluated { tasks.matching { ... } }`
        // 自己接线，但那段代码里的 `tasks` 是 **logwire 项目**的任务集合，
        // 永远匹配不到 `:core:app:preDebugBuild`，属于空操作：
        // 实测 `:core:app:preDebugBuild --dry-run` 的依赖里没有任何 logwire 任务，
        // 因此 `assembleRelease` 与 `fixAarName` 从未被调用过。
        //
        // 这里按与 tooling-api 完全相同的方式，把它注册为「生成的 assets 目录」，
        // 由 AGP 负责合并进 APK。依赖用字符串路径声明，避免在配置期过早解析
        // 由 AGP 变体 API 创建的 `assembleRelease` 任务。
        val copyLoggerRuntimeAar =
            tasks.register(
                "copy${variantNameCapitalized}LoggerRuntimeAar",
                AddFileToAssetsTask::class.java,
            ) {
              dependsOn(":external:logwire:assembleRelease")
              dependsOn(":external:logwire:fixAarName")

              val logwire = checkNotNull(rootProject.findProject(":external:logwire")) {
                "Cannot find the LogWire module with project path: ':external:logwire'"
              }

              // fixAarName 会把 bundleReleaseAar 的产物重命名成这个名字
              val loggerAar =
                  logwire.layout.buildDirectory.file("outputs/aar/logger-runtime.aar")

              inputFile.set(loggerAar)
              baseAssetsPath.set("data/common")
            }

        variant.sources.assets?.addGeneratedSourceDirectory(
            copyLoggerRuntimeAar,
            AddFileToAssetsTask::outputDirectory,
        )
      }
    }
  }
}
