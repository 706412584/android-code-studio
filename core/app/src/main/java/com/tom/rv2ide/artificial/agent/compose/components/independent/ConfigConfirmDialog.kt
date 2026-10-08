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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tom.rv2ide.resources.R

/**
 * 配置变更确认弹窗（Aharou 配置通道：写入前由用户批准，120 秒窗口）。
 * 自 上游项目 的 ConfigConfirmDialog 移植（裁剪：暂不逐行开关）。
 */
@Composable
internal fun ConfigConfirmDialog(
    change: PendingConfigChange,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.config_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    change.caption ?: stringResource(R.string.config_confirm_fallback_caption),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                change.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${item.oldDisplay}  →  ${item.newDisplay}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(item.path, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onApprove) { Text(stringResource(R.string.config_confirm_approve)) } },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.config_confirm_reject)) } },
    )
}
