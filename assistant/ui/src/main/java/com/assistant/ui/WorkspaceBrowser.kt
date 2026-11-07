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

package com.assistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.assistant.ui.components.independent.BrowseClipboard
import com.assistant.ui.components.independent.FileBrowseState
import com.assistant.ui.components.independent.FileEntry
import com.assistant.ui.components.independent.FileTreeNode
import java.io.File

/**
 * 侧边栏「文件」Tab 的状态持有者：目录树、展开状态、剪切板。
 *
 * <p><b>为什么需要它</b>：[ChatDrawerContent] 的文件树是**受控组件**——它只渲染
 * 传进去的 `FileBrowseState` / `expandedPaths` / `clipboard`，读写磁盘的动作由宿主做。
 * 这个类就是那个宿主侧的状态机。
 *
 * <p><b>为什么直接操作 [File] 而不走 `FileAccessProvider`</b>：后者的接口是
 * 「按路径写字节 / 递归列文件」这类**工具层**用的最小投影（只有 4 个方法），
 * 没有「列一层目录并带大小/时间/权限」的能力。文件树需要目录列表、条目元数据、
 * 新建/重命名/删除/复制，这些在本地文件系统上直接做更简单，也不必为此扩接口
 * ——扩了就得同时维护远程实现，而本 App 没有远程工作区。
 *
 * <p><b>线程</b>：所有方法都**同步读写磁盘**，必须在后台线程调用（目录可能很大）。
 * 状态字段是 Compose 状态，赋值必须回主线程——调用方负责切。
 */
class WorkspaceBrowser(private val root: File) {

  /** 当前文件树（已按展开状态扁平化）。 */
  var state: FileBrowseState by mutableStateOf(FileBrowseState.Loading)
    private set

  /** 已展开的目录绝对路径。 */
  var expandedPaths: Set<String> by mutableStateOf(emptySet())
    private set

  /** 正在展开的目录（用于显示加载指示）。 */
  var expandingPath: String? by mutableStateOf(null)
    private set

  /** 剪切板。 */
  var clipboard: BrowseClipboard? by mutableStateOf(null)
    private set

  /** 最近一次操作失败的说明；null 表示无错误。 */
  var lastError: String? by mutableStateOf(null)
    private set

  /** 工作区根节点的显示名。 */
  private val rootLabel: String = root.name.ifBlank { "workspace" }

  /** 重新读取整棵树。 */
  fun refresh() {
    state =
        try {
          if (!root.exists()) {
            root.mkdirs()
          }
          FileBrowseState.Success(buildTree())
        } catch (e: Exception) {
          FileBrowseState.Error(e.message ?: "读取目录失败")
        }
  }

  /** 展开/收起一个目录。 */
  fun toggleExpand(path: String) {
    val next = expandedPaths.toMutableSet()
    if (path in next) next.remove(path) else next.add(path)
    expandedPaths = next
    refresh()
  }

  /**
   * 把整棵树按展开状态扁平化成可见节点列表。
   *
   * <p>根节点恒可见；目录展开后才递归其子项，且子项按「目录在前、名字序」排序
   * （与文件管理器一致，避免用户找不到文件夹）。
   */
  private fun buildTree(): List<FileTreeNode> {
    val out = ArrayList<FileTreeNode>()
    out.add(
        FileTreeNode(
            entry =
                FileEntry(
                    name = rootLabel,
                    isDirectory = true,
                    size = 0,
                    lastModified = root.lastModified(),
                    localFile = root,
                    permissions = permissionsOf(root),
                ),
            path = root.absolutePath,
            depth = 0,
            isRoot = true,
            isExpanded = root.absolutePath in expandedPaths,
        ))
    if (root.absolutePath in expandedPaths) {
      appendChildren(root, 1, out)
    }
    return out
  }

  private fun appendChildren(dir: File, depth: Int, out: MutableList<FileTreeNode>) {
    val children = dir.listFiles() ?: return
    // 目录在前，再按名字（忽略大小写）排序。
    val sorted = children.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
    for (child in sorted) {
      val expanded = child.isDirectory && child.absolutePath in expandedPaths
      out.add(
          FileTreeNode(
              entry =
                  FileEntry(
                      name = child.name,
                      isDirectory = child.isDirectory,
                      size = if (child.isDirectory) 0L else child.length(),
                      lastModified = child.lastModified(),
                      localFile = child,
                      permissions = permissionsOf(child),
                  ),
              path = child.absolutePath,
              depth = depth,
              isRoot = false,
              isExpanded = expanded,
          ))
      if (expanded) {
        appendChildren(child, depth + 1, out)
      }
    }
  }

