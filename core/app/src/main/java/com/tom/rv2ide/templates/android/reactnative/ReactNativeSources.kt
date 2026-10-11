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

package com.tom.rv2ide.templates.android.reactnative

/**
 * React Native 模板的文本生成器。
 *
 * 生成的文件分为三类：
 * 1. **Gradle 工程**（settings/build/gradle.properties）——RN 官方模板的扁平化改造版
 * 2. **Android 壳**（MainApplication/MainActivity/manifest/res）
 * 3. **JS 侧**（package.json/index.js/App.tsx/metro 配置/CI workflow/README）
 *
 * <h3>与官方模板的两处硬性差异</h3>
 *
 * 1. **扁平化布局**。RN 官方模板把 Gradle 根放 `android/` 子目录（JS 在上层），
 *    但 ACS 的 `GradleBuildService.kt:147-150` 在**项目根**查找 `gradlew`——
 *    子目录布局下构建按钮直接报「wrapper 不可用」。因此本模板把 Gradle 根
 *    与 JS 根合并到项目根：gradlew、settings.gradle、package.json 同级，
 *    Android 模块仍叫 `app/`。CLI 侧靠 `react-native.config.js` 的
 *    `project.android.sourceDir: '.'` 指回根目录（已在本机实测：config 输出
 *    正确解析出 packageName/applicationId）。
 *
 * 2. **node 显式注入**。设备端 Gradle daemon 的 PATH 不含 Termux prefix
 *    （`tooling/impl/.../Main.kt:152-180`），而 RN 构建有两个位点会调 node：
 *    settings 阶段的 autolinking（`npx ... cli config`）与 build 阶段的
 *    codegen/bundle（`nodeExecutableAndArgs`，默认 `["node"]`）。
 *    两处都必须写死 Termux node 的绝对路径 [TERMUX_NODE_PATH]。
 *
 * <h3>trimIndent 陷阱</h3>
 *
 * 所有 `"""…"""` 模板遵守 QuickDevelop 踩过三次的规则：多行插入值不直接内嵌
 * （其第 1 列缩进会算进公共缩进），一律用单行占位符 + `trimIndent()` 后
 * `.replace("@…@", value)`。
 *
 * <h3>版本组合（2026-10-11 定稿，依据见项目记忆）</h3>
 *
 * RN 0.81.4 × AGP 8.11.0（RN 插件自带） × Kotlin 2.1.20 × Gradle 8.14.3 ×
 * buildTools 36.0.0 × compileSdk 36 × minSdk 24。**禁止顺手升级 RN**：
 * 0.83+ 的 AGP 8.12 与 0.87 的 compileSdk 37 都超出设备工具链范围。
 * v1 用 old architecture（`newArchEnabled=false`）：RN 的 NdkConfiguratorUtils
 * 在 old arch 下完全跳过 NDK/CMake 配置，纯预编译 aar——设备端零 C++ 编译，
 * 与 CMP 模板同风险级。new arch 需要 CMake 编译 libappmodules.so，设备虽有
 * arm64 clang/cmake，但全量 codegen+CMake 的耗时与风险不值得在 v1 承担。
 */
object ReactNativeSources {

  /** RN 版本。npm 包与 Maven 构件版本号一致（`react-native@X` ↔ `com.facebook.react:react-android:X`）。 */
  const val REACT_NATIVE_VERSION = "0.81.4"

  /** React 版本。RN 0.81 的 peerDependency 是 `react@^19.1.0`。 */
  const val REACT_VERSION = "19.1.0"

  /**
   * Kotlin 版本。RN 0.81 模板锁定 2.1.20（gradle-plugin 的 libs.versions.toml 同值）。
   * 与 ACS 自身的 Kotlin 2.1.0 无关——RN 工程的 KGP 由 RN gradle-plugin 的
   * classpath 依赖带入，模板目录里的版本必须与 RN 插件预期一致。
   */
  const val KOTLIN_VERSION = "2.1.20"

  /** Gradle wrapper 版本。RN 0.81 模板用 8.14.3（> ACS 惯用的 8.13，但 wrapper 各自独立）。 */
  const val GRADLE_VERSION = "8.14.3"

