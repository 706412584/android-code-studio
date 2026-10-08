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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing

/**
 * 工具详情页的载荷：当前要展开看的那条工具消息。
 *
 * <p>与 Aharou 的 `FilePreviewHolder` 同构——走「静态持有 + 路由」而不是导航参数：
 * 工具结果可能有几十万字符，塞进导航参数会把它序列化进 Bundle。
 *
 * <p>注意 `LocalToolPreviewOpener`（打开详情页的入口）**不在这里**：它已由 compat 统一定义，
 * 本文件从那里 import。Aharou 原版把两者放在同一个文件里，移植时若照搬会与 compat 的同名
 * 声明撞车（同包重复声明直接编译失败），故只保留 holder。
 */
internal object ToolCallPreviewHolder {
  var current: AgentUIMessage? = null
}

/**
 * 工具调用详情页：把「指令」与「结果」铺满整屏看。
 *
 * <p>卡片里的片段要跟标题行挤在一起，几十万字符的构建日志/搜索结果在里面只能滚动看个大概；
 * 这里全文展示并允许选中复制（[SelectionContainer]）。
 *
 * <p>入口由宿主在导航层提供（`LocalToolPreviewOpener`），未提供时工具行上的详情按钮不显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallPreviewScreen(
    message: AgentUIMessage,
    onBack: () -> Unit,
) {
  val argsText = remember(message.toolArgs) { formatToolArgs(message.toolArgs) }
  val resultText = remember(message.id, message.content.length) { formatToolResult(message.content) }

  Scaffold(
      topBar = {
        TopAppBar(
            title = {
              Text(
                  text = message.toolName ?: ToolCardStrings.TOOL,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = ToolCardStrings.BACK)
              }
            })
      }) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
              if (!argsText.isNullOrBlank()) {
                ToolPreviewSection(label = ToolCardStrings.INSTRUCTION, body = argsText)
              }
              if (!resultText.isNullOrBlank()) {
                ToolPreviewSection(label = ToolCardStrings.RESULT, body = resultText)
              }
            }
      }
}

@Composable
private fun ToolPreviewSection(label: String, body: String) {
  Column(modifier = Modifier.padding(bottom = Spacing.md)) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Spacing.xs))
    SelectionContainer {
      Text(
          text = body,
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface)
    }
  }
}
