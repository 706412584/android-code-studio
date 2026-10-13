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

package com.tom.rv2ide.templates.android.composemultiplatform

/**
 * Compose Multiplatform 模板的文本生成器。
 *
 * 只放**结构化 writer 表达不了**的内容：KMP 源集 DSL（shared/desktop 的
 * build.gradle.kts）、源码、workflow、README。版本目录 / settings / 顶层
 * build 由模板类用标准 writer（VersionCatalogWriter 等）生成——与
 * LibGdx / QuickDevelop 同一套机制，不在这里重复。
 *
 * <h3>trimIndent 陷阱</h3>
 *
 * 本文件所有 `"""…"""` 模板都遵守 QuickDevelop 踩过三次的规则：
 * 多行插入值不直接内嵌（其第 1 列缩进会算进公共缩进，整份文件保留前导空格），
 * 一律用**单行占位符** + `trimIndent()` 后 `.replace("@…@", value)`。
 *
 * <h3>版本组合（2026-10 定稿，调研依据见项目记忆）</h3>
 *
 * CMP 1.10.3 × Kotlin 2.2.20 × AGP 8.13.0。CMP 1.12+ 实测要求 compileSdk 37 /
 * AGP 9.1+（aar-metadata），与设备端 AGP 8.13 硬冲突，禁止顺手升级；Kotlin
 * 2.2.20 的 AGP fully-tested 上限是 8.11.1，超限只是官方未测（warning），不是硬错。
 * iOS targets（iosArm64/iosSimulatorArm64）已声明：本地无 Xcode 时这些 target
 * 不参与构建（KMP 按平台惰性编译），CI 的 macos runner 才实际出 iOS 产物。
 */
object ComposeMultiplatformSources {

  /** 版本目录里的 Kotlin 版本。CMP 1.10.x 全平台要求 ≥2.1.20、web/native 需 2.2，取 2.2.20。 */
  const val KOTLIN_VERSION = "2.2.20"

  /** 版本目录里的 CMP 版本。升级前先查它的 aar-metadata（minCompileSdk / minAGP）。 */
  const val COMPOSE_MULTIPLATFORM_VERSION = "1.10.3"

  /** shared 模块的 Gradle 路径与目录名。 */
  const val SHARED_MODULE_PATH = ":composeApp"
  const val SHARED_MODULE_DIR = "composeApp"

  /** Desktop 壳模块。 */
  const val DESKTOP_MODULE_PATH = ":desktop"
  const val DESKTOP_MODULE_DIR = "desktop"

  /** 生成的共享 Composable 所在包名后缀（`<packageId>.shared`）。 */
  fun sharedPackage(packageId: String): String = "$packageId.shared"

  // ---------------------------------------------------------------------------
  // shared 模块（composeApp）
  // ---------------------------------------------------------------------------