  /**
   * Termux 内 node 的绝对路径。
   *
   * 硬编码依据：TermuxConstants.TERMUX_PACKAGE_NAME 已被本项目改为 `com.tom.rv2ide`
   * （`TermuxConstants.java:344`），prefix 路径随之固定为
   * `/data/data/com.tom.rv2ide/files/usr`。设备实测 node v24.7.0 在 `env -i`
   * 干净环境下可直接运行（无需 PATH/LD_LIBRARY_PATH）。
   */
  const val TERMUX_NODE_PATH = "/data/data/com.tom.rv2ide/files/usr/bin/node"

  /** minSdk。RN 0.81 要求 ≥24（官方模板值），向导默认 21 由模板类钳制。 */
  const val MIN_SDK = 24

  /** compileSdk / targetSdk。设备已装 android-36 platform。 */
  const val COMPILE_SDK = 36

  /** buildTools 版本。RN 0.81 要求 36.0.0；设备默认只有 35.0.x，需 sdkmanager 补装。 */
  const val BUILD_TOOLS_VERSION = "36.0.0"

  /** NDK 版本。仅 new arch 需要；old arch 下此值不参与构建（保留以对齐官方模板）。 */
  const val NDK_VERSION = "27.1.12297006"

  // ---------------------------------------------------------------------------
  // Gradle 工程
  // ---------------------------------------------------------------------------

  /**
   * `settings.gradle`——RN 构建的入口。
   *
   * 四个关键点：
   * - `includeBuild("node_modules/@react-native/gradle-plugin")`：RN 的 Gradle
   *   插件**不经 Maven 分发**，而是从 node_modules 里以 composite build 方式引入。
   *   这是 npm install 成为硬前置的根本原因。
   * - `autolinkLibrariesFromCommand(...)` 默认命令是 `npx @react-native-community/cli config`，
   *   在 **settings 配置阶段**执行（`ReactSettingsExtension.kt:31-34`）。daemon 没有
   *   Termux PATH，npx 必然找不到——必须显式传 `[node, cli.js, config]`。
   *   这里的 workingDirectory 显式指向项目根（默认是 `../`，即项目的父目录，
   *   扁平化布局下会读错位置）。
   * - **lockFiles 必须显式传**：默认值是 `rootDirectory.dir("../")` 下的
   *   package.json / lock 文件——扁平化布局下项目根的父目录里一个都没有，
   *   于是「无 lock 文件变化 → 缓存不脏 → 不再执行 config 命令」，
   *   用户装了新原生库（svg/screens/reanimated 等）后 autolinking.json
   *   静默不更新，库不参与链接（审查 S2，实测复现）。
   * - node 路径做存在性回退：设备上用 Termux 绝对路径，CI（GitHub Actions
   *   ubuntu runner）上不存在该路径时回落 PATH 上的 `node`（审查 S1——
   *   否则 workflow 在 settings 阶段直接炸）。
   * - 用 **Groovy** 而非 KTS：RN 官方模板就是 Groovy，`ext {}` 块与 RN 插件文档
   *   的所有示例都是 Groovy 语法，KTS 化只有翻译成本没有收益。
   */
  fun settingsGradle(projectName: String): String =
      """
      pluginManagement { includeBuild("node_modules/@react-native/gradle-plugin") }
      plugins { id("com.facebook.react.settings") }
      extensions.configure(com.facebook.react.ReactSettingsExtension) { ex ->
          // node 路径存在性回退：设备上用 Termux 绝对路径，CI（GitHub Actions）
          // 上不存在时回落 PATH 上的 node（审查 S1——否则云端构建在 settings
          // 阶段直接炸）。判断放闭包内：settings 脚本顶层的语句顺序有 Gradle
          // 硬约束（pluginManagement 必须最先、plugins 紧随其后），
          // 顶层插 def 会报 "must appear before any other statements"。
          def rnNode = new File("@@NODE@@").exists() ? "@@NODE@@" : "node"
          ex.autolinkLibrariesFromCommand(
              [
                  rnNode,
                  "node_modules/@react-native-community/cli/build/bin.js",
                  "config"
              ],
              file("."),
              // lockFiles 显式指向项目根（默认是父目录，扁平化下为空集，
              // 会让 autolinking 缓存永不失效——见方法注释）
              files("package.json", "package-lock.json", "yarn.lock", "react-native.config.js")
          )
      }
      rootProject.name = '@@PROJECT@@'
      include ':app'
      includeBuild('node_modules/@react-native/gradle-plugin')
      """
          .trimIndent()
          .replace("@@NODE@@", TERMUX_NODE_PATH)
          .replace("@@PROJECT@@", projectName) + "\n"

