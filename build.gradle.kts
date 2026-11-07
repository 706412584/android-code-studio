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

@file:Suppress("UnstableApiUsage")

import com.tom.rv2ide.build.config.BuildConfig
import com.tom.rv2ide.plugins.AndroidIDEPlugin
import com.tom.rv2ide.plugins.conf.configureAndroidModule
import com.tom.rv2ide.plugins.conf.configureJavaModule
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("build-logic.root-project")
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.protobuf) apply false
  alias(libs.plugins.benchmark) apply false
  // 覆盖率插件：只在根声明版本，由 ai-* / app 模块按需 apply（见各模块 build.gradle.kts）。
  alias(libs.plugins.kover) apply false
  // 编译器插件的字面量版本号**不是**实际生效版本，不要照字面理解。
  //
  // 实际生效的是 kotlin-gradle-plugins-bom 里的 constraint：Kotlin 升到 2.3.21 后
  // `./gradlew buildEnvironment` 实测 compose-compiler-gradle-plugin 解析为
  // `2.3.21 (c)`、kotlin-serialization 同样被提升，与 Kotlin 版本自动对齐。
  // 这里的字面量只用于让 Gradle 解析 plugins{} 块（需要一个存在的 marker 版本）。
  //
  // 因此**不要**为了"与 Kotlin 一致"把下面两个字面量手动改成 2.3.21：那样做要求
  // 本机 Gradle 缓存里存在对应 marker（实测本机缓存仅有 compose marker 2.0.21/
  // 2.1.0/2.2.21，无 2.3.21），离机构建会直接失败。保持现状即可。
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.10" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false

  // ── 构建健康（2026-10-17 接入，见 gradle/libs.versions.toml 同名注释）─────────
  // 依赖分析：根声明即生效（autoapply 默认把分析任务挂到所有子项目；普通构建只注册
  // 不执行）。要出报告跑 ./gradlew buildHealth，默认 severity=warn 不会弄挂构建。
  // 注意：AGP/KGP 已在本块内（apply false），满足它「同 classloader」的要求。
  alias(libs.plugins.dependency.analysis)
  // 构建体检：必须应用在根项目，随任意构建在结束时打印建议（JAVA_HOME/GC/缓存等）。
  alias(libs.plugins.doctor)
  // 版本目录升级：./gradlew versionCatalogUpdate（会重写 libs.versions.toml，按需手动跑）。
  alias(libs.plugins.version.catalog.update)
  // 测试重试：只在这里声明版本，实际 apply 在下方 subprojects 块统一做。
  alias(libs.plugins.test.retry) apply false
}

// Gradle Doctor：JAVA_HOME 检查默认 failOnError=true——未设置 JAVA_HOME 的构建会直接
// 失败（Android Studio 等环境常见）。降级为构建结束时的提示：诊断保留，不拦构建。
doctor {
  javaHome {
    failOnError.set(false)
  }
  // 放行「一次构建多个 Android app 模块」。
  //
  // 本仓库有 core:app 与 assistant:app 两个应用模块，而 CI 为了覆盖全部变体走
  // 根级 `./gradlew assembleDebug`（而非显式 :core:app:assembleDebug）。doctor 默认
  // 会把这判为误用并**直接报错**：
  //   * What went wrong: Did you really mean to run all these?
  //   [task ':assistant:app:assembleDebug', task ':core:app:assembleDebug']
  // 实测该错误会让 CI 的 Assemble APK 步骤失败（r17/r18 时 dev 尚无 assistant 模块，
  // 故当时未暴露）。CI 只分发 core:app 的产物，跑 assistant 的 assemble 无副作用。
  allowBuildingAllAndroidAppsSimultaneously.set(true)
}

buildscript {
  dependencies {
    classpath(libs.kotlin.gradle.plugin)
    classpath(libs.nav.safe.args.gradle.plugin)
  }
}

project.group = BuildConfig.packageName

subprojects {
  if (project != rootProject) {
    var group = project.parent!!.group
    if (project.parent != rootProject) {
      group = "${group}.${project.parent!!.name}"
    }
    project.group = group
  }

  // 全局排除 kotlin-android-extensions-runtime。
  //
  // 它由 `com.blankj:utilcodex:1.31.1` 传递引入（版本 1.3.72，Kotlin 1.3 时代的产物），
  // 其中的 kotlinx.android.parcel.* 与 Kotlin 2.3 的 kotlin-parcelize-runtime 重名。
  // 必须**全局**排除：core:common 用 `api(libs.common.utilcode)` 暴露了它，在单个模块
  // 里 exclude 挡不住传递路径；而 AGP 9 的 checkDuplicateClasses 对此是硬错误
  // （旧版仅警告）。这些类在本项目无处使用。
  configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
  }

  // 测试重试：失败测试自动重试 ≤2 次，仍失败才算失败；重试通过则构建通过并打印报告。
  // 插件对 Test 任务是惰性挂扩展（无测试模块上 apply 无害），Android 模块没有
  // java 插件、加 withId 反而漏掉，故直接全量 apply。
  apply(plugin = "org.gradle.test-retry")
  tasks.withType<Test>().configureEach {
    extensions.configure<org.gradle.testretry.TestRetryTaskExtension>("retry") {
      maxRetries.set(2)
      failOnPassedAfterRetry.set(false)
    }
  }

  // 依赖分析：3.x 起根项目 apply 不再自动挂到子项目（那是 settings 级
  // build-health 插件的职责）。按官方 wiki 的「选择性应用」模式：根（上面已 apply，
  // 提供 buildHealth 聚合任务）+ 各子项目显式 apply（提供 projectHealth）。
  // 只在有可分析插件的模块上生效，其余模块静默忽略。
  apply(plugin = "com.autonomousapps.dependency-analysis")

  afterEvaluate {
    apply { plugin(AndroidIDEPlugin::class.java) }
  }

  project.version = rootProject.version

  plugins.withId("com.android.application") {
    configureAndroidModule(libs.androidx.libDesugaring)
  }
  plugins.withId("com.android.library") {
    configureAndroidModule(libs.androidx.libDesugaring)
  }
  plugins.withId("java-library") { configureJavaModule() }

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(BuildConfig.javaVersion.toString()))
    freeCompilerArgs.addAll("-Xstring-concat=inline")
  }
}
}

tasks.register<Delete>("clean") { delete(rootProject.layout.buildDirectory) }