  /**
   * `composeApp/build.gradle.kts`——KMP 的核心配置。
   *
   * 三个 target：
   * - `androidTarget`：设备端只编它（经 ：app 消费）
   * - `jvm("desktop")`：给 desktop 壳用
   * - `wasmJs`：browser + webpack；Node 由 KGP 自动下载（设备端不执行 wasm
   *   任务所以不触发，云端无所谓）
   *
   * 四个插件缺一不可：`android-library`（AGP，androidTarget 需要）、
   * `compose`（org.jetbrains.compose——`compose.runtime` 等 sourceSets 访问器
   * 就是它提供的，不 apply 就得逐个写坐标）、两个 kotlin 插件同版本。
   *
   * 源集故意扁平：共享代码直接放 `commonMain`，不造自定义 uiCommonMain——
   * 三个 target 都能吃 Compose，多一层源集只增加新手理解成本。
   */
  fun sharedModuleGradle(packageId: String, compileSdk: Int, minSdk: Int): String =
      """
      import org.jetbrains.kotlin.gradle.dsl.JvmTarget

      plugins {
          alias(libs.plugins.kotlin.multiplatform)
          alias(libs.plugins.android.library)
          alias(libs.plugins.compose)
          alias(libs.plugins.kotlin.compose)
      }

      kotlin {
          androidTarget {
              compilerOptions {
                  jvmTarget.set(JvmTarget.JVM_17)
              }
          }

          jvm("desktop") {
              compilerOptions {
                  jvmTarget.set(JvmTarget.JVM_17)
              }
          }

          wasmJs {
              browser {
                  commonWebpackConfig {
                      outputFileName = "app.js"
                  }
              }
              binaries.executable()
          }

          // iOS targets：由 macos runner 构建（见 workflow 的 ios job）。
          // 声明 targets 不影响 Android/桌面构建——KMP 只编译对应平台的产物。
          iosArm64 {
              binaries.framework {
                  baseName = "shared"
                  isStatic = true
              }
          }
          iosSimulatorArm64 {
              binaries.framework {
                  baseName = "shared"
                  isStatic = true
              }
          }

          sourceSets {
              commonMain.dependencies {
                  implementation(compose.runtime)
                  implementation(compose.foundation)
                  implementation(compose.material3)
                  implementation(compose.ui)
              }
          }
      }

      android {
          namespace = "@PACKAGE_ID@.shared"
          compileSdk = @COMPILE_SDK@

          defaultConfig {
              minSdk = @MIN_SDK@
          }

          buildFeatures {
              compose = true
          }

          compileOptions {
              sourceCompatibility = JavaVersion.VERSION_17
              targetCompatibility = JavaVersion.VERSION_17
          }
      }
      """
          .trimIndent()
          .replace("@PACKAGE_ID@", packageId)
          .replace("@COMPILE_SDK@", compileSdk.toString())
          .replace("@MIN_SDK@", minSdk.toString()) + "\n"

  // ---------------------------------------------------------------------------
  // Desktop 壳（desktop）
  // ---------------------------------------------------------------------------

  /**
   * `desktop/build.gradle.kts`。
   *
   * `packageUberJarForCurrentOS` 出 fat jar（任务名注意：不是
   * `packageDistributionForCurrentOS`——那是聚合任务，Linux 上出 deb/rpm）。
   * fat jar 是云端 CI 主推产物：任何装了 JRE 17+ 的机器 `java -jar` 直接跑。
   */
  fun desktopModuleGradle(packageId: String, projectName: String): String =
      """
      import org.jetbrains.compose.desktop.application.dsl.TargetFormat

      plugins {
          alias(libs.plugins.kotlin.multiplatform)
          alias(libs.plugins.compose)
          alias(libs.plugins.kotlin.compose)
      }

      kotlin {
          jvm("desktop")

          sourceSets {
              val desktopMain by getting {
                  dependencies {
                      implementation(compose.desktop.currentOs)
                      implementation(project("$SHARED_MODULE_PATH"))
                  }
              }
          }
      }

      compose.desktop {
          application {
              mainClass = "@PACKAGE_ID@.MainKt"

              nativeDistributions {
                  targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
                  packageName = "@PROJECT_NAME@"
                  packageVersion = "1.0.0"
              }
          }
      }
      """
          .trimIndent()
          .replace("@PACKAGE_ID@", packageId)
          .replace("@PROJECT_NAME@", projectName) + "\n"

  // ---------------------------------------------------------------------------
  // 源码（commonMain / Android 壳 / Desktop 壳）
  // ---------------------------------------------------------------------------