  /** 读一个文件的内容（供「加入对话」用）。超过上限时截断并说明。 */
  fun readText(path: String): String? =
      try {
        val file = File(path)
        if (!file.isFile) null
        else if (file.length() > MAX_TEXT_BYTES) {
          file.inputStream().use { input ->
            val buf = ByteArray(MAX_TEXT_BYTES)
            val read = input.read(buf)
            String(buf, 0, read.coerceAtLeast(0)) + "\n…（文件过大，已截断）"
          }
        } else {
          file.readText()
        }
      } catch (e: Exception) {
        lastError = "读取失败：${e.message}"
        null
      }

  /** 新建文件。 */
  fun createFile(dirPath: String, name: String) = writeEntry(dirPath, name, isFolder = false)

  /** 新建目录。 */
  fun createFolder(dirPath: String, name: String) = writeEntry(dirPath, name, isFolder = true)

  private fun writeEntry(dirPath: String, name: String, isFolder: Boolean) {
    try {
      val target = File(dirPath, name)
      if (target.exists()) {
        lastError = "已存在：$name"
        return
      }
      val ok = if (isFolder) target.mkdirs() else target.createNewFile()
      if (!ok) lastError = "创建失败：$name"
    } catch (e: Exception) {
      lastError = "创建失败：${e.message}"
    }
    refresh()
  }

  /** 重命名。 */
  fun rename(path: String, newName: String) {
    try {
      val source = File(path)
      val target = File(source.parentFile, newName)
      if (target.exists()) {
        lastError = "已存在：$newName"
      } else if (!source.renameTo(target)) {
        lastError = "重命名失败"
      }
    } catch (e: Exception) {
      lastError = "重命名失败：${e.message}"
    }
    refresh()
  }

  /** 删除（目录递归删）。 */
  fun delete(path: String) {
    try {
      val file = File(path)
      // 工作区根不允许删：删了整棵树就没了，且用户无从恢复。
      if (file.absolutePath == root.absolutePath) {
        lastError = "不能删除工作区根目录"
      } else if (!file.deleteRecursively()) {
        lastError = "删除失败"
      }
    } catch (e: Exception) {
      lastError = "删除失败：${e.message}"
    }
    refresh()
  }

  /** 复制/剪切到目标目录。 */
  fun copy(srcPath: String, destDir: String, cut: Boolean) {
    try {
      val src = File(srcPath)
      val dest = File(destDir, src.name)
      if (dest.absolutePath == src.absolutePath) {
        lastError = "源与目标相同"
      } else if (dest.exists()) {
        lastError = "目标已存在：${src.name}"
      } else if (src.isDirectory) {
        src.copyRecursively(dest, overwrite = false)
        if (cut) src.deleteRecursively()
      } else {
        src.copyTo(dest, overwrite = false)
        if (cut) src.delete()
      }
      if (cut) clipboard = null
    } catch (e: Exception) {
      lastError = "${if (cut) "移动" else "复制"}失败：${e.message}"
    }
    refresh()
  }

  /** 设为剪切板（供后续粘贴）。 */
  fun setClipboard(path: String, cut: Boolean) {
    val file = File(path)
    clipboard = BrowseClipboard(sourcePath = file.absolutePath, sourceName = file.name, isCut = cut)
  }

  /** 清空剪切板。 */
  fun clearClipboard() {
    clipboard = null
  }

  /** 消费掉一次错误（UI 提示后调用）。 */
  fun consumeError(): String? {
    val e = lastError
    lastError = null
    return e
  }

  private companion object {
    /** 单文件读取上限。超过就截断——模型上下文装不下几十 MB 的日志。 */
    const val MAX_TEXT_BYTES = 256 * 1024

    /** 权限位的粗略表示（rwx 三段）。Android 上取精确 mode 要 native 调用，这里够展示。 */
    fun permissionsOf(file: File): String {
      val r = if (file.canRead()) "r" else "-"
      val w = if (file.canWrite()) "w" else "-"
      val x = if (file.canExecute()) "x" else "-"
      return "$r$w$x"
    }
  }
}
