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

package com.tom.rv2ide.artificial.agent.compose.compat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 与 Material3 `AlertDialog` 外观一致、但窗口创建走 overlay 的弹窗。
 *
 * <p><b>为什么不能直接用 Material3 `AlertDialog`</b>：它内部用
 * `androidx.compose.ui.window.Dialog`，默认 window type 是 `TYPE_APPLICATION`
 * （需要 Activity token）。悬浮窗宿主是 Service，没有 token → `BadTokenException`
 * （实测：悬浮窗里长按会话 → 删除 → 点击确认直接崩）。本组件用
 * [OverlayAwareDialog]——overlay 下走原生 Dialog + `TYPE_APPLICATION_OVERLAY`，
 * Activity 下走普通 Compose Dialog。调用点只需把 `AlertDialog(...)` 换成
 * `OverlayAlertDialog(...)`，参数完全同名。
 *
 * <p>排版复刻 Material3 `AlertDialog` 的默认外观（28dp 圆角、24dp 内边距、
 * headlineSmall 标题 / bodyMedium 正文 / 右下按钮组）。颜色全部走
 * `MaterialTheme.colorScheme`——**深色模式下标题/正文自动为浅色**，
 * 不会出现「深色背景上黑色文字看不见」（用户实测反馈）。
 */
@Composable
fun OverlayAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
  OverlayAwareDialog(onDismissRequest = onDismissRequest) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        if (title != null) {
          CompositionLocalProvider(
              androidx.compose.material3.LocalTextStyle provides
                  MaterialTheme.typography.headlineSmall,
              androidx.compose.material3.LocalContentColor provides
                  MaterialTheme.colorScheme.onSurface,
          ) {
            title()
          }
        }
        if (text != null) {
          CompositionLocalProvider(
              androidx.compose.material3.LocalTextStyle provides
                  MaterialTheme.typography.bodyMedium,
              androidx.compose.material3.LocalContentColor provides
                  MaterialTheme.colorScheme.onSurfaceVariant,
          ) {
            Column(modifier = Modifier.padding(top = 16.dp)) { text() }
          }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.End,
        ) {
          if (dismissButton != null) {
            dismissButton()
          }
          confirmButton()
        }
      }
    }
  }
}
