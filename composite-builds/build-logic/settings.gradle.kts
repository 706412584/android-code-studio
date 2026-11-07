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

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {

  includeBuild("../build-deps-common") {
    dependencySubstitution {
      substitute(module("com.tom.rv2ide.build:desugaring-core"))
        .using(project(":desugaring-core"))
    }
  }

  repositories {
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    
    google()
    mavenCentral()
  }

  versionCatalogs {
    create("libs") {
      from(files("../../gradle/libs.versions.toml"))
    }
  }
}

include(
  ":common",
  ":desugaring",
  ":plugins",
  ":properties-parser"
)

// 注意：这里**不能**再 include ":desugaring-core"。
// 它由上面的 includeBuild("../build-deps-common") + dependencySubstitution 提供
// （真实目录在 composite-builds/build-deps-common/desugaring-core，不在 build-logic 下）。
// 历史上这里多写过一个 ":desugaring-core"，指向不存在的目录；Gradle 8 静默容忍，
// Gradle 9 直接报 "Configuring project ':desugaring-core' without an existing directory"。

rootProject.name = "build-logic"