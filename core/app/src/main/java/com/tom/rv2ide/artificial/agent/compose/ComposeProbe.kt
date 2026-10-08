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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Compose 接入探针。
 *
 * <p><b>用途</b>：验证 ACS 能编译并运行 Compose 代码。这是移植 Aharou 消息渲染层之前的
 * 最小可编译单元——若连它都编不过，说明 Compose 工具链没接好，不必往下走。
 *
 * <p>探针本身可随时删除，不影响其它功能。
 */
@Composable
fun ComposeProbe(text: String, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth().background(Color.Transparent).padding(8.dp)) {
    Text(text = text, color = MaterialTheme.colorScheme.onSurface)
  }
}
