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

package com.assistant.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 消息渲染区的两项外观设置：卡片缩放与正文颜色。
 *
 * <p><b>为什么用 CompositionLocal 而不是逐组件传参</b>：它们作用于**整棵**消息渲染树
 * （气泡、工具卡、思考块、diff 卡都要跟着缩放/变色），逐层传参要改几十个组件的签名。
 * 主题层注入一次，需要的地方按需读。
 *
 * <p>与 [AIEditorTheme] 的 `textScale` 分工：那个改字号（typography），
 * 这个改**几何缩放**（卡片留白/圆角）与**正文着色**。两者正交。
 */
@Immutable
data class AssistantStyle(
    /** 卡片缩放系数：1.0 = 默认。作用于卡片内边距与圆角。 */
    val cardScale: Float = 1f,
    /**
     * 正文颜色；null 表示跟随主题（用 `MaterialTheme.colorScheme.onSurface`）。
     *
     * <p>可空而不是用哨兵色值：哨兵（如 0）与合法颜色值撞车的风险永远存在，
     * 而 null 在类型上就表达了「未设置」。
     */
    val textColor: Color? = null,
)

/** 默认值 = 跟随主题、不缩放。 */
val LocalAssistantStyle = staticCompositionLocalOf { AssistantStyle() }

/**
 * 按 [AssistantStyle.cardScale] 缩放一个尺寸。
 *
 * <p>用法：`LocalAssistantStyle.current.scaled(ChatStyle.bubbleCorner)`。
 * 抽成方法而不是在每处写乘法：漏乘一处就是「有的卡片跟着缩放、有的不变」，
 * 而那种不一致只有肉眼能发现。
 */
fun AssistantStyle.scaled(base: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    base * cardScale

/** 按 [AssistantStyle.cardScale] 缩放内边距。 */
fun AssistantStyle.scaledPadding(base: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    base * cardScale