  /**
   * 共享 Compose UI：`composeApp/src/commonMain/kotlin/<pkg>/shared/App.kt`。
   *
   * 一个可交互的最小 demo（计数器）：证明"状态 + 点击 + recomposition"这条
   * 最基本链路在三个 target 上都能跑。全部用 material3 组件。
   */
  fun sharedApp(packageId: String): String =
      """
      package ${sharedPackage(packageId)}

      import androidx.compose.foundation.layout.Arrangement
      import androidx.compose.foundation.layout.Column
      import androidx.compose.foundation.layout.fillMaxSize
      import androidx.compose.foundation.layout.padding
      import androidx.compose.material3.Button
      import androidx.compose.material3.ExperimentalMaterial3Api
      import androidx.compose.material3.MaterialTheme
      import androidx.compose.material3.Scaffold
      import androidx.compose.material3.Text
      import androidx.compose.material3.TopAppBar
      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.getValue
      import androidx.compose.runtime.mutableStateOf
      import androidx.compose.runtime.remember
      import androidx.compose.runtime.setValue
      import androidx.compose.ui.Alignment
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.unit.dp

      /**
       * 共享界面：三个平台（Android / Desktop / Web）跑的是同一份代码。
       *
       * 计数器是 Compose 跨平台的最小闭环——remember 状态、点击、重组。
       */
      @OptIn(ExperimentalMaterial3Api::class)
      @Composable
      fun App() {
          var count by remember { mutableStateOf(0) }

          MaterialTheme {
              Scaffold(
                  topBar = {
                      TopAppBar(title = { Text("Compose Multiplatform") })
                  }
              ) { padding ->
                  Column(
                      modifier = Modifier
                          .fillMaxSize()
                          .padding(padding),
                      verticalArrangement = Arrangement.Center,
                      horizontalAlignment = Alignment.CenterHorizontally,
                  ) {
                      Text(
                          text = "点击了 ${'$'}count 次",
                          style = MaterialTheme.typography.headlineMedium,
                      )
                      Button(
                          onClick = { count++ },
                          modifier = Modifier.padding(top = 16.dp),
                      ) {
                          Text("点我")
                      }
                  }
              }
          }
      }
      """
          .trimIndent() + "\n"

  /**
   * Android 入口：`app/src/main/kotlin/<pkg>/MainActivity.kt`。
   *
   * 注意目录是 `src/main/kotlin`——共享 UI 在 commonMain，Android 壳只有 Kotlin。
   */
  fun mainActivity(packageId: String): String =
      """
      package $packageId

      import android.os.Bundle
      import androidx.activity.ComponentActivity
      import androidx.activity.compose.setContent
      import androidx.activity.enableEdgeToEdge
      import ${sharedPackage(packageId)}.App

      class MainActivity : ComponentActivity() {
          override fun onCreate(savedInstanceState: Bundle?) {
              super.onCreate(savedInstanceState)
              enableEdgeToEdge()
              setContent {
                  App()
              }
          }
      }
      """
          .trimIndent() + "\n"

  /** Desktop 入口：`desktop/src/desktopMain/kotlin/<pkg>/Main.kt`。 */
  fun desktopMain(packageId: String): String =
      """
      package $packageId

      import androidx.compose.ui.window.Window
      import androidx.compose.ui.window.application
      import ${sharedPackage(packageId)}.App

      fun main() = application {
          Window(
              onCloseRequest = ::exitApplication,
              title = "Compose Multiplatform",
          ) {
              App()
          }
      }
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // app 壳的 manifest / themes
  // ---------------------------------------------------------------------------

  /**
   * Android 壳的 manifest。主题用系统 `android:Theme.Material.Light.NoActionBar`
   * （见 [appThemes]）——Compose 自绘全部内容，不需要 appcompat/Material3 XML 主题。
   */
  fun appManifest(): String =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">

          <application
              android:allowBackup="true"
              android:icon="@mipmap/ic_launcher"
              android:label="@string/app_name"
              android:roundIcon="@mipmap/ic_launcher_round"
              android:supportsRtl="true"
              android:theme="@style/Theme.CmpApp">
              <activity
                  android:name=".MainActivity"
                  android:exported="true">
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>
          </application>

      </manifest>
      """
          .trimIndent() + "\n"