  /**
   * 根 `build.gradle`——版本声明与 classpath。
   *
   * `classpath("com.android.tools.build:gradle")` 与
   * `classpath("org.jetbrains.kotlin:kotlin-gradle-plugin")` **故意不带版本号**：
   * RN gradle-plugin 以 `implementation` 依赖固定了 AGP 8.11.0 与 KGP 2.1.20
   * （`gradle-plugin/gradle/libs.versions.toml`），rootProject 的 classpath
   * 由 RN 插件在配置期接管版本。手写版本号反而会与 RN 内部预期冲突。
   *
   * @param minSdk 必须由调用方传 `maxOf(options.minSdk, RN_MIN_SDK)`——
   *   写死常量会让用户选的 minSdk 在项目元数据（全局 SDK 偏好）与 Gradle
   *   实际值之间不一致（审查 M1）。
   */
  fun rootBuildGradle(minSdk: Int): String =
      """
      buildscript {
          ext {
              buildToolsVersion = "@@BUILD_TOOLS@@"
              minSdkVersion = @@MIN_SDK@@
              compileSdkVersion = @@COMPILE_SDK@@
              targetSdkVersion = @@COMPILE_SDK@@
              ndkVersion = "@@NDK@@"
              kotlinVersion = "@@KOTLIN@@"
          }
          repositories {
              maven { url 'https://maven.aliyun.com/repository/google' }
              maven { url 'https://maven.aliyun.com/repository/central' }
              google()
              mavenCentral()
          }
          dependencies {
              classpath("com.android.tools.build:gradle")
              classpath("com.facebook.react:react-native-gradle-plugin")
              classpath("org.jetbrains.kotlin:kotlin-gradle-plugin")
          }
      }

      apply plugin: "com.facebook.react.rootproject"

      allprojects {
          repositories {
              maven { url 'https://maven.aliyun.com/repository/google' }
              maven { url 'https://maven.aliyun.com/repository/central' }
              google()
              mavenCentral()
          }
      }
      """
          .trimIndent()
          .replace("@@BUILD_TOOLS@@", BUILD_TOOLS_VERSION)
          .replace("@@MIN_SDK@@", minSdk.toString())
          .replace("@@COMPILE_SDK@@", COMPILE_SDK.toString())
          .replace("@@NDK@@", NDK_VERSION)
          .replace("@@KOTLIN@@", KOTLIN_VERSION) + "\n"

