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

package com.tom.rv2ide.templates

/**
 * Android API versions.
 *
 * @author Akash Yadav
 */
enum class Sdk(val codename: String, val version: String, val api: Int) {
  JellyBean("Jelly Bean", "4.1", 16),
  JellyBeanMR1("Jelly Bean", "4.2", 17),
  JellyBeanMR2("Jelly Bean", "4.3", 18),
  KitKat("KitKat", "4.4", 19),
  KitKatWatch("KitKat Watch", "4.4W", 20),
  Lollipop("Lollipop", "5.0", 21),
  LollipopMR1("Lollipop", "5.1", 22),
  Marshmallow("Marshmallow", "6.0", 23),
  Nougat("Nougat", "7.0", 24),
  NougatMR1("Nougat", "7.1", 25),
  Oreo("Oreo", "8.0", 26),
  OreoMR1("Oreo", "8.1", 27),
  Pie("Pie", "9.0", 28),
  QuinceTart("Q", "10", 29),
  RedVelvetCake("R", "11", 30),
  SnowCone("SnowCone", "12", 31),
  SnowCodeV2("SnowCone", "12L", 32),
  Tiramisu("Tiramisu", "13", 33),
  UpsideDownCake("UpsideDownCake", "14", 34),
  VanillaIceCream("VanillaIceCream", "15", 35),
  BakLava("BakLava", "16", 36),

  /**
   * API 37 —— **Android 16 的次要版本，没有独立代号**。
   *
   * <p>依据（读本机 SDK 平台元数据得出，不是猜的）：
   * `platforms/android-37.2/source.properties` 里 `AndroidVersion.CodeName=` **为空**，
   * `build.prop` 只有 `ro.build.version.sdk=37`。Google 自 API 36 起改用
   * 「主版本 + 次要版本」编号（36 → 36.1 → 37.x 同属 Android 16），已停用甜点代号。
   *
   * <p>因此 [codename] 留空——[displayName] 对空代号不拼括号，显示为
   * `API 37: Android 16`，而不是编一个假代号误导用户。
   *
   * <p><b>平台目录名带 minor</b>：实际为 `android-37.2`，生成的工程必须同时写
   * `compileSdkMinor = 2`，否则 AGP 去找不存在的 `android-37`（见
   * `SdkVersion.minorOf` 的说明）。
   */
  BakLavaMR1("", "16", 37);

  /**
   * Get the display name for this Sdk version.
   *
   * @return The display name.
   */
  fun displayName(): String =
      // 空代号不拼括号：API 37 是 Android 16 的次要版本、没有独立代号
      // （Google 已停用甜点代号）。拼成 `(Android 16)` 会与前面的
      // `Android 16` 重复，编个假代号更糟。
      if (codename.isBlank()) "API $api: Android $version"
      else "API $api: Android $version ($codename)"

  /**
   * Get the number for this Sdk version.
   *
   * @return The API level.
   */
  fun apiLevel(): Int = api
}
