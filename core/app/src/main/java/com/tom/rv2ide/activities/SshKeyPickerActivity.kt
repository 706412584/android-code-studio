/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns

/**
 * 选择 SSH 私钥文件的蹦床 Activity。
 *
 * <p><b>为什么需要蹦床</b>：发起方是 [androidx.preference.Preference]，拿不到 Activity、
 * 也没有 `registerForActivityResult`。仓库对这类需求的既定解法是「蹦床 Activity + 静态
 * 回调」，见 [FolderPickerActivity] / [AvatarPickerActivity]。
 *
 * <p><b>为什么当场把内容读成字节</b>：`ACTION_OPEN_DOCUMENT` 给的读权限跟随**任务栈**，
 * 而不是永久授权（除非另行 takePersistableUriPermission）。回调之后本 Activity 就
 * `finish()` 了，调用方再拿着 Uri 去读有失效风险。私钥文件很小（几 KB），整块读进内存
 * 再交给调用方最稳，也省去调用方处理 `contentResolver` 与权限的代码。
 */
class SshKeyPickerActivity : Activity() {

  companion object {
    /**
     * 私钥体积上限（1MB）。
     *
     * <p>正常 OpenSSH/PEM 私钥只有几 KB；设上限是为了拦住用户误选的其它大文件
     * （选成压缩包、日志等），避免把无关内容加密写进 `filesDir/ssh_keys/`。
     */
    private const val MAX_KEY_BYTES = 1L * 1024 * 1024

    private const val REQUEST_PICK_KEY = 1004

    /**
     * 结果回调：第一个参数是文件内容（失败/取消为 null），第二个是文件的显示名
     * （用于给导入的密钥起名；拿不到时为 null）。
     *
     * <p>`@Volatile`：写在主线程（onActivityResult），为将来后台读取留出安全边界。
     */
    @Volatile var onKeyPicked: ((ByteArray?, String?) -> Unit)? = null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 私钥没有标准 MIME 类型，只能放开到任意文件；不限制会更容易误选，但限制成
    // application/octet-stream 在多数文件管理器里根本选不到 .pem，取舍后放开。
    val intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "*/*"
        }
    try {
      startActivityForResult(intent, REQUEST_PICK_KEY)
    } catch (e: android.content.ActivityNotFoundException) {
      // 设备上没有文档选择器（精简 ROM / 策略限制）：回调 null 让调用方提示，
      // 而不是把 ActivityNotFoundException 抛给系统层变成「应用已停止」。
      onKeyPicked?.invoke(null, null)
      onKeyPicked = null
      finish()
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQUEST_PICK_KEY) {
      return
    }
    var bytes: ByteArray? = null
    var name: String? = null
    if (resultCode == RESULT_OK) {
      data?.data?.let { uri ->
        name = displayName(uri)
        bytes = readBytes(uri)
      }
    }
    onKeyPicked?.invoke(bytes, name)
    // 回调用完即清：静态引用持有设置页的闭包，不清会让设置页无法回收。
    onKeyPicked = null
    finish()
  }

  override fun onDestroy() {
    super.onDestroy()
    // 兜底清理：进程被回收或系统直接销毁时，静态字段会一直持有设置页闭包。
    // 正常路径已在 onActivityResult 清过，这里是幂等的二次保险。
    onKeyPicked = null
  }

  /** 读取选中文件；超过上限或读取失败返回 null（拒绝而非截断——截断的私钥必然解析失败）。 */
  private fun readBytes(uri: Uri): ByteArray? =
      try {
        contentResolver.openInputStream(uri)?.use { input ->
          val out = java.io.ByteArrayOutputStream()
          val buffer = ByteArray(8 * 1024)
          var total = 0L
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_KEY_BYTES) return null
            out.write(buffer, 0, read)
          }
          out.toByteArray()
        }
      } catch (e: Exception) {
        null
      }

  /** 文件的显示名（`OpenableColumns.DISPLAY_NAME`），拿不到时退回 Uri 最后一段。 */
  private fun displayName(uri: Uri): String? {
    runCatching {
          contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
              val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (index != -1) {
                return cursor.getString(index)
              }
            }
          }
        }
        .getOrNull()
    return uri.lastPathSegment
  }
}