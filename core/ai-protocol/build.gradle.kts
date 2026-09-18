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

@Suppress("JavaPluginLanguageLevel")
plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(projects.core.aiToolApi)

    // org.json is provided by the Android framework at runtime. This module is a pure
    // java-library (so its unit tests run without Robolectric), therefore it needs the
    // API on the compile classpath only. `compileOnly` keeps it out of the POM/AAR and
    // avoids the duplicate-class error that `implementation` would cause on Android.
    compileOnly(libs.org.json)

    testImplementation(libs.org.json)
    testImplementation(libs.tests.junit.jupiter)
    testRuntimeOnly(libs.tests.junit.platformLauncher)
}

// The source files in this module contain non-ASCII (Chinese) string literals that are
// asserted on in unit tests. The build runs on a JVM whose default charset is GBK on
// Chinese Windows, which would corrupt those literals at compile time. Pin UTF-8
// explicitly for both compilation and test execution.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// main 源码编译目标锁到 Java 8 的 API 面，而不只是语法级别。
//
// 背景：这四个模块是纯 java-library，编译用 JDK 17，而它们最终跑在 Android 上。
// javac 默认按 JDK 17 的 API 面编译，于是 Java 9+ 的 API（Matcher.appendReplacement
// 的 StringBuilder 重载、Map.entry、List.of、String.isBlank 等）能通过编译、
// JVM 单测也全绿，但真机上抛 NoSuchMethodError——只在设备上才暴露。
//
// options.release = 8 让 javac 按 Java 8 的 API 面校验，这类调用直接编译失败。
// 本模块 main 源码全部是 Java 8 语法，不需要更高的语言级别。
// 注意：不要改成 sourceCompatibility/targetCompatibility，那两个只约束语法与字节码版本，
// 不检查 API，挡不住这个问题。
//
// 只约束 compileJava（main）：测试代码跑在开发机 JVM 上、不进 APK，
// 用 Java 9+ 的集合工厂构造测试数据是安全的，没必要跟着受限。
tasks.named<JavaCompile>("compileJava") {
    options.release = 8
}


tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}
