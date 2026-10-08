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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.theme.semanticColors

/**
 * iOS 风分段控件（Aharou `core/ui/SegmentedTabs.kt` 逐字移植）。
 *
 * 中性灰轨道 + 白色（深色下为抬起灰）滑块，选中时滑块弹性滑到对应一段。
 *
 * 不用主题色填充，让它与下方的扫描式列表协调，只靠中性色与轻阴影表现层次。
 * 波次 3 的 ChatDrawer 侧栏顶部（会话/文件两个 Tab）使用。
 */
@Composable
fun SegmentedTabs(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
  val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val trackColor = MaterialTheme.semanticColors.mutedSurface
  val thumbColor =
      if (dark) MaterialTheme.semanticColors.capsuleSurface
      else MaterialTheme.semanticColors.cardSurface

  BoxWithConstraints(
      modifier =
          modifier
              .fillMaxWidth()
              .height(42.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(trackColor)
              .padding(3.dp)) {
        val thumbWidth = maxWidth / labels.size
        // 选中滑块位移：临界阻尼弹簧（不过冲），切换时滑块顺滑到位，不是硬切。
        val thumbOffset by
            animateDpAsState(
                targetValue = thumbWidth * selected,
                animationSpec =
                    spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                label = "segmented-thumb")
        Surface(
            modifier =
                Modifier.offset(x = thumbOffset).width(thumbWidth).fillMaxHeight(),
            shape = RoundedCornerShape(9.dp),
            color = thumbColor,
            shadowElevation = 2.dp,
            content = {})
        Row(modifier = Modifier.fillMaxSize()) {
          labels.forEachIndexed { index, label ->
            val isSelected = selected == index
            Box(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        // 禁用点击波纹：选中滑块位移已是反馈，ripple 残留的深色阴影反而扎眼。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null) {
                              onSelect(index)
                            },
                contentAlignment = Alignment.Center) {
                  Text(
                      text = label,
                      style = MaterialTheme.typography.labelLarge,
                      fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                      color =
                          if (isSelected) MaterialTheme.colorScheme.onSurface
                          else MaterialTheme.colorScheme.onSurfaceVariant)
                }
          }
        }
      }
}
