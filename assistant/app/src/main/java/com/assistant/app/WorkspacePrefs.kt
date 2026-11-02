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

package com.assistant.app

import android.content.Context
import java.io.File

/**
 * 工作区（模型与终端能看到、能改的文件范围）的持久化。
 *
 * <p><b>为什么存绝对路径而不是 SAF 的 content:// uri</b>：工作区要同时喂给三处——
 * agent 工具（`java.io.File` 直读写）、文件树、以及 proot 容器的 `-b` 绑定。
 * 三者都要求**真实文件系统路径**，而 `DocumentFile` 只有 uri、拿不到真实路径
 * （SAF 的授权模型就是「不暴露路径」）。因此目录选择必须走自带的文件浏览器
 * 或 `MANAGE_EXTERNAL_STORAGE`（见 [StoragePermission]），不能走系统目录选择器。
 *
 * <p><b>与终端的一致性</b>：容器内 `/root/workspace` 必须与这里返回的目录是同一份
 * 文件（见 `LinuxContainer`）。两处各自读同一个键，因此切换后两端天然一致——
 * **不要在任一侧缓存成单例**，否则改完要重启才生效。
 *
 * <p><b>为什么不放在 assistant:ui / assistant:termux</b>：它是**宿主侧**偏好；
 * ui 是纯渲染层（读了它就得认识 `filesDir`），termux 是终端容器（只该被宿主告知
 * 用哪个目录）。放 app 模块，两者通过参数拿到结果。
 */
object WorkspacePrefs {

  /**
   * 存储位置与键名。
   *
   * <p>沿用 `ai_agent_tools`（与 `AssistantPrefs` 同一偏好文件）：工作区是助手的
   * 运行配置，和对话模式、推理强度同类，散到另一个文件只会让「配置在哪」更难找。
   */
  private const val PREFS_NAME = "ai_agent_tools"
  private const val KEY_WORKSPACE = "workspace_path"

  /** 子目录名。默认工作区 = `filesDir/workspace`。 */
  private const val DEFAULT_DIR = "workspace"

  /** 默认工作区（应用私有目录）。始终可用，也是所有回退路径的落点。 */
  fun defaultWorkspace(context: Context): File =
      File(context.filesDir, DEFAULT_DIR).apply { mkdirs() }

  /** 用户是否显式选过外部目录（false = 用默认私有目录）。 */
  fun isCustomized(context: Context): Boolean = configuredPath(context) != null

  /**
   * 取当前工作区。
   *
   * <p><b>无效即回退，并把坏值清掉</b>：用户选的目录可能在外部被删除、或存储卡
   * 被拔出、或权限被撤销。此时若照用会得到一棵空文件树、且模型写文件静默失败
   * ——比回退到默认目录更难排查。清键是为了让设置页如实显示「默认」，而不是
   * 显示一个用不了的路径。
   *
   * <p><b>不在这里 mkdirs 用户选的目录</b>：能选到它就说明它在；凭空创建会掩盖
   * 「这个路径其实不可访问」。
   */
  fun resolve(context: Context): File {
    val path = configuredPath(context)
    if (path == null) {
      return defaultWorkspace(context)
    }
    val dir = File(path)
    if (dir.isDirectory) {
      return dir
    }
    clear(context)
    return defaultWorkspace(context)
  }

  /** 保存用户选择的工作区。仅做语法与「是目录」校验，权限由调用方保证。 */
  fun save(context: Context, dir: File) {
    prefs(context).edit().putString(KEY_WORKSPACE, dir.absolutePath).apply()
  }

  /** 恢复默认（清掉自定义路径）。 */
  fun clear(context: Context) {
    prefs(context).edit().remove(KEY_WORKSPACE).apply()
  }

  /**
   * 偏好里存的自定义路径；null 表示未设置或已失效。
   *
   * <p>单独抽出来是为了让 [isCustomized] 与 [resolve] 用同一套判据：在别处
   * 复制一遍 `getString(...) != null` 就会在「键存在但值为空串」上分叉。
   */
  private fun configuredPath(context: Context): String? =
      prefs(context).getString(KEY_WORKSPACE, null)?.takeIf { it.isNotBlank() }

  private fun prefs(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
