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

package com.tom.rv2ide.artificial.agent.compose.components.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage
import com.tom.rv2ide.artificial.agent.compose.model.MessageRole
import com.tom.rv2ide.artificial.agent.compose.model.hasVisibleContent
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.tom.rv2ide.resources.R

/** 宽屏下模态弹窗的最大宽度：再宽一行文案过长，视线来回扫描成本高。 */
private val DialogMaxWidth = 560.dp

/** 紧凑屏与宽屏的分界宽度（与 Aharou `WindowSize.kt` 的 COMPACT 档一致）。 */
private const val COMPACT_WIDTH_DP = 600

/** 面板菜单文案。TODO(i18n)：Aharou 用 `R.string.chat_more_options` / `chat_action_*`，ACS 侧先用常量。 */
private object MessageActionStrings {
  val MORE_OPTIONS: String @Composable get() = stringResource(R.string.compose_message_action_more_options)
  val EDIT: String @Composable get() = stringResource(R.string.compose_message_action_edit)
  val COPY: String @Composable get() = stringResource(R.string.compose_message_action_copy)
  val REGENERATE: String @Composable get() = stringResource(R.string.compose_message_action_regenerate)
  val DELETE: String @Composable get() = stringResource(R.string.compose_message_action_delete)
}

/**
 * 当前窗口是否为紧凑宽度（手机竖屏、窄分屏）。
 *
 * <p>只取 [LocalConfiguration] 的 `screenWidthDp` 与 600dp 断点比较，不引
 * material3-window-size-class：本组只为一处模态宿主做粗分流，不值得为它加一个依赖，
 * 且折叠屏/分屏下 `screenWidthDp` 已是窗口宽度而非物理屏宽，判定正确。
 */
@Composable
private fun isCompactWidth(): Boolean =
    LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP

/**
 * 自适应模态底栏/弹窗：
 * - 紧凑屏幕（手机竖屏、窄分屏等，< 600dp）：呈现为标准贴底 [ModalBottomSheet]，保留下拉手势与拖拽手柄；
 * - 宽屏/平板（>= 600dp）：呈现为规范的居中模态 [Dialog]，移除拖拽手柄，限制最大宽度并居中展示。
 *
 * <p>移植说明：Aharou 原版还从这里导出一个 fling 修复连接（`rememberSheetFlingFix`）以规避
 * Material3 ModalBottomSheet 的已知振荡 bug，并为通用宿主暴露了 shape / scrim / insets /
 * properties / `sheetGesturesEnabled` 等一整套参数。本处只服务 [MessageActionsBottomSheet]
 * 这一个「短列表」宿主——内容不会接近全屏高度，振荡前提不成立，故只保留实际用到的参数，
 * 不搬那套未被任何调用方使用的参数面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdaptiveModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
  if (isCompactWidth()) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor) {
          Column(modifier = Modifier.fillMaxWidth()) { content() }
        }
  } else {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
          Surface(
              modifier =
                  Modifier.widthIn(min = 280.dp, max = DialogMaxWidth)
                      .fillMaxWidth()
                      .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
                      .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp),
              shape = RoundedCornerShape(16.dp),
              color = containerColor,
              tonalElevation = 0.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)) { content() }
              }
        }
  }
}

/**
 * 消息操作面板：编辑 / 复制 / 重新生成 / 删除。
 *
 * <p>[onRegenerateClick] 只对「有正文的助手回答」显示：重新生成会产生新版本，
 * 旧回答保留成变体由气泡下方的 `‹ n/N ›` 切换，因此对工具消息、纯图片消息不提供。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionsBottomSheet(
    message: AgentUIMessage,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit,
    onCopyClick: () -> Unit,
    onDeleteClick: () -> Unit,
    /** 重新生成：仅助手回答（有正文）提供，生成新版本而不覆盖当前回答。 */
    onRegenerateClick: (() -> Unit)? = null
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  AdaptiveModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.lg)) {
          Text(
              text = MessageActionStrings.MORE_OPTIONS,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))

          Spacer(modifier = Modifier.height(Spacing.xs))

          // 编辑消息
          MessageActionItem(
              icon = FeatherIcons.Edit2,
              title = MessageActionStrings.EDIT,
              onClick = {
                onDismiss()
                onEditClick()
              })

          // 复制文本：纯图片消息没有文字可复制，隐藏该项
          if (message.content.hasVisibleContent()) {
            MessageActionItem(
                icon = FeatherIcons.Copy,
                title = MessageActionStrings.COPY,
                onClick = {
                  onDismiss()
                  onCopyClick()
                })
          }

          // 重新生成：旧回答保留成变体，气泡下方用 ‹ n/N › 切换
          if (onRegenerateClick != null &&
              message.role == MessageRole.ASSISTANT &&
              message.content.hasVisibleContent()) {
            MessageActionItem(
                icon = FeatherIcons.RefreshCw,
                title = MessageActionStrings.REGENERATE,
                onClick = {
                  onDismiss()
                  onRegenerateClick()
                })
          }

          // 删除消息
          MessageActionItem(
              icon = FeatherIcons.Trash2,
              title = MessageActionStrings.DELETE,
              isDestructive = true,
              onClick = {
                onDismiss()
                onDeleteClick()
              })
        }
      }
}

@Composable
private fun MessageActionItem(
    icon: ImageVector,
    title: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
  val color =
      if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

  Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.md),
      verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = title, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = color)
      }
}
