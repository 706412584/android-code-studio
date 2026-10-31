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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.ui.compat.SegmentedTabs
import com.assistant.ui.components.independent.SettingsGroupHeader
import com.assistant.ui.components.independent.settingsPageBackground
import com.assistant.ui.theme.AIEditorTheme
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2

/**
 * 会话列表的一行（渲染层投影）。
 *
 * <p>与引擎的 `ConversationSummary` 字段一一对应，但不直接引用它——
 * 渲染层不依赖引擎（见 `assistant/engine/build.gradle.kts` 的说明）。
 */
data class SessionRow(
    val id: String,
    val title: String,
    /** 最后修改时间（epoch 毫秒）。 */
    val updatedAt: Long,
    /** 消息条数；0 表示空会话。 */
    val messageCount: Int = 0,
)

/**
 * 会话抽屉：**顶栏汉堡键打开的就是这一页**。
 *
 * <p>参考项目 Aharou 的抽屉分「会话 / 文件」两个 Tab。本 App 还没有工作区文件树
 * （模型的工作区在私有目录里，用户没必要浏览），因此只保留「会话」一页，
 * 文件 Tab 的位置留给「设置」入口——用户需要它，而抽屉是唯一能稳定到达的地方。
 *
 * @param currentSessionId 当前打开的会话 id，用于高亮
 */
@Composable
fun AssistantSessionsDrawer(
    sessions: List<SessionRow>,
    currentSessionId: String?,
    onSelect: (SessionRow) -> Unit,
    onNewSession: () -> Unit,
    onDelete: (SessionRow) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  AIEditorTheme(darkTheme = darkTheme, followHostFrame = false) {
    Column(
        modifier = modifier.fillMaxSize().background(settingsPageBackground()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      // ---- 顶部：标题 + 新建 ----
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.lg),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = "会话",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.IconButton(onClick = onNewSession) {
          Icon(
              FeatherIcons.Plus,
              contentDescription = "新建会话",
              tint = MaterialTheme.colorScheme.onBackground,
              modifier = Modifier.size(20.dp),
          )
        }
      }

      // ---- 会话列表 ----
      if (sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
              text = "还没有会话",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
          items(sessions, key = { it.id }) { session ->
            SessionRowItem(
                session = session,
                selected = session.id == currentSessionId,
                onClick = { onSelect(session) },
                onDelete = { onDelete(session) },
            )
          }
        }
      }

      // ---- 底部：设置入口 ----
      // 放在这里而不是顶栏：抽屉是「去哪」的清单，设置属于其中之一；
      // 顶栏那颗汉堡键的语义是「打开清单」，不是「打开设置」。
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .clickable { onOpenSettings() }
                  .padding(horizontal = Spacing.lg, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
            FeatherIcons.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "设置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = Spacing.md),
        )
      }
    }
  }
}

/** 单条会话：标题 + 相对时间；右侧删除按钮。 */
@Composable
private fun SessionRowItem(
    session: SessionRow,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
  Surface(
      shape = RoundedCornerShape(10.dp),
      color =
          if (selected) MaterialTheme.colorScheme.primaryContainer
          else androidx.compose.ui.graphics.Color.Transparent,
      modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = 2.dp),
  ) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = Spacing.md, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          FeatherIcons.MessageSquare,
          contentDescription = null,
          tint =
              if (selected) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp),
      )
      Column(
          modifier = Modifier.weight(1f).padding(start = Spacing.md),
          verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
            text = session.title.ifBlank { "未命名会话" },
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = relativeTime(session.updatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      androidx.compose.material3.IconButton(onClick = onDelete) {
        Icon(
            FeatherIcons.Trash2,
            contentDescription = "删除会话",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}

/**
 * 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 日期。
 *
 * <p>不引 `DateUtils`：它按系统 locale 输出「3 分钟前」，但中英文混排时长度差异大，
 * 而会话行只有一行宽度。手写反而可控。
 */
private fun relativeTime(epochMs: Long): String {
  if (epochMs <= 0L) return ""
  val delta = System.currentTimeMillis() - epochMs
  return when {
    delta < 60_000L -> "刚刚"
    delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
    delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
    delta < 7 * 86_400_000L -> "${delta / 86_400_000L} 天前"
    else -> {
      val fmt = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
      fmt.format(java.util.Date(epochMs))
    }
  }
}