  /**
   * `app/build.gradle`——Android 应用模块。
   *
   * `react { }` 块里三个配置是设备端可构建的关键：
   * - `nodeExecutableAndArgs`：codegen / bundle 任务调用 node 的路径
   *   （`GenerateCodegenArtifactsTask.kt:73` 与 bundle 任务都读它）
   * - `root`：JS 根 = 项目根（默认是 `rootProject/../`，扁平化下会指向项目父目录）
   * - `reactNativeDir` / `cliFile` / `codegenDir`：都从根重新指向 node_modules
   *
   * `autolinkLibrariesWithApp()` 读 settings 阶段生成的
   * `build/generated/autolinking/autolinking.json`——若 settings 的 autolink
   * 命令失败（如 node 路径错），这里会连带失败，错误信息指向 autolinking，
   * 排查时先看 settings 阶段。
   */
  fun appBuildGradle(packageId: String): String =
      """
      apply plugin: "com.android.application"
      apply plugin: "org.jetbrains.kotlin.android"
      apply plugin: "com.facebook.react"

      /**
       * React Native 配置。详细选项见 RN 文档 react-native-gradle-plugin。
       */
      react {
          /* Folders —— 扁平化布局：JS 根与 Gradle 根同为项目根 */
          root = file("../")
          reactNativeDir = file("../node_modules/react-native")
          codegenDir = file("../node_modules/@react-native/codegen")
          cliFile = file("../node_modules/react-native/cli.js")

          /* node 显式路径 —— 设备端 Gradle daemon 没有 Termux PATH，
             默认的 "node" 会直接找不到。这是设备端构建的硬要求。
             存在性回退：CI（GitHub Actions）上没有 Termux 路径，回落
             PATH 上的 node（审查 S1——否则云端构建在 codegen/bundle 阶段炸）。 */
          def termuxNode = new File("@@NODE@@")
          nodeExecutableAndArgs = [termuxNode.exists() ? "@@NODE@@" : "node"]

          /* Autolinking —— 读取 settings 阶段生成的 autolinking.json */
          autolinkLibrariesWithApp()
      }

      def enableProguardInReleaseBuilds = false

      def jscFlavor = 'io.github.react-native-community:jsc-android:2026004.+'

      android {
          ndkVersion rootProject.ext.ndkVersion
          buildToolsVersion rootProject.ext.buildToolsVersion
          compileSdk rootProject.ext.compileSdkVersion

          namespace "@@PKG@@"
          defaultConfig {
              applicationId "@@PKG@@"
              minSdkVersion rootProject.ext.minSdkVersion
              targetSdkVersion rootProject.ext.targetSdkVersion
              versionCode 1
              versionName "1.0"

              // ABI 裁剪：old arch 下 RN 插件不读 reactNativeArchitectures
              // （那属性只在 new arch 的 NdkConfiguratorUtils 里被消费），
              // 不过滤的话 react-android/hermes-android 的 aar 会把全部四个
              // ABI 的 .so 打进 APK（实测 97MB）。设备与主流手机都是 arm64，
              // 只保留 arm64-v8a。
              ndk {
                  abiFilters "arm64-v8a"
              }
          }
          signingConfigs {
              debug {
                  storeFile file('debug.keystore')
                  storePassword 'android'
                  keyAlias 'androiddebugkey'
                  keyPassword 'android'
              }
          }
          buildTypes {
              debug {
                  signingConfig signingConfigs.debug
              }
              release {
                  // 生产发布前请替换为自己的签名（见 RN 文档 signed-apk-android）。
                  signingConfig signingConfigs.debug
                  minifyEnabled enableProguardInReleaseBuilds
                  proguardFiles getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"
              }
          }
      }

      dependencies {
          // react-native 的版本由 RN Gradle 插件按 package.json 强制
          implementation("com.facebook.react:react-android")

          if (hermesEnabled.toBoolean()) {
              implementation("com.facebook.react:hermes-android")
          } else {
              implementation jscFlavor
          }
      }
      """
          .trimIndent()
          .replace("@@PKG@@", packageId)
          .replace("@@NODE@@", TERMUX_NODE_PATH) + "\n"

  /**
   * `gradle.properties`。
   *
   * `newArchEnabled=false` 是本模板最重要的开关：RN 的 NdkConfiguratorUtils
   * 在 old arch 下跳过全部 NDK/CMake 配置（`NdkConfiguratorUtils.kt:26-31`），
   * 构建只用预编译 aar——设备端零 C++ 编译。
   *
   * `reactNativeArchitectures=arm64-v8a`：设备是 arm64，只编一个 ABI 可显著
   * 减少打包时间与 APK 体积（官方模板默认四个 ABI 全编）。
   *
   * 内存调低到 2g 与官方一致；daemon 由 ACS 的 launcher 参数另行控制。
   */
  fun gradleProperties(): String =
      """
      # Project-wide Gradle settings.
      org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m

      # AndroidX
      android.useAndroidX=true

      # 只编 arm64（设备即 arm64；官方模板默认四 ABI 全编，构建时间 x4）
      # 注意：old arch 下 RN 插件不读这个属性（只在 new arch 的 NdkConfiguratorUtils
      # 消费）——真正生效的 ABI 裁剪是 app/build.gradle 的 ndk.abiFilters。
      reactNativeArchitectures=arm64-v8a

      # 新架构（Fabric/TurboModules）。v1 关闭：old arch 下 RN 插件完全跳过
      # NDK/CMake 配置，纯预编译 aar 构建——设备端零原生编译。
      # 想启用需设备上有匹配的 NDK 且接受 CMake 全量编译耗时。
      newArchEnabled=false

      # Hermes JS 引擎（预编译 aar，与架构开关无关）
      hermesEnabled=true

      # Edge-to-edge 显示
      edgeToEdgeEnabled=false
      """
          .trimIndent() + "\n"

