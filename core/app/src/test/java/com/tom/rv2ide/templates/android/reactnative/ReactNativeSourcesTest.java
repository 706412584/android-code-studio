/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.android.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 字符串级断言：RN 模板生成的 Gradle / JS 文本里必须出现（或不出现）哪些关键内容。
 *
 * <p>这里只做「长什么样」的校验；「能否构建」由 {@code ReactNativeProjectGenTest}
 * 产出完整工程树交给真实 Gradle。
 */
public class ReactNativeSourcesTest {

  private static final String PKG = "com.example.rntest";
  private static final String PROJECT = "rntest";

  private static final String NODE = ReactNativeSources.TERMUX_NODE_PATH;

  // ------------------------------------------------------------------ 版本

  @Test
  public void versionMatrix_matchesRn081() {
    // RN 0.81.4 的官方矩阵：Kotlin 2.1.20 / Gradle 8.14.3 / buildTools 36.0.0 /
    // minSdk 24 / compileSdk 36。升级任何一项前先查 RN 的 gradle/libs.versions.toml。
    assertEquals("0.81.4", ReactNativeSources.REACT_NATIVE_VERSION);
    assertEquals("19.1.0", ReactNativeSources.REACT_VERSION);
    assertEquals("2.1.20", ReactNativeSources.KOTLIN_VERSION);
    assertEquals("8.14.3", ReactNativeSources.GRADLE_VERSION);
    assertEquals("36.0.0", ReactNativeSources.BUILD_TOOLS_VERSION);
    assertEquals(24, ReactNativeSources.MIN_SDK);
    assertEquals(36, ReactNativeSources.COMPILE_SDK);
  }

  @Test
  public void nodePath_isTermuxAbsolute() {
    // daemon 没有 Termux PATH，必须是绝对路径；且包名已改为 com.tom.rv2ide。
    assertEquals("/data/data/com.tom.rv2ide/files/usr/bin/node", NODE);
    assertTrue(NODE.startsWith("/"));
  }

  // ------------------------------------------------------------------ settings.gradle

  @Test
  public void settingsGradle_hasIncludeBuildAndAutolink() {
    String s = ReactNativeSources.INSTANCE.settingsGradle(PROJECT);
    // RN 插件从 node_modules 以 composite build 引入（不经 Maven）
    assertTrue(s.contains("includeBuild('node_modules/@react-native/gradle-plugin')"));
    assertTrue(s.contains("includeBuild(\"node_modules/@react-native/gradle-plugin\")"));
    assertTrue(s.contains("id(\"com.facebook.react.settings\")"));
    assertTrue(s.contains("autolinkLibrariesFromCommand"));
    assertTrue(s.contains("include ':app'"));
  }

  @Test
  public void settingsGradle_nodeAbsolutePathInAutolinkCommand() {
    // autolinking 命令必须是 [node 绝对路径, cli.js, config]——默认的
    // `npx @react-native-community/cli config` 在无 PATH 的 daemon 里必炸。
    String s = ReactNativeSources.INSTANCE.settingsGradle(PROJECT);
    assertTrue("缺 node 绝对路径", s.contains("\"" + NODE + "\""));
    assertTrue(
        "缺 CLI 入口",
        s.contains("node_modules/@react-native-community/cli/build/bin.js"));
    assertTrue("缺 config 子命令", s.contains("\"config\""));
    // workingDirectory 显式指项目根（默认 ../ 会指向项目父目录）
    assertTrue(s.contains("file(\".\")"));
  }

  @Test
  public void settingsGradle_nodeFallbackForCi() {
    // 审查 S1：CI（GitHub Actions）上没有 Termux 路径，必须存在性回退到
    // PATH 上的 node，否则 workflow 在 settings 阶段直接炸。
    String s = ReactNativeSources.INSTANCE.settingsGradle(PROJECT);
    assertTrue("缺存在性回退", s.contains("new File(\"" + NODE + "\").exists()"));
    assertTrue("缺回退值", s.contains(": \"node\""));
    // pluginManagement 必须是脚本第一个语句（Gradle 硬约束）
    assertTrue(
        "pluginManagement 必须最先", s.stripLeading().startsWith("pluginManagement"));
  }