  /** `app/src/main/res/values/themes.xml`：一行系统主题，Compose 不需要更多。 */
  fun appThemes(): String =
      """
      <resources>
          <style name="Theme.CmpApp" parent="android:Theme.Material.Light.NoActionBar" />
      </resources>
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // GitHub Actions workflow
  // ---------------------------------------------------------------------------

  /**
   * 云端出包 workflow。
   *
   * 设备端只出 Android APK；桌面 fat jar 用 **三 OS 矩阵**（ubuntu/windows/macos）——
   * fat jar 里的 skiko 渲染库按构建机 OS 打包，单 runner 出的 jar 换 OS 跑不起来
   * （「Cannot find skiko-windows-x64.dll.sha256」），2026-10-10 远端实测踩中后修正。
   * Web 静态站（ubuntu）由推送自动触发。artifact 上传用宽松 glob 兜底两个产物目录。
   * iOS 用 macos runner 出未签名 framework（composeApp 已声明 iosArm64 targets）。
   *
   * <p><b>出包目标可选</b>：workflow_dispatch 的 `targets` 输入（逗号分隔，
   * 默认 all）控制哪些 job 执行——只出安卓时其余平台 job 直接跳过，
   * 省 CI 时间与额度。push 触发时仍出全部平台。
   *
   * <p><b>retention-days: 7</b>：Actions artifact 计入仓库存储配额，而 APK/fat jar
   * 单个上百 MB——仓库默认保留期 90 天时，几次构建就能把免费配额堆满，之后
   * workflow 直接跑不起来（用户实测踩过）。产物是「构建完就下载走」的一次性交付，
   * 7 天足够取回；需要长期留存的产物应发 Release（不计入 Actions 配额）。
   */
  fun ciWorkflow(projectName: String): String =
      """
      name: Build Multiplatform

      on:
        push:
          branches: [ main, master ]
        workflow_dispatch:
          inputs:
            targets:
              description: '出包目标：all=全部（默认）/ 逗号分隔的 android,desktop,web,ios'
              required: false
              default: 'all'
              type: string
            build_mode:
              description: '构建模式'
              required: false
              default: 'debug'
              type: choice
              options:
                - debug
                - release