  /**
   * `react-native.config.js`——CLI 的项目配置。
   *
   * `sourceDir: '.'` 是扁平化布局的关键：CLI 默认只认 `android/` 子目录
   * （`findAndroidDir.js`），指回根目录后 `npx cli config` 才能从
   * `app/build.gradle` 解析出 packageName——而 `GenerateEntryPointTask`
   * 生成 ReactNativeApplicationEntryPoint.java 时需要这个字段。
   * （已在本机用同布局实测：config 输出 packageName/applicationId 正确。）
   */
  fun reactNativeConfigJs(): String =
      """
      module.exports = {
        project: {
          android: {
            // 扁平化布局：Gradle 根 = 项目根（ACS 要求 gradlew 在项目根）。
            sourceDir: '.',
            appName: 'app',
          },
        },
      };
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // Android 壳源码
  // ---------------------------------------------------------------------------

  /**
   * MainApplication.kt。
   *
   * `ReactNativeApplicationEntryPoint.loadReactNative` 看起来像是普通 import，
   * 实际是 **RN Gradle 插件在构建时生成**的类（`GenerateEntryPointTask.kt`，
   * 输出到 `build/generated/autolinking/`）——它内部做 SoLoader.init 并按
   * BuildConfig 开关决定是否加载新架构。源码树里搜不到这个类属于正常现象。
   */
  fun mainApplication(packageId: String): String =
      """
      package @@PKG@@

      import android.app.Application
      import com.facebook.react.PackageList
      import com.facebook.react.ReactApplication
      import com.facebook.react.ReactHost
      import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
      import com.facebook.react.ReactNativeHost
      import com.facebook.react.ReactPackage
      import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
      import com.facebook.react.defaults.DefaultReactNativeHost

      class MainApplication : Application(), ReactApplication {

        override val reactNativeHost: ReactNativeHost =
            object : DefaultReactNativeHost(this) {
              override fun getPackages(): List<ReactPackage> =
                  PackageList(this).packages.apply {
                    // 不能自动链接的包在这里手动添加：
                    // add(MyReactNativePackage())
                  }

              override fun getJSMainModuleName(): String = "index"

              override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

              override val isNewArchEnabled: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
              override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED
            }

        override val reactHost: ReactHost
          get() = getDefaultReactHost(applicationContext, reactNativeHost)

        override fun onCreate() {
          super.onCreate()
          loadReactNative(this)
        }
      }
      """
          .trimIndent()
          .replace("@@PKG@@", packageId) + "\n"

  /**
   * MainActivity.kt。`getMainComponentName` 必须与 app.json 的 `name` 一致
   * （AppRegistry.registerComponent 的注册名）。
   */
  fun mainActivity(packageId: String, projectName: String): String =
      """
      package @@PKG@@

      import com.facebook.react.ReactActivity
      import com.facebook.react.ReactActivityDelegate
      import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
      import com.facebook.react.defaults.DefaultReactActivityDelegate

      class MainActivity : ReactActivity() {

        /**
         * 注册到 JS 侧的主组件名，必须与 app.json 的 name 一致。
         */
        override fun getMainComponentName(): String = "@@APPNAME@@"

