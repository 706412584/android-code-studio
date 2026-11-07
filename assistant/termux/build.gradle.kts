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

/*
 * 通用助手 —— 终端（PTY 渲染 + 容器执行）。
 *
 * **本模块不得依赖任何 ACS 模块**（与 assistant:ui 同一约束）。
 *
 * 终端能力由两部分组成：
 *
 * 1. **PTY 与渲染**：直接引 Termux 官方在 JitPack 发布的预编译 AAR
 *    （terminal-emulator / terminal-view），其中已含各 ABI 的 libtermux.so。
 *    不自己编译 native、也不搬那 22 个源文件——参考实现
 *    D:/app/android/android 已验证这条路可行（见其 app/build.gradle.kts:169-171）。
 *
 * 2. **执行体**：proot 进 Linux 容器（rootfs 运行时下载）。
 *    proot 全套以 lib*.so 形式放 jniLibs（nativeLibraryDir 是 App 私有目录里
 *    唯一允许 execve 的位置，targetSdk 29+ 下 filesDir 会被 W^X 拒绝）。
 */

import com.tom.rv2ide.build.config.BuildConfig

plugins {
  id("com.android.library")
  id("kotlin-android")
}

android {
  namespace = "com.assistant.termux"

  compileSdk = BuildConfig.compileSdk
  // 平台目录带 minor（android-37.2），只写 37 会被 AGP 解析成不存在的 android-37。
  compileSdkMinor = BuildConfig.compileSdkMinor

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = BuildConfig.javaVersion
    targetCompatibility = BuildConfig.javaVersion
  }

  // ⚠️ useLegacyPackaging **不能写在这里**。
  //
  // 它是 application 级的打包选项：在 library 模块里设置会被静默忽略。实测在
  // 这里设了之后，打包出的 APK 里 extractNativeLibs 仍是 false，安装后
  // nativeLibraryDir 是空目录，proot 全套都 not found。
  // 正确的落点是 assistant/app/build.gradle.kts 的 packaging 块。
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }
}

dependencies {
  // Termux 官方预编译 AAR：含 TerminalEmulator / TerminalView 与 libtermux.so。
  // terminal-view 会传递带上 terminal-emulator，但显式写出更清楚。
  api("com.github.termux.termux-app:terminal-emulator:v0.118.3")
  api("com.github.termux.termux-app:terminal-view:v0.118.3")

  implementation(libs.common.kotlin)
  implementation(libs.androidx.annotation)

  // tar.gz 解压：Android 框架没有 tar 解析（只有 toybox 的 tar 命令，
  // 旧版本选项不全且要额外 spawn 进程）。版本与 core/app 保持一致。
  implementation("org.apache.commons:commons-compress:1.21")
}
