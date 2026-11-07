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

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Folder
import java.io.File

/**
 * 新建会话时选择工作区。
 *
 * <p><b>为什么要问</b>：会话绑定的工作区决定模型能改哪些文件。多个工作区并存时
 * 替用户猜一个，猜错就会把文件写到别的项目里——而且事后很难发现（改动卡上只写
 * 文件名，不写目录）。ACS 的 `startNewConversation` 也是同语义：单项目直接开，
 * 多项目弹 `showSingleChoice` 让用户选。
 *
 * <p><b>只列目录、不提供新建入口</b>：用户要开新会话时目录通常已存在。新建目录
 * 属于设置页「工作区」的职责（那里有完整的目录浏览器），在「开新会话」这个上下文里
 * 再造一套只会让流程变长。
 *
 * @param paths 候选目录绝对路径（宿主已去重、并滤掉不存在的）
 * @param currentPath 当前工作区路径，用于标出「默认就是这个」
 * @param onPick 用户选中的目录
 */
@Composable
fun NewSessionWorkspaceDialog(
    paths: List<String>,
    currentPath: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("在哪个目录新建会话") },
      text = {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
          items(paths, key = { it }) { path ->
            val isCurrent = path == currentPath
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(
                            // 当前工作区用浅色底标出来，避免用户误以为「默认」是第一个。
                            if (isCurrent) {
                              MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            } else {
                              MaterialTheme.colorScheme.surface
                            },
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPick(path) }
                        .padding(horizontal = Spacing.sm, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                  FeatherIcons.Folder,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(18.dp),
              )
              androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    // 显示目录名，下面一行给完整路径——长路径只有名字时无法区分同级同名目录。
                    text = File(path).name.ifBlank { path },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isCurrent) "$path · 当前" else path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
              }
              if (isCurrent) {
                Icon(
                    FeatherIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}