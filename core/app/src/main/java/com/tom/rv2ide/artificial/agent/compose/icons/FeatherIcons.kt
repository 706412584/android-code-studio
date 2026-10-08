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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.compose.icons

import androidx.compose.material.icons.Icons
// 这 7 个图标的非 AutoMirrored 版本已废弃：它们在 RTL 布局下应当镜像。
// 用 AutoMirrored 版本既是官方要求的写法，也顺带让阿拉伯语等 RTL 环境下的方向语义正确。
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Feather 图标的兼容层。
 *
 * <p><b>为什么需要它</b>：Aharou 的 Compose 组件引用 `compose.icons.FeatherIcons`，它来自第三方库
 * `br.com.devsrsouza.compose.icons:feather:1.1.1`。该库不在本地 Gradle 缓存中，而本机构建必须
 * `--offline`，无法拉取。因此用 Material Icons 做等价替换，并把替换收敛在这一个文件里。
 *
 * <p><b>为什么沿用 FeatherIcons 这个名字</b>：移植过来的组件里 `FeatherIcons.Copy` 这类引用有上百处，
 * 逐处改名既无意义又极易改漏。把兼容层做成同名 object、同名字段，调用点只需改一行 import，
 * 图标引用保持原样。这是让移植 diff 最小的关键——若这里改成 `AcsIcons`，每个被移植的文件都得动。
 *
 * <p><b>覆盖率是有意受限的</b>：这里的 53 个图标是 `feature/agent/`（Aharou 的助手展示层）实际引用过的
 * 全集，也正是本次移植的范围。不是"Feather 全集"——Aharou 全 App 用到 101 个，其余属于设置/备份等
 * 不在移植范围的界面。将来若移植那些界面，在本文件按同样方式追加即可。
 *
 * <p><b>映射取舍</b>：Feather 与 Material 的图标集并非一一对应，以下三处是语义近似而非等价，
 * 已逐一标注原因，避免后来者以为是随手填的：
 * - `GitBranch` → `AccountTree`：Material 没有 git 图标；AccountTree 的树形分支与 git 分支图最接近。
 * - `HardDrive` → `Save`：`Storage` 已被 `Database` 占用，而 Save 的软盘造型同样是"存储介质"。
 * - `ChevronDown`/`ChevronUp` → `KeyboardArrow*`：Material 只提供左右 chevron（ChevronLeft/Right 已直接对应），
 *   上下方向在 Material 里由 KeyboardArrow 承担。
 *
 * <p><b>存在性已离线核验</b>：53 项全部对照 `material-icons-core-android-1.7.8` 与
 * `material-icons-extended-android-1.7.8` 两个 AAR 的 class 清单逐项确认过，不存在拼错名字的可能。
 */
object FeatherIcons {

  val AlertCircle: ImageVector = Icons.Outlined.ErrorOutline
  val ArrowDown: ImageVector = Icons.Outlined.ArrowDownward
  val ArrowUp: ImageVector = Icons.Outlined.ArrowUpward
  val Camera: ImageVector = Icons.Outlined.CameraAlt
  val Check: ImageVector = Icons.Outlined.Check
  val CheckSquare: ImageVector = Icons.Outlined.CheckBox

  // Material 无上下 chevron，用 KeyboardArrow 承担；左右则有直接对应。
  val ChevronDown: ImageVector = Icons.Outlined.KeyboardArrowDown
  val ChevronLeft: ImageVector = Icons.Outlined.ChevronLeft
  val ChevronRight: ImageVector = Icons.Outlined.ChevronRight
  val ChevronUp: ImageVector = Icons.Outlined.KeyboardArrowUp

  val Clipboard: ImageVector = Icons.Outlined.ContentPaste
  val Clock: ImageVector = Icons.Outlined.Schedule
  val Code: ImageVector = Icons.Outlined.Code
  val Copy: ImageVector = Icons.Outlined.ContentCopy
  val Cpu: ImageVector = Icons.Outlined.Memory
  val Database: ImageVector = Icons.Outlined.Storage
  val Download: ImageVector = Icons.Outlined.Download

  // Feather 的 Edit2/Edit3 是同一支笔的两个变体，Material 侧取 Edit 与 Draw 以保持可区分。
  val Edit2: ImageVector = Icons.Outlined.Edit
  val Edit3: ImageVector = Icons.Outlined.Draw

  val ExternalLink: ImageVector = Icons.AutoMirrored.Outlined.OpenInNew
  val File: ImageVector = Icons.AutoMirrored.Outlined.InsertDriveFile
  val FilePlus: ImageVector = Icons.AutoMirrored.Outlined.NoteAdd
  val FileText: ImageVector = Icons.Outlined.Description
  val Folder: ImageVector = Icons.Outlined.Folder
  val FolderPlus: ImageVector = Icons.Outlined.CreateNewFolder

  /** Material 无 git 图标；AccountTree 的树形分支与 git 分支图最接近。 */
  val GitBranch: ImageVector = Icons.Outlined.AccountTree

  val Globe: ImageVector = Icons.Outlined.Public

  /** `Storage` 已被 [Database] 占用，此处取软盘造型的 Save。 */
  val HardDrive: ImageVector = Icons.Outlined.Save

  val HelpCircle: ImageVector = Icons.AutoMirrored.Outlined.HelpOutline
  val Image: ImageVector = Icons.Outlined.Image
  val Key: ImageVector = Icons.Outlined.Key
  val Maximize: ImageVector = Icons.Outlined.OpenInFull
  val Menu: ImageVector = Icons.Outlined.Menu
  val MessageSquare: ImageVector = Icons.Outlined.ChatBubbleOutline
  val Minimize: ImageVector = Icons.Outlined.CloseFullscreen
  val MoreHorizontal: ImageVector = Icons.Outlined.MoreHoriz
  val Music: ImageVector = Icons.Outlined.MusicNote
  val Package: ImageVector = Icons.Outlined.Inventory2
  val Plus: ImageVector = Icons.Outlined.Add
  val RefreshCw: ImageVector = Icons.Outlined.Refresh
  val RotateCcw: ImageVector = Icons.AutoMirrored.Outlined.RotateLeft
  val Search: ImageVector = Icons.Outlined.Search
  val Settings: ImageVector = Icons.Outlined.Settings
  val Shield: ImageVector = Icons.Outlined.Shield
  val Square: ImageVector = Icons.Outlined.CropSquare
  val Terminal: ImageVector = Icons.Outlined.Terminal
  val Tool: ImageVector = Icons.Outlined.Build
  val Trash2: ImageVector = Icons.Outlined.Delete
  val Video: ImageVector = Icons.Outlined.Videocam
  val Volume2: ImageVector = Icons.AutoMirrored.Outlined.VolumeUp
  val VolumeX: ImageVector = Icons.AutoMirrored.Outlined.VolumeOff
  val X: ImageVector = Icons.Outlined.Close
  val Zap: ImageVector = Icons.Outlined.Bolt
}