  @Test
  public void settingsGradle_hasExplicitLockFiles() {
    // 审查 S2：lockFiles 必须显式指向项目根——默认是父目录（扁平化下为空集），
    // 会让 autolinking 缓存永不失效（装新原生库后静默不链接）。
    String s = ReactNativeSources.INSTANCE.settingsGradle(PROJECT);
    assertTrue(
        "缺 lockFiles 参数",
        s.contains(
            "files(\"package.json\", \"package-lock.json\", \"yarn.lock\", \"react-native.config.js\")"));
  }

  @Test
  public void settingsGradle_rootProjectNameIsProject() {
    // 审查 m1：rootProject.name 不能硬编码 HelloWorld
    String s = ReactNativeSources.INSTANCE.settingsGradle(PROJECT);
    assertTrue(s.contains("rootProject.name = '" + PROJECT + "'"));
    assertFalse(s.contains("HelloWorld"));
  }

  @Test
  public void settingsGradle_noNpx() {
    // npx 依赖 PATH，设备上不可用
    String s = ReactNativeSources.INSTANCE.settingsGradle(PROJECT);
    assertFalse("不得出现 npx", s.contains("npx"));
  }

  // ------------------------------------------------------------------ 根 build.gradle

  @Test
  public void rootBuildGradle_hasVersionExtBlock() {
    String g = ReactNativeSources.INSTANCE.rootBuildGradle(24);
    assertTrue(g.contains("buildToolsVersion = \"36.0.0\""));
    assertTrue(g.contains("minSdkVersion = 24"));
    assertTrue(g.contains("compileSdkVersion = 36"));
    assertTrue(g.contains("kotlinVersion = \"2.1.20\""));
    assertTrue(g.contains("apply plugin: \"com.facebook.react.rootproject\""));
  }

  @Test
  public void rootBuildGradle_minSdkIsParameterized() {
    // 审查 M1：minSdk 必须来自参数（maxOf(options.minSdk, RN_MIN_SDK)），
    // 否则用户选的 28 在 Gradle 里仍是 24——与项目元数据不一致。
    String g = ReactNativeSources.INSTANCE.rootBuildGradle(28);
    assertTrue(g.contains("minSdkVersion = 28"));
  }

  @Test
  public void rootBuildGradle_noVersionsOnClasspath() {
    // AGP 与 KGP 版本由 RN 插件锁定（classpath 故意不带版本号）
    String g = ReactNativeSources.INSTANCE.rootBuildGradle(24);
    assertTrue(g.contains("classpath(\"com.android.tools.build:gradle\")"));
    assertTrue(g.contains("classpath(\"com.facebook.react:react-native-gradle-plugin\")"));
    assertTrue(g.contains("classpath(\"org.jetbrains.kotlin:kotlin-gradle-plugin\")"));
    assertFalse(g.contains("com.android.tools.build:gradle:"));
    assertFalse(g.contains("kotlin-gradle-plugin:"));
  }

  @Test
  public void rootBuildGradle_hasAliyunMirrors() {
    // 国内直连 Google/Maven Central 高延迟（CMP 模板实机验证过），镜像前置
    String g = ReactNativeSources.INSTANCE.rootBuildGradle(24);
    assertTrue(g.contains("maven.aliyun.com/repository/google"));
    assertTrue(g.contains("maven.aliyun.com/repository/central"));
  }

  // ------------------------------------------------------------------ app/build.gradle

  @Test
  public void appBuildGradle_hasReactBlockWithNodePath() {
    String g = ReactNativeSources.INSTANCE.appBuildGradle(PKG);
    assertTrue("缺 react 块", g.contains("react {"));
    assertTrue("缺 node 显式路径", g.contains("\"" + NODE + "\""));
    assertTrue("缺 node 回退（CI 用）", g.contains("termuxNode.exists()"));
    assertTrue("缺 autolinkLibrariesWithApp", g.contains("autolinkLibrariesWithApp()"));
  }

