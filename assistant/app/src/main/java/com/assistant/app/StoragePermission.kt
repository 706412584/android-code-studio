/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 外部工作区所需的存储访问权限。
 *
 * <p><b>为什么必须用「所有文件访问」而不是 SAF</b>：工作区要交给 proot 的 `-b` 绑定、
 * 交给 agent 工具用 `java.io.File` 直接读写——两者都需要**真实路径**。SAF 授权的
 * 只是一枚 content:// uri，无法交给容器。因此 Android 11+ 需要
 * `MANAGE_EXTERNAL_STORAGE`，Android 10 及以下用 READ/WRITE_EXTERNAL_STORAGE。
 *
 * <p><b>目标 SDK 是 28 带来的简化</b>：本 App `targetSdk=28`。Android 10 在
 * targetSdk ≤ 29 时才给「分区存储的兼容模式」（`requestLegacyExternalStorage`），
 * 那正是需要 READ/WRITE 权限的那条路径。Android 11+ 则无视 legacy 标志，
 * 纯按 `MANAGE_EXTERNAL_STORAGE` 判定——所以两个分支的判据必须分开写。
 */
object StoragePermission {

  /** 是否已具备访问外部存储的能力。 */
  fun granted(context: Context): Boolean =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // 部分 ROM 上这个调用会抛（定制系统缺少对应服务），按未授权处理更安全。
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
      } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
      }

  /**
   * 需要动态申请的权限列表。
   *
   * <p>Android 11+ 返回空：那是「所有文件访问」，只能由用户去设置页授权，
   * 拿不到运行时权限弹窗（`requestPermissions` 传空数组会直接回调失败）。
   */
  fun runtimePermissions(): Array<String> =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) emptyArray()
      else
          arrayOf(
              Manifest.permission.READ_EXTERNAL_STORAGE,
              Manifest.permission.WRITE_EXTERNAL_STORAGE,
          )

  /**
   * 跳「所有文件访问」授权页（Android 11+）。
   *
   * <p>部分 ROM 没有应用专属授权页，`resolveActivity` 会返回 null——那种情况回退到
   * 全局列表页，否则 `startActivity` 抛 ActivityNotFoundException。
   */
  fun manageAllFilesIntent(context: Context): Intent {
    val packageUri = Uri.parse("package:${context.packageName}")
    val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
    return if (direct.resolveActivity(context.packageManager) != null) direct
    else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
  }

  /**
   * 该路径是否落在**应用私有目录之外**（因此需要外部存储权限）。
   *
   * <p>选到私有目录时不必向用户要权限——先要权限再选私有目录会给一个无意义的授权请求。
   */
  fun requiresExternalPermission(context: Context, dir: java.io.File): Boolean {
    val privateRoot = context.filesDir.parentFile?.absolutePath ?: return true
    val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
    return !(path == privateRoot || path.startsWith("$privateRoot/"))
  }
}