        /**
         * DefaultReactActivityDelegate 按 fabricEnabled 开关决定是否启用
         * 新架构渲染（v1 关闭，与 gradle.properties 的 newArchEnabled 一致）。
         */
        override fun createReactActivityDelegate(): ReactActivityDelegate =
            DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
      }
      """
          .trimIndent()
          .replace("@@PKG@@", packageId)
          .replace("@@APPNAME@@", projectName) + "\n"

  /**
   * AndroidManifest.xml。`${usesCleartextTraffic}` 占位符由 RN 插件注入
   * （`AgpConfiguratorUtils.kt:40-46`，debug 构建为 true）——不要手写死值。
   */
  fun appManifest(): String =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">

          <uses-permission android:name="android.permission.INTERNET" />

          <application
            android:name=".MainApplication"
            android:label="@string/app_name"
            android:icon="@mipmap/ic_launcher"
            android:roundIcon="@mipmap/ic_launcher_round"
            android:allowBackup="false"
            android:theme="@style/AppTheme"
            android:usesCleartextTraffic="${'$'}{usesCleartextTraffic}"
            android:supportsRtl="true">
            <activity
              android:name=".MainActivity"
              android:label="@string/app_name"
              android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode"
              android:launchMode="singleTask"
              android:windowSoftInputMode="adjustResize"
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

  /** values/strings.xml。 */
  fun stringsXml(projectName: String): String =
      """
      <resources>
          <string name="app_name">@@APPNAME@@</string>
      </resources>
      """
          .trimIndent()
          .replace("@@APPNAME@@", projectName) + "\n"

  /**
   * values/styles.xml。RN 官方模板的 AppTheme 继承 `Theme.AppCompat.DayNight.NoActionBar`
   * ——appcompat 由 react-android 的传递依赖带入，无需在模板里声明。
   */
  fun stylesXml(): String =
      """
      <resources>

          <!-- Base application theme. -->
          <style name="AppTheme" parent="Theme.AppCompat.DayNight.NoActionBar">
              <item name="android:editTextBackground">@drawable/rn_edit_text_material</item>
          </style>

      </resources>
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // JS 侧
  // ---------------------------------------------------------------------------

  /**
   * package.json。
   *
   * 依赖版本与 RN 0.81.4 官方模板一致（react 19.1.0 / react-native 0.81.4）。
   * 刻意不带 `@react-native/new-app-screen` 与 `react-native-safe-area-context`
   * ——App.tsx 是本模板自写的计数器示例（见 [appTsx]），不依赖这两个包。
   * devDependencies 也精简到运行必需（babel/metro 配置包），去掉了
   * eslint/jest/typescript 工具链——设备端用户改代码为主，不跑测试/lint，
   * 少装 200+ 个包可显著缩短 npm install。
   */
  fun packageJson(projectName: String): String =
      """
      {
        "name": "@@APPNAME@@",
        "version": "0.0.1",
        "private": true,
        "scripts": {
          "android": "react-native run-android",
          "start": "react-native start"
        },
        "dependencies": {
          "react": "@@REACT@@",
          "react-native": "@@RN@@"
        },
        "devDependencies": {
          "@babel/core": "^7.25.2",
          "@babel/preset-env": "^7.25.3",
          "@babel/runtime": "^7.25.0",
          "@react-native-community/cli": "20.0.0",
          "@react-native-community/cli-platform-android": "20.0.0",
          "@react-native/babel-preset": "@@RN@@",
          "@react-native/metro-config": "@@RN@@"
        },
        "engines": {
          "node": ">=20"
        }
      }
      """
          .trimIndent()
          .replace("@@APPNAME@@", projectName)
          .replace("@@REACT@@", REACT_VERSION)
          .replace("@@RN@@", REACT_NATIVE_VERSION) + "\n"

  /** index.js——AppRegistry 入口。 */
  fun indexJs(): String =
      """
      /**
       * @format
       */

      import { AppRegistry } from 'react-native';
      import App from './App';
      import { name as appName } from './app.json';

      AppRegistry.registerComponent(appName, () => App);
      """
          .trimIndent() + "\n"

