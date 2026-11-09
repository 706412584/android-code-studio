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


import com.tom.rv2ide.build.config.BuildConfig

@Suppress("JavaPluginLanguageLevel")
plugins {
  // shadow 插件已由 johnrengelman 移交 gradleup；旧坐标 8.1.1 与 Gradle 9 不兼容
  // （shadowJar 报 Groovy MissingPropertyException: No such property: mode
  //  —— Gradle 9 内部 FileCopyDetails API 变更所致）。改用新坐标。
  id("com.gradleup.shadow") version "9.6.1"
  id("java-library")
  id("kotlin-kapt")
  id("org.jetbrains.kotlin.jvm")
}



tasks.withType<Jar> {
  manifest { attributes("Main-Class" to "${BuildConfig.packageName}.tooling.impl.Main") }
}

tasks.register("deleteExistingJarFiles") {
  delete {
    delete(project.layout.buildDirectory.dir("libs"))
  }
}

tasks.register("copyJar") {
  doLast {
    val libsDir = project.layout.buildDirectory.dir("libs")

    copy {
      from(libsDir)
      into(libsDir)
      include("*-all.jar")
      // 排除上一次的产物：它同样以 -all.jar 结尾，会被 include 命中并与重命名后的
      // 目标同名，导致增量构建报 "Entry tooling-api-all.jar is a duplicate"。
      exclude("tooling-api-all.jar")
      rename { "tooling-api-all.jar" }
    }
  }
}

project.tasks.getByName("jar") {
  dependsOn("deleteExistingJarFiles")
  finalizedBy("shadowJar")
}

project.tasks.getByName("shadowJar") {
  finalizedBy("copyJar")
}

dependencies {

  implementation("io.hotmoka:toml4j:0.7.3")
  
  kapt(libs.google.auto.service)

  api(projects.tooling.api)

  implementation(projects.utilities.buildInfo)
  implementation(projects.utilities.shared)

  implementation(libs.common.jkotlin)
  implementation(libs.google.auto.service.annotations)
  implementation(libs.xml.xercesImpl)
  implementation(libs.xml.apis)
  implementation(libs.tooling.gradleApi)

  // No additional deps for proxy (socket-based)


  runtimeOnly(libs.tooling.slf4j)

  testImplementation(libs.tests.junit.jupiter)
  testRuntimeOnly(libs.tests.junit.platformLauncher)
}

// 与本模块源码一致：源码里有非 ASCII 字面量，单测断言的是同一批字符串。
tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  systemProperty("file.encoding", "UTF-8")
}
