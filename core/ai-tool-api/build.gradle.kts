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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}
