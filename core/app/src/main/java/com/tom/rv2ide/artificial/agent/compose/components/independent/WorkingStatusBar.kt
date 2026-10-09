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

package com.tom.rv2ide.artificial.agent.compose.components.independent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing

/**
 * 运行状态条：一个转动的点阵 + 一行「此刻在做什么」。
 *
 * <p><b>替换自 [com.tom.rv2ide.artificial.agent.WorkingStatusView]</b>（自定义 View，
 * 用 Canvas 手绘点阵与文字）。换 Compose 的动机与渲染层整体迁移一致：手绘 View
 * 不跟随 Compose 主题（颜色写死在代码里），而面板其余部分已经全部 Compose 化——
 * 两者并排时色调与字体对不上，正是用户反馈的「看起来不是一个界面」。
 *
 * <p><b>点阵为什么不逐点绘制</b>：原实现画 3×3 九个点并让亮度按进度循环。Compose 侧
 * 用「一个旋转的方块点阵」表达同一个意思——「在工作、还活着」是唯一要传达的信息，
 * 具体是几个点、怎么转属于实现细节。旋转用 `rememberInfiniteTransition` 驱动，
 * 帧率与系统动画设置一致（动画关闭时 transition 自动停）。
 *
 * @param action 当前动作文案（如「正在读取 app/build.gradle」）；null 时回退到
 *   [thinking] 决定的两条固定文案
 * @param thinking true 表示模型在推理（尚无正文），false 表示在输出或执行工具
 */
@Composable
internal fun WorkingStatusBar(
    action: CharSequence?,
    thinking: Boolean,
    modifier: Modifier = Modifier,
) {
  // 无限旋转：linear 匀速 + 无限重复。用 Restart 而不是 Reverse——
  // 「一直在转」比「来回摆」更像机器在工作。
  val transition = rememberInfiniteTransition(label = "working-dots")
  val angle by
      transition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 1200, easing = LinearEasing),
                  repeatMode = RepeatMode.Restart,
              ),
          label = "working-angle",
      )

  val text =
      action?.takeIf { it.isNotBlank() }?.toString()
          ?: if (thinking) "思考中…" else "处理中…"

  Row(
      modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    // 点阵：两行两列的四个小方块，整体旋转。用方块而不是圆点——方块的旋转
    // 在视觉上更明显（圆点转起来与静止几乎无差别，等于没动画）。
    Box(modifier = Modifier.size(14.dp).rotate(angle)) {
      DotMatrix()
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
  }
}

/** 四个小圆点排成 2×2；颜色取主题主色，避免与周围文字抢注意力。 */
@Composable
private fun DotMatrix() {
  val color = MaterialTheme.colorScheme.primary
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    repeat(2) {
      Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(2) {
          Box(
              modifier =
                  Modifier.size(6.dp)
                      .alpha(0.85f)
                      .background(color, CircleShape)
          )
        }
      }
    }
  }
}