  @Test
  public void appBuildGradle_flatLayoutPaths() {
    // 扁平化布局：JS 根 = 项目根，三个目录都从 ../ 重新指向 node_modules
    String g = ReactNativeSources.INSTANCE.appBuildGradle(PKG);
    assertTrue(g.contains("root = file(\"../\")"));
    assertTrue(g.contains("reactNativeDir = file(\"../node_modules/react-native\")"));
    assertTrue(g.contains("codegenDir = file(\"../node_modules/@react-native/codegen\")"));
    assertTrue(g.contains("cliFile = file(\"../node_modules/react-native/cli.js\")"));
  }

  @Test
  public void appBuildGradle_hasNamespaceAndHermes() {
    String g = ReactNativeSources.INSTANCE.appBuildGradle(PKG);
    assertTrue(g.contains("namespace \"" + PKG + "\""));
    assertTrue(g.contains("applicationId \"" + PKG + "\""));
    assertTrue(g.contains("hermesEnabled.toBoolean()"));
    assertTrue(g.contains("com.facebook.react:hermes-android"));
    assertTrue(g.contains("com.facebook.react:react-android"));
  }

  @Test
  public void appBuildGradle_hasAbiFilter() {
    // old arch 下 RN 插件不读 reactNativeArchitectures（只在 new arch 的
    // NdkConfiguratorUtils 消费），必须在 AGP 侧 abiFilters 裁剪——否则
    // 四个 ABI 的 .so 全打进 APK（实测 97MB debug）。
    String g = ReactNativeSources.INSTANCE.appBuildGradle(PKG);
    assertTrue("缺 abiFilters", g.contains("abiFilters \"arm64-v8a\""));
  }

  // ------------------------------------------------------------------ gradle.properties

  @Test
  public void gradleProperties_oldArchNoNdk() {
    // old arch 是设备端免 C++ 编译的关键：NdkConfiguratorUtils 在
    // newArchEnabled=false 时完全跳过 NDK/CMake 配置。
    String p = ReactNativeSources.INSTANCE.gradleProperties();
    assertTrue(p.contains("newArchEnabled=false"));
    assertTrue(p.contains("hermesEnabled=true"));
    assertTrue(p.contains("reactNativeArchitectures=arm64-v8a"));
    assertTrue(p.contains("android.useAndroidX=true"));
  }

  // ------------------------------------------------------------------ react-native.config.js

  @Test
  public void reactNativeConfigJs_sourceDirIsRoot() {
    // 扁平化布局的关键：CLI 默认只认 android/ 子目录
    String c = ReactNativeSources.INSTANCE.reactNativeConfigJs();
    assertTrue(c.contains("sourceDir: '.'"));
    assertTrue(c.contains("module.exports"));
  }

  // ------------------------------------------------------------------ Android 壳

  @Test
  public void mainApplication_usesGeneratedEntryPoint() {
    // ReactNativeApplicationEntryPoint 是 RN 插件构建时生成的类
    // （GenerateEntryPointTask.kt），源码树里搜不到属正常。
    String s = ReactNativeSources.INSTANCE.mainApplication(PKG);
    assertTrue(s.contains("package " + PKG));
    assertTrue(s.contains("ReactNativeApplicationEntryPoint.loadReactNative"));
    assertTrue(s.contains("loadReactNative(this)"));
    assertTrue(s.contains("DefaultReactNativeHost"));
    assertTrue(s.contains("IS_NEW_ARCHITECTURE_ENABLED"));
  }

  @Test
  public void mainActivity_componentNameMatchesProject() {
    String s = ReactNativeSources.INSTANCE.mainActivity(PKG, PROJECT);
    assertTrue(s.contains("package " + PKG));
    assertTrue(s.contains("getMainComponentName(): String = \"" + PROJECT + "\""));
    assertTrue(s.contains("DefaultReactActivityDelegate"));
  }

  @Test
  public void appManifest_usesPlaceholder() {
    String m = ReactNativeSources.INSTANCE.appManifest();
    // ${usesCleartextTraffic} 由 RN 插件注入（AgpConfiguratorUtils.kt:40-46）
    assertTrue(m.contains("${usesCleartextTraffic}"));
    assertTrue(m.contains("android:name=\".MainApplication\""));
    assertTrue(m.contains("android:name=\".MainActivity\""));
    assertTrue(m.contains("android.permission.INTERNET"));
  }

