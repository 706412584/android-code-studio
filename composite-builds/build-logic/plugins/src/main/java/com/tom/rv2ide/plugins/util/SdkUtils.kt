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

package com.tom.rv2ide.plugins.util

import com.android.build.api.variant.AndroidComponentsExtension
import com.tom.rv2ide.build.config.BuildConfig
import java.io.File

/** @author Akash Yadav */
object SdkUtils {

  fun AndroidComponentsExtension<*, *, *>.getAndroidJar(assertExists: Boolean = true): File {
    val sdkDirectory = sdkComponents.sdkDirectory
    // 用 compileSdkPlatform（如 android-37.2）而不是裸的 compileSdk：
    // SDK 平台目录名带 minor 后缀，拼成 android-37 会指向不存在的目录。
    val androidJar =
        File(sdkDirectory.get().asFile, "platforms/${BuildConfig.compileSdkPlatform}/android.jar")

    if (assertExists) {
      check(androidJar.exists() && androidJar.isFile) {
        "$androidJar does not exist or is not a file."
      }
    }

    return androidJar
  }
}
