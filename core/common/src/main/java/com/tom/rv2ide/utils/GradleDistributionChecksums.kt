/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.utils

/**
 * 已知 Gradle 发行包的官方 SHA-256。
 *
 * <p><b>为什么要把哈希写进模板 wrapper</b>：Gradle 8.9 的 wrapper
 * （`org.gradle.wrapper.Install`）在**没有** `distributionSha256Sum` 时，一旦 zip 解压失败
 * （`ZipException`）就会回退去拉同 URL 的 `.sha256`；而国内镜像（腾讯云）只镜像 zip、
 * **不提供 `.sha256`**（实测 404），404 被
 * `DistributionFactory$ZippedDistribution` 包装成
 * “The specified Gradle distribution ... does not exist”，把真实的网络/损坏问题掩盖成
 * “发行包不存在”。钉住哈希后该回退路径**根本不会被触发**，且损坏的下载会被明确拒绝。
 *
 * <p><b>取值可信度</b>：全部取自 `downloads.gradle.org` 的官方 `.sha256`；并已实测腾讯镜像
 * 的 9.6.1 / 9.0.0 / 8.13 / 8.14.3 / 9.3.1-all 与官方**字节一致**，换镜像不会让哈希失效。
 * 未列入表中的版本不写入哈希——宁可没有校验，也不写一个可能错误的校验值。
 *
 * <p>放在 `core:common` 而非 App 模块：模板资源、Flutter 生成器（App）与
 * `templates-api` 的 wrapper 生成器都要用同一份哈希表。
 */
object GradleDistributionChecksums {

  /** 键：`"<version>-<bin|all>"`；值：官方 SHA-256（小写十六进制）。 */
  @JvmField
  val SHA256: Map<String, String> =
      mapOf(
          "9.6.1-bin" to "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14",
          "9.0.0-bin" to "8fad3d78296ca518113f3d29016617c7f9367dc005f932bd9d93bf45ba46072b",
          "8.14.3-bin" to "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531",
          "8.13-bin" to "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78",
          "9.3.1-all" to "17f277867f6914d61b1aa02efab1ba7bb439ad652ca485cd8ca6842fccec6e43",
      )

  /**
   * 取某个 Gradle 版本的官方哈希。
   *
   * @return 小写十六进制 SHA-256；版本未知返回 `null`（调用方**不得**写入占位值）
   */
  @JvmStatic
  fun sha256For(version: String, allVariant: Boolean): String? {
    val suffix = if (allVariant) "all" else "bin"
    return SHA256["$version-$suffix"]
  }
}