  @Test
  public void stylesXml_usesAppCompatTheme() {
    // RN 官方模板的 AppTheme 继承 Theme.AppCompat.DayNight.NoActionBar
    // （appcompat 由 react-android 传递依赖带入）
    String s = ReactNativeSources.INSTANCE.stylesXml();
    assertTrue(s.contains("Theme.AppCompat.DayNight.NoActionBar"));
    assertTrue(s.contains("rn_edit_text_material"));
  }

  // ------------------------------------------------------------------ JS 侧

  @Test
  public void packageJson_hasRnDeps() {
    String p = ReactNativeSources.INSTANCE.packageJson(PROJECT);
    assertTrue(p.contains("\"react-native\": \"0.81.4\""));
    assertTrue(p.contains("\"react\": \"19.1.0\""));
    assertTrue(p.contains("\"@react-native-community/cli\": \"20.0.0\""));
    // CLI 入口 bin.js 属于 @react-native-community/cli 包，必须在依赖里
    assertTrue(p.contains("@react-native-community/cli-platform-android"));
  }

  @Test
  public void appJson_nameMatchesProject() {
    String a = ReactNativeSources.INSTANCE.appJson(PROJECT);
    assertTrue(a.contains("\"name\": \"" + PROJECT + "\""));
    assertTrue(a.contains("\"displayName\": \"" + PROJECT + "\""));
  }

  @Test
  public void indexJs_registersComponent() {
    String i = ReactNativeSources.INSTANCE.indexJs();
    assertTrue(i.contains("AppRegistry.registerComponent"));
    assertTrue(i.contains("from './App'"));
    assertTrue(i.contains("from './app.json'"));
  }

  @Test
  public void appTsx_hasCounterExample() {
    String a = ReactNativeSources.INSTANCE.appTsx(PROJECT);
    assertTrue(a.contains("useState"));
    assertTrue(a.contains("setCount"));
    assertTrue(a.contains(PROJECT));
    // 不依赖官方模板的额外包（精简依赖的前提）
    assertFalse(a.contains("new-app-screen"));
    assertFalse(a.contains("safe-area-context"));
  }

  @Test
  public void metroAndBabelConfigs_areValid() {
    String metro = ReactNativeSources.INSTANCE.metroConfigJs();
    assertTrue(metro.contains("@react-native/metro-config"));
    assertTrue(metro.contains("mergeConfig"));

    String babel = ReactNativeSources.INSTANCE.babelConfigJs();
    assertTrue(babel.contains("@react-native/babel-preset"));
  }

  // ------------------------------------------------------------------ CI 与 README

  @Test
  public void ciWorkflow_installsNpmThenBuilds() {
    String w = ReactNativeSources.INSTANCE.ciWorkflow(PROJECT);
    // npm install 必须先于 gradle 构建（node_modules 是硬前置）
    int npmIdx = w.indexOf("npm install");
    int gradleIdx = w.indexOf(":app:assembleDebug");
    assertTrue("缺 npm install", npmIdx > 0);
    assertTrue("缺 assembleDebug", gradleIdx > 0);
    assertTrue("npm install 必须在构建前", npmIdx < gradleIdx);
    assertTrue(w.contains("actions/setup-node@v4"));
    assertTrue(w.contains("actions/setup-java@v4"));
  }

  @Test
  public void readme_documentsNpmInstallAndBuildTools() {
    String r = ReactNativeSources.INSTANCE.projectReadme(PKG, PROJECT);
    // 两个必写的前置：npm install 与 build-tools 36
    assertTrue(r.contains("npm install"));
    assertTrue(r.contains("build-tools;36.0.0"));
    assertTrue(r.contains("registry.npmmirror.com"));
    assertTrue(r.contains("扁平化"));
  }

  @Test
  public void gitignore_excludesNodeModules() {
    String g = ReactNativeSources.INSTANCE.gitignore();
    assertTrue(g.contains("node_modules/"));
    assertTrue(g.contains(".gradle/"));
    // RN 官方模板的 debug.keystore 必须入库（release 块引用它）
    assertTrue(g.contains("!app/debug.keystore"));
  }
}
