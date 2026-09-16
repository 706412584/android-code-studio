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

    // org.json is provided by the Android framework at runtime; see core/ai-tool-api
    // for the rationale behind compileOnly.
    compileOnly(libs.org.json)

    testImplementation(libs.org.json)
    testImplementation(libs.tests.junit.jupiter)
    testRuntimeOnly(libs.tests.junit.platformLauncher)
}

// Source files contain non-ASCII (Chinese) literals asserted on in unit tests. The build
// JVM defaults to GBK on Chinese Windows, which would corrupt them at compile time.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}
