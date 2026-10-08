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

package com.tom.rv2ide.artificial.agent.compose.components.independent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.artificial.agent.compose.components.tools.ParsedTodoItem
import com.tom.rv2ide.artificial.agent.compose.components.tools.TodoItemRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import com.tom.rv2ide.artificial.agent.compose.compat.ExpandableChevronIcon

/**
 * 位于输入框上方的待办任务常驻面板：
 * - 仅在当前会话有待办项时显示；
 * - 支持折叠为单行紧凑摘要与展开查看完整列表；
 * - 记住各会话的展开/折叠状态；
 * - 弹窗/键盘叠加时支持联动强制收起。
 */
@Composable
internal fun TodoDashboardBar(
    items: List<ParsedTodoItem>,
    sessionId: String,
    modifier: Modifier = Modifier,
    forceCollapse: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {}
) {
    if (items.isEmpty()) return

    // 全部完成就整条撤掉：留着只占输入框上方的地方（主人反馈「任务都做完了还显示在上面」）。
    // 同时把展开状态复位——组件不渲染后回调不会再触发，否则外层的折叠判断会一直以为它还展开着。
    val allCompleted = items.all { it.status == "completed" }
    LaunchedEffect(allCompleted) { if (allCompleted) onExpandedChange(false) }
    if (allCompleted) return

    // 按会话隔离记忆展开状态，新会话默认收起
    var isExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val effectiveExpanded = isExpanded && !forceCollapse

    LaunchedEffect(effectiveExpanded) {
        onExpandedChange(effectiveExpanded)
    }

    val totalCount = items.size
    val completedCount = items.count { it.status == "completed" }
    val inProgressItem = items.firstOrNull { it.status == "in_progress" }

    val cardBgColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs),
        shape = RoundedCornerShape(Radius.lg),
        color = cardBgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 8.dp)
        ) {
            // 单行标题栏（点击切换折叠/展开）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable {
                        // 展开前先让键盘让位，不然面板展开后会正好被键盘挡住。
                        if (!isExpanded) keyboard?.hide()
                        isExpanded = !isExpanded
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.CheckSquare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.todo_dashboard_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(Spacing.sm))

                // 进度胶囊或当前进行中的任务简述
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (completedCount == totalCount && totalCount > 0) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    }
                ) {
                    Text(
                        text = if (completedCount == totalCount && totalCount > 0) {
                            stringResource(R.string.todo_dashboard_all_done)
                        } else {
                            stringResource(R.string.todo_dashboard_progress, completedCount, totalCount)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (!effectiveExpanded && inProgressItem != null) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = inProgressItem.subject,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = if (effectiveExpanded) {
                        stringResource(R.string.common_collapse_action)
                    } else {
                        stringResource(R.string.common_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 16.dp
                )
            }

            // 展开时展示待办列表（平滑淡入展开、向上卷折淡出）
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.xs))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items.forEach { todo ->
                            TodoItemRow(item = todo)
                        }
                    }
                }
            }
        }
    }
}
