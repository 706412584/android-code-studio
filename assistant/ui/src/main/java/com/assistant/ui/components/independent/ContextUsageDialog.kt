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

package com.assistant.ui.components.independent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.assistant.ui.theme.Spacing

/**
 * 上下文占用详情。
 *
 * <p><b>为什么需要它</b>：输入栏的占用环只表达「占了多少」一个比例。用户看见环快满了
 * 时会问两件事：具体是多少 token、我能不能腾出空间。前者这里给出，后者的入口是
 * [onCompact]。
 *
 * <p><b>为什么不做「自动压缩」按钮</b>：压缩由内核在**每轮对话入口**执行
 * （需要条目序号、结果必须落盘）。在 UI 上直接触发它，要么绕开那条路径让结果不落盘、
 * 要么假装压缩了。这里只提供「请求压缩」并说明它何时生效，不假装即时完成。
 */
@Composable
fun ContextUsageDialog(
    usedTokens: Int,
    windowTokens: Int,
    /** 请求压缩上下文；null 时该项不显示（宿主没接这个能力）。 */
    onCompact: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
  val ratio = if (windowTokens > 0) (usedTokens.toFloat() / windowTokens).coerceIn(0f, 1f) else 0f
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("上下文占用") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          Text(
              text = "$usedTokens / $windowTokens token",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
          )
          LinearProgressIndicator(
              progress = { ratio },
              modifier = Modifier.fillMaxWidth(),
          )
          Text(
              text = "已用 ${(ratio * 100).toInt()}%",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (onCompact != null) {
            Text(
                text = "接近上限时内核会在下一轮对话开始前自动压缩历史；" +
                    "也可以点下面的按钮提前触发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      },
      confirmButton = {
        if (onCompact != null) {
          TextButton(
              onClick = {
                onCompact()
                onDismiss()
              }) {
                Text("压缩上下文")
              }
        } else {
          TextButton(onClick = onDismiss) { Text("关闭") }
        }
      },
      dismissButton = if (onCompact != null) {
        { TextButton(onClick = onDismiss) { Text("关闭") } }
      } else {
        null
      },
  )
}
