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

package com.assistant.ui.components.independent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Folder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工作区目录选择器。
 *
 * <p><b>为什么不用系统的目录选择器（SAF `ACTION_OPEN_DOCUMENT_TREE`）</b>：SAF 回传的
 * 是 `content://` uri，`DocumentFile` 拿不到真实路径——那是 SAF 的设计前提（不暴露
 * 文件系统路径）。而工作区必须交给 proot 的 `-b` 绑定与 `java.io.File` 直读写，
 * 两者都要求真实路径。因此这里自带一个浏览已有目录的对话框。
 *
 * <p><b>为什么是「浏览已有目录」而不是「新建目录」</b>：工作区通常已经存在（用户的
 * 项目目录、下载目录）。建目录属于文件管理器的事，用户在外面建好再回来选即可——
 * 在这个对话框里再造一套新建/命名交互，收益小而面积大。
 *
 * <p><b>起点与上限</b>：起点由宿主给（见 [start]），上限由 [ceiling] 给——默认停在
 * 外部存储根 `/storage/emulated/0`。**不设上限的话用户可以一路点到 `/`**，
 * 那样既是糟糕的选择（把整个文件系统当工作区），也会让根目录列表很大很慢。
 *
 * <p><b>为什么不去读 `Environment.getExternalStorageDirectory()`</b>：渲染层不依赖
 * Android 存储 API（那是宿主的职责），上限由宿主参数化传入，本层只管浏览。
 *
 * @param start 初始目录；不存在时回退到 [ceiling]
 * @param ceiling 允许上溯的最高目录（宿主传入，通常是外部存储根）
 * @param onPick 确认选择，回传真实绝对路径目录
 */
@Composable
fun WorkspaceDirectoryPickerDialog(
    start: File,
    ceiling: File,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
  // 起点：**必须在 [ceiling] 子树内**，否则退回 ceiling。
  //
  // 默认工作区在应用私有目录（/data/user/0/...），它不在外部存储子树内——照它当起点
  // 的话「上一级」会一路点到 `/`（上限只挡住「等于 ceiling」这一种情况），
  // 而用户真正要挑的是外部存储里的目录。从 ceiling 起步，两步就能到目标。
  val initial = remember(start, ceiling) {
    val ceilingPath = ceiling.absolutePath.trimEnd('/')
    val startPath = start.absolutePath
    if (start.isDirectory && (startPath == ceilingPath || startPath.startsWith("$ceilingPath/"))) {
      start
    } else {
      ceiling
    }
  }
  var current by remember(initial) { mutableStateOf(initial) }

  // 子目录列表：**在 IO 线程读**。大目录（几千个条目）在主线程 listFiles 会卡住输入。
  // produceState 的 key 是 current：切目录即重新读取。
  val children by
      produceState<List<File>?>(null, current) {
        value = null
        value =
            withContext(Dispatchers.IO) {
              current
                  .listFiles()
                  ?.filter { it.isDirectory }
                  // 目录名前导点多为系统/缓存目录，但用户也可能真的想选——不隐藏，
                  // 只把它们排在后面，主列表仍是「先普通目录、再隐藏目录」。
                  ?.sortedWith(
                      compareBy({ it.name.startsWith(".") }, { it.name.lowercase() }))
                  .orEmpty()
            }
      }

  // 只有仍在 ceiling 子树内、且不是 ceiling 本身时才允许上溯。
  val canGoUp = remember(current, ceiling) {
    val ceilingPath = ceiling.absolutePath.trimEnd('/')
    current.absolutePath != ceilingPath && current.absolutePath.startsWith("$ceilingPath/")
  }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("选择工作区目录") },
      text = {
        Column(Modifier.fillMaxWidth()) {
          // 当前路径：等宽字体，长路径最多两行。
          Text(
              text = current.absolutePath,
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
          )
          if (canGoUp) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { current = current.parentFile ?: ceiling }
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                  FeatherIcons.ArrowUp,
                  contentDescription = "上一级",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
              )
              androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
              Text(
                  "上一级",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          val list = children
          when {
            list == null ->
                Text(
                    "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            list.isEmpty() ->
                Text(
                    "没有子目录（可以点「用这个目录」）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  items(list, key = { it.absolutePath }) { child ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { current = child }
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Icon(
                          FeatherIcons.Folder,
                          contentDescription = null,
                          tint = MaterialTheme.colorScheme.primary,
                          modifier = Modifier.size(18.dp),
                      )
                      androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
                      Text(
                          child.name,
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onSurface,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                      )
                    }
                  }
                }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { onPick(current) }) { Text("用这个目录") }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}
