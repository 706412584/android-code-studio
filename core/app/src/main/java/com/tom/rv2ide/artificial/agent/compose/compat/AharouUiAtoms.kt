/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.artificial.agent.compose.compat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.theme.Brand
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight

/**
 * `com.aharou.core.ui` 里被移植组件共用的两个 UI 原子（源自 Aharou
 * `core/ui/ExpandableChevronIcon.kt` 与 `core/ui/WindowSize.kt`）。
 *
 * <p><b>为什么收进 compat 而不是留给某个移植组</b>：折叠箭头被三个不同归属的文件引用
 * （MessageBubbles / StatusBubbles / AskUserQuestionPanel，横跨两个移植组）。留在任一组的
 * 包里，另一组就得反向依赖它；各造一份则会出现两套时长/缓动不一致的箭头。
 *
 * <p>`core/ui/WindowSize.kt` 的其余内容（`WindowWidthClass` / `readableContentMaxWidth` /
 * `drawerWidth` / `isExpandedWidth` / `isCompactWidth` / `ContentWidth.drawerCompact|drawerWide`）
 * 移植范围内的 16 个文件一个都没引用，故不搬——按实际引用决定，不是照目录名全搬。
 */

/**
 * 折叠箭头的旋转语义风格：
 * - [UP_DOWN]: 基准朝下 (ChevronDown)，收起为 0°，展开翻转 180° 朝上；用于卡片内部折叠、思考面板、顶栏看板等；
 * - [RIGHT_DOWN]: 基准朝右 (ChevronRight)，收起为 0°，展开顺时针旋转 90° 朝下；用于整轮执行过程、侧边栏时间分组、Git 状态分组等。
 */
enum class ChevronRotationStyle {
  UP_DOWN,
  RIGHT_DOWN,
}

private const val CHEVRON_ANIM_DURATION_MS = 200

/**
 * 统一折叠小箭头组件：封装平滑的旋转动画与统一的时长/缓动曲线。
 */
@Composable
fun ExpandableChevronIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    style: ChevronRotationStyle = ChevronRotationStyle.UP_DOWN,
    size: Dp = 18.dp,
    tint: Color = Brand.IconGray,
    contentDescription: String? = null
) {
  val targetRotation =
      when (style) {
        ChevronRotationStyle.UP_DOWN -> if (expanded) 180f else 0f
        ChevronRotationStyle.RIGHT_DOWN -> if (expanded) 90f else 0f
      }
  val rotation by
      animateFloatAsState(
          targetValue = targetRotation,
          animationSpec = tween(durationMillis = CHEVRON_ANIM_DURATION_MS, easing = FastOutSlowInEasing),
          label = "chevron_rotation")
  val baseIcon: ImageVector =
      when (style) {
        ChevronRotationStyle.UP_DOWN -> FeatherIcons.ChevronDown
        ChevronRotationStyle.RIGHT_DOWN -> FeatherIcons.ChevronRight
      }
  Icon(imageVector = baseIcon, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size).rotate(rotation))
}

/**
 * 正文列宽度上限。
 *
 * <p>只保留 [readable]：`drawerCompact` / `drawerWide` 在移植范围内无人引用，
 * 留空壳只会让人以为侧栏布局也一起移植了。
 */
object ContentWidth {
  /** 正文列最大宽度：再宽一行文字过长，视线来回扫描成本高。 */
  val readable = 800.dp
}