      jobs:
        android:
          if: ${'$'}{{ github.event_name == 'push' || github.event.inputs.targets == 'all' || contains(github.event.inputs.targets, 'android') }}
          runs-on: ubuntu-latest
          steps:
            - uses: actions/checkout@v4
            - uses: actions/setup-java@v4
              with:
                distribution: 'temurin'
                java-version: '17'
            - uses: gradle/actions/setup-gradle@v4
            - run: chmod +x ./gradlew
            - run: ./gradlew :app:assembleDebug
            - uses: actions/upload-artifact@v4
              with:
                name: ${projectName}-android-debug
                retention-days: 7
                path: app/build/outputs/apk/debug/*.apk

        desktop:
          if: ${'$'}{{ github.event_name == 'push' || github.event.inputs.targets == 'all' || contains(github.event.inputs.targets, 'desktop') }}
          strategy:
            matrix:
              os: [ubuntu-latest, windows-latest, macos-latest]
          runs-on: ${'$'}{{ matrix.os }}
          steps:
            - uses: actions/checkout@v4
            - uses: actions/setup-java@v4
              with:
                distribution: 'temurin'
                java-version: '17'
            - uses: gradle/actions/setup-gradle@v4
            - run: chmod +x ./gradlew
              if: matrix.os != 'windows-latest'
            # fat jar 里的 skiko 原生库按当前 OS 打包，必须每 OS 各出一份。
            # 要 deb/msi/dmg 原生安装包就把任务换成 :desktop:packageDistributionForCurrentOS
            - run: ./gradlew :desktop:packageUberJarForCurrentOS
            - uses: actions/upload-artifact@v4
              with:
                name: ${projectName}-desktop-${'$'}{{ matrix.os }}
                retention-days: 7
                path: |
                  desktop/build/compose/binaries/main/**/*.jar
                  desktop/build/compose/jars/*.jar

        web:
          if: ${'$'}{{ github.event_name == 'push' || github.event.inputs.targets == 'all' || contains(github.event.inputs.targets, 'web') }}
          runs-on: ubuntu-latest
          steps:
            - uses: actions/checkout@v4
            - uses: actions/setup-java@v4
              with:
                distribution: 'temurin'
                java-version: '17'
            - uses: gradle/actions/setup-gradle@v4
            - run: chmod +x ./gradlew
            # wasm 构建需要的 Node 由 Kotlin 插件自动下载，无需预装
            - run: ./gradlew :composeApp:wasmJsBrowserDistribution
            - uses: actions/upload-artifact@v4
              with:
                name: ${projectName}-web
                retention-days: 7
                path: composeApp/build/dist/wasmJs/productionExecutable/**

        # iOS：composeApp 已声明 iosArm64/iosSimulatorArm64 targets（见
        # composeApp/build.gradle.kts）。产物是未签名 .framework；完整 .app
        # 需要 Xcode 工程（iosApp/），本模板暂不含——framework 可直接集成到
        # 已有 Xcode 工程。真机分发需 Apple 开发者账号签名。
        ios:
          if: ${'$'}{{ github.event_name == 'push' || github.event.inputs.targets == 'all' || contains(github.event.inputs.targets, 'ios') }}
          runs-on: macos-latest
          steps:
            - uses: actions/checkout@v4
            - uses: actions/setup-java@v4
              with:
                distribution: 'temurin'
                java-version: '17'
            - uses: gradle/actions/setup-gradle@v4
            - run: chmod +x ./gradlew
            - run: ./gradlew :composeApp:linkDebugFrameworkIosArm64
            - uses: actions/upload-artifact@v4
              with:
                name: ${projectName}-ios-framework
                retention-days: 7
                path: composeApp/build/bin/iosArm64/debugFramework/**
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // README
  // ---------------------------------------------------------------------------

  fun projectReadme(packageId: String, projectName: String): String =
      """
      # $projectName

      由 **Android Code Studio** 的 *Compose Multiplatform* 模板生成：
      一份共享的 Compose UI 代码，跑在 Android、Desktop（JVM）、Web（WASM）
      三个平台上。

      ## 项目结构

      ```
      composeApp/                    共享代码（KMP 模块）
      └── src/commonMain/kotlin/
          └── ${sharedPackage(packageId)}/App.kt    共享 Compose 界面
      app/                           Android 壳（设备上构建出 APK）
      desktop/                       Desktop 壳（云端构建出 fat jar）
      .github/workflows/             推送到 GitHub 自动出包
      ```

      ## 各平台怎么构建

      | 平台 | 在哪构建 | 命令 | 产物 |
      |---|---|---|---|
      | Android | **设备上（本 IDE）** | 构建按钮 / `./gradlew :app:assembleDebug` | APK |
      | Desktop | GitHub Actions | 推送自动触发 | fat jar（`java -jar` 可跑） |
      | Web | GitHub Actions | 推送自动触发 | 静态站点（可部署到任意静态托管） |
      | iOS | GitHub Actions（macOS） | 推送自动触发 | 未签名 .framework |

      手动触发时在 **Run workflow** 表单里填 `targets`：留空或 `all` 出全部
      平台，填 `android` 只出安卓，填 `android,desktop` 出两个（逗号分隔）。

      > iOS 产物是 Kotlin framework（`composeApp/build/bin/iosArm64/...`），
      > 可直接集成进已有 Xcode 工程；完整的 .app 需要 iosApp/ Xcode 工程
      > （本模板暂不含）。真机分发需 Apple 开发者账号签名。

      ## 改界面

      只改 `composeApp/src/commonMain/kotlin/${sharedPackage(packageId)}/App.kt`
      ——三个平台共用这一份代码。

      平台专属代码放各自源集：`composeApp/src/androidMain/`、
      `composeApp/src/desktopMain/` 或 `composeApp/src/wasmJsMain/`。

      ## 版本组合

      Compose Multiplatform ${COMPOSE_MULTIPLATFORM_VERSION} / Kotlin ${KOTLIN_VERSION} /
      AGP 8.13.0 / Gradle 8.13。

      升级 CMP 前先查它的 aar-metadata 要求（1.12+ 需要 compileSdk 37 / AGP 9.1+，
      会与设备端构建冲突）。
      """
          .trimIndent() + "\n"

  /**
   * 根 `.gitignore`。
   *
   * <p>云编译产物目录（`cloudbuild-output/` 与 `.cloudbuild-cache/`）必须忽略：
   * 下载的 APK 产物单个可达上百 MB，一次 `git add .` 就会把大文件误提交进仓库。
   * 其余条目沿用 Gradle 项目的通行忽略集。
   */
  fun gitignore(): String =
      """
      .gradle/
      build/
      */build/
      .idea/
      *.iml
      local.properties
      .kotlin/

      # 云编译产物与缓存（ACS 云编译功能下载的 artifact / 日志 zip）
      cloudbuild-output/
      .cloudbuild-cache/
      """
          .trimIndent() + "\n"
}