  /**
   * App.tsx——自写的计数器示例。
   *
   * 用官方模板的 `NewAppScreen` 需要多装两个包，且展示的是 RN 品牌页；
   * 计数器能直观验证「JS 逻辑 → 桥接 → 原生渲染 → 触摸事件」整条链路，
   * 与 CMP 模板的计数器示例保持同一验证语义。
   */
  fun appTsx(projectName: String): String =
      """
      /**
       * @@APPNAME@@ —— 由 AndroidCodeStudio 的 React Native 模板生成。
       *
       * 计数器示例：验证 JS 逻辑、原生渲染与触摸事件链路。
       */

      import React, { useState } from 'react';
      import {
        StatusBar,
        StyleSheet,
        Text,
        TouchableOpacity,
        useColorScheme,
        View,
      } from 'react-native';

      function App(): React.JSX.Element {
        const isDarkMode = useColorScheme() === 'dark';
        const [count, setCount] = useState(0);

        return (
          <View style={[styles.container, isDarkMode && styles.containerDark]}>
            <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
            <View style={styles.content}>
              <Text style={[styles.title, isDarkMode && styles.textDark]}>
                Hello React Native
              </Text>
              <Text style={[styles.subtitle, isDarkMode && styles.textDark]}>
                @@APPNAME@@
              </Text>
              <Text style={[styles.counter, isDarkMode && styles.textDark]}>{count}</Text>
              <TouchableOpacity
                style={styles.button}
                onPress={() => setCount(c => c + 1)}>
                <Text style={styles.buttonText}>点击 +1</Text>
              </TouchableOpacity>
            </View>
          </View>
        );
      }

      const styles = StyleSheet.create({
        container: {
          flex: 1,
          backgroundColor: '#ffffff',
        },
        containerDark: {
          backgroundColor: '#1c1c1e',
        },
        content: {
          flex: 1,
          alignItems: 'center',
          justifyContent: 'center',
          padding: 24,
        },
        title: {
          fontSize: 28,
          fontWeight: 'bold',
          color: '#111111',
        },
        subtitle: {
          fontSize: 16,
          color: '#666666',
          marginTop: 4,
        },
        counter: {
          fontSize: 64,
          fontWeight: 'bold',
          color: '#0a7ea4',
          marginVertical: 24,
        },
        button: {
          backgroundColor: '#0a7ea4',
          borderRadius: 8,
          paddingHorizontal: 32,
          paddingVertical: 12,
        },
        buttonText: {
          color: '#ffffff',
          fontSize: 18,
          fontWeight: '600',
        },
        textDark: {
          color: '#f2f2f2',
        },
      });

      export default App;
      """
          .trimIndent()
          .replace("@@APPNAME@@", projectName) + "\n"

  /** app.json——AppRegistry 注册名。 */
  fun appJson(projectName: String): String =
      """
      {
        "name": "@@APPNAME@@",
        "displayName": "@@APPNAME@@"
      }
      """
          .trimIndent()
          .replace("@@APPNAME@@", projectName) + "\n"

  /** metro.config.js。 */
  fun metroConfigJs(): String =
      """
      const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

      /**
       * Metro 打包器配置
       * https://reactnative.dev/docs/metro
       *
       * @type {import('@react-native/metro-config').MetroConfig}
       */
      const config = {};

      module.exports = mergeConfig(getDefaultConfig(__dirname), config);
      """
          .trimIndent() + "\n"

  /** babel.config.js。 */
  fun babelConfigJs(): String =
      """
      module.exports = {
        presets: ['module:@react-native/babel-preset'],
      };
      """
          .trimIndent() + "\n"

  /** .gitignore——node_modules 与构建产物不入库。 */
  fun gitignore(): String =
      """
      node_modules/
      app/build/
      build/
      .gradle/
      .idea/
      *.iml
      local.properties
      *.keystore
      !app/debug.keystore
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // CI workflow 与 README
  // ---------------------------------------------------------------------------

  /**
   * GitHub Actions workflow——云端出 APK。
   *
   * 与 CMP 模板的差异：RN 的构建前置是 `npm install`（ubuntu runner 直连
   * 官方源，无需镜像），且 debug APK 无需签名配置（用模板自带的 debug.keystore）。
   */
  fun ciWorkflow(projectName: String): String =
      """
      name: Build React Native APK

      on:
        push:
          branches: [ main, master ]
        workflow_dispatch:

