/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
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
import java.io.File
import java.io.FileOutputStream

/**
 * 选择助手头像的蹦床 Activity。
 *
 * <p><b>为什么是独立 Activity 而不是在设置页直接选</b>：发起方是
 * [androidx.preference.Preference]，它拿不到 Activity 也没有
 * `registerForActivityResult`。仓库对这类需求的既定解法是「蹦床 Activity +
 * 静态回调」，见 [FolderPickerActivity] 与 `aiAgentPrefExts` 里的用法。
 *
 * <p><b>为什么选完立刻把图片拷贝到私有目录</b>：用 `ACTION_GET_CONTENT` 选到的
 * Uri **不带持久读权限**（与 `ACTION_OPEN_DOCUMENT` 不同）。头像要在每次打开助手时
 * 显示，若只记住 Uri，下次启动时权限已失效、图片加载不出来。
 * 因此这里当场把内容读成字节写进 `filesDir/assistant_avatar.png`，回调返回
 * **文件路径**而不是 Uri。
 *
 * <p>失败（读不到内容、磁盘满）时回调 null，调用方保持原头像不变。
 */
class AvatarPickerActivity : Activity() {

  companion object {
    /** 头像文件名（固定名，重复选择直接覆盖，不留垃圾文件）。 */
    private const val AVATAR_FILE_NAME = "assistant_avatar.png"

    private const val REQUEST_PICK_IMAGE = 1002

    /**
     * 结果回调：参数是拷贝后的**本地文件绝对路径**；失败为 null。
     *
     * <p>`@Volatile`：写在主线程（onActivityResult），但为将来可能的后台读取留出安全边界。
     */
    @Volatile var onAvatarPicked: ((String?) -> Unit)? = null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "image/*"
        }
    startActivityForResult(intent, REQUEST_PICK_IMAGE)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQUEST_PICK_IMAGE) {
      return
    }
    var result: String? = null
    if (resultCode == RESULT_OK) {
      result = data?.data?.let { copyToPrivateStorage(it) }
    }
    onAvatarPicked?.invoke(result)
    // 回调用完即清：静态引用持有设置页的闭包，不清会让设置页无法回收。
    onAvatarPicked = null
    finish()
  }

  /**
   * 把选中的图片拷到私有目录，返回文件绝对路径；失败返回 null。
   *
   * <p>用固定文件名（覆盖写）而不是按时间戳命名：用户反复换头像时不会在
   * 私有目录里堆一堆用不到的图片。写入用「临时文件 + 重命名」，避免中途失败
   * 把用户**已有的**头像也损坏掉。
   */
  private fun copyToPrivateStorage(uri: Uri): String? {
    val target = File(filesDir, AVATAR_FILE_NAME)
    val temp = File(filesDir, "$AVATAR_FILE_NAME.tmp")
    return try {
      contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(temp, false).use { output -> input.copyTo(output) }
      } ?: return null
      if (!temp.renameTo(target)) {
        // 重命名失败（目标被占用等）：退化为删除后重试一次，仍失败则清理临时文件。
        if (!(target.delete() && temp.renameTo(target))) {
          temp.delete()
          return null
        }
      }
      target.absolutePath
    } catch (e: Exception) {
      temp.delete()
      null
    }
  }
}
