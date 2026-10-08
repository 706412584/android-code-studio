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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.artificial.agent.compose.theme.semanticColors
import com.tom.rv2ide.resources.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clock
import kotlinx.coroutines.delay

/** 倒计时刷新间隔：粒度为分钟，30 秒足够，且不会让顶栏跟着每次流式重组。 */
private const val PILL_REFRESH_MS = 30_000L

/**
 * 定时任务胶囊的数据快照（ACS 接线时由 JSONL 数据源填充；ACS 无 Room/Hilt）。
 */
data class ScheduledTaskSnapshot(
    val id: String,
    val nextRunAt: Long,
    val runCount: Int,
    val maxRuns: Int,   // 0 = 无上限
)

/**
 * 聊天页顶栏下方的定时任务胶囊。
 *
 * 当前会话若被某个启用中的定时任务指向，就显示「倒计时 · 已运行/上限」；没有任务时**不占位**。
 * Aharou 原版从 Room ViewModel 里取「下一次最早要跑」的那个任务；ACS 改为数据驱动，
 * 由调用点传入 [ScheduledTaskSnapshot]（JSONL 会话接线时填充），其余逻辑逐字保留。
 */
@Composable
internal fun ScheduledTaskPill(
    sessionId: String?,
    task: ScheduledTaskSnapshot? = null,
) {
    if (task == null) return

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id) {
        while (true) {
            delay(PILL_REFRESH_MS)
            nowMs = System.currentTimeMillis()
        }
    }

    val remainMinutes = (task.nextRunAt - nowMs) / 60_000L
    val countdown = if (remainMinutes <= 0L) {
        stringResource(R.string.scheduled_tasks_due)
    } else {
        stringResource(R.string.scheduled_tasks_pill_countdown, remainMinutes)
    }
    val runs = if (task.maxRuns > 0) "${task.runCount}/${task.maxRuns}" else task.runCount.toString()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.mdLarge))
                .background(MaterialTheme.semanticColors.capsuleSurface)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                FeatherIcons.Clock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "$countdown · $runs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