      jobs:
        android-apk:
          runs-on: ubuntu-latest
          steps:
            - uses: actions/checkout@v4
            - uses: actions/setup-node@v4
              with:
                node-version: '20'
            - uses: actions/setup-java@v4
              with:
                distribution: 'temurin'
                java-version: '17'
            - uses: gradle/actions/setup-gradle@v4
            - name: Make gradlew executable
              run: chmod +x ./gradlew
            - name: Install JS dependencies
              run: npm install --no-audit --no-fund
            - name: Build debug APK
              run: ./gradlew :app:assembleDebug
            - uses: actions/upload-artifact@v4
              with:
                name: ${projectName}-android-debug
                path: app/build/outputs/apk/debug/*.apk
      """
          .trimIndent() + "\n"

  /**
   * README——设备端使用说明。
   *
   * 必须写清 npm install 前置：node_modules 缺失时 settings 阶段就会失败，
   * 用户看到的是「autolinking 解析失败」这类间接错误。
   */
  fun projectReadme(packageId: String, projectName: String): String =
      """
      # $projectName

      由 **Android Code Studio** 的 *React Native* 模板生成：
      用 JavaScript / TypeScript 写界面与逻辑，原生渲染到 Android。

      ## 首次使用（重要）

      RN 的 Gradle 插件与 autolinking 都从 `node_modules` 加载，
      **构建前必须先安装 JS 依赖**。在 ACS 的终端里执行：

      ```bash
      cd /sdcard/AndroidIDEProjects/$projectName
      npm install --registry=https://registry.npmmirror.com
      ```

      首次安装约 200–300MB（npmmirror 实测 1–3 分钟；设备网络波动时
      可加 `--maxsockets=2` 降低并发重试）。
      安装完成后回到 IDE 点构建即可（debug 构建不打包 JS bundle，
      运行时通过 Metro 连接开发服务器；直接装 APK 会白屏，这是 RN 的
      开发模式行为，发布版由 `assembleRelease` 打包离线 bundle）。

      ## 构建要求

      - **build-tools 36.0.0**：RN 0.81 要求，设备默认只有 35.0.x。
        终端执行：`sdkmanager "build-tools;36.0.0"`
      - **node**：模板已写死 Termux 内的 node 路径，无需配置 PATH。
        在设备外的环境（如云端 CI）构建时会自动回退到 PATH 上的 node。

      > **设备端 release 构建限制**：RN 的 hermesc（JS 编译器）只有
      > x86_64/Windows/macOS 版本，没有 linux-arm64——设备上
      > `assembleRelease`（hermesEnabled=true）会失败。发布包请在
      > GitHub Actions 云端构建（workflow 已配好）；设备上以 debug
      > 构建 + Metro 开发调试为主。

      ## 项目结构（扁平化布局）

      ```
      $projectName/
      ├── settings.gradle          # RN 插件 + autolinking（含 node 绝对路径）
      ├── build.gradle             # 版本声明（RN 0.81.4 矩阵）
      ├── gradle.properties        # newArchEnabled=false（old arch，免 NDK）
      ├── react-native.config.js   # sourceDir='.'（扁平化布局的关键）
      ├── package.json / index.js / App.tsx   # JS 侧
      └── app/                     # Android 模块
          ├── build.gradle
          └── src/main/            # MainActivity / MainApplication / res
      ```

      > 与官方模板不同：Gradle 根在项目根而不是 `android/` 子目录
      > （ACS 的构建服务要求 gradlew 位于项目根）。

      ## 云端打包

      推送到 GitHub 后，`.github/workflows/` 会自动构建 debug APK
      并从 Actions 页面提供下载（无需本机环境）。

      ## 版本组合

      | 组件 | 版本 |
      |---|---|
      | React Native | ${REACT_NATIVE_VERSION} |
      | React | ${REACT_VERSION} |
      | Kotlin | ${KOTLIN_VERSION} |
      | Gradle | ${GRADLE_VERSION} |
      | compileSdk / targetSdk | ${COMPILE_SDK} |
      | minSdk | ${MIN_SDK} |
      | 架构 | old architecture（newArchEnabled=false） |
      """
          .trimIndent() + "\n"
}
