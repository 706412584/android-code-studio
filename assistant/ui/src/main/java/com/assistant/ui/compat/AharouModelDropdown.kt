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

package com.assistant.ui.compat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 模型选择框：**手填 + 下拉二合一**，形制照 ACS 的 `MaterialAutoCompleteTextView`。
 *
 * <p><b>为什么必须是下拉而不是纯输入框</b>：模型名要一字不差才能命中服务端
 * （`deepseek-chat` 写成 `deepseek_chat` 就是 404），而用户没法凭记忆写对。
 * ACS 的做法是「拉取模型清单 → 注入下拉 → 可选可输」，本组件等价实现。
 *
 * <p><b>为什么保留手填</b>：有些服务商不实现 `/models`，或用户想用清单外的模型名
 * （私有部署、别名）。纯下拉会把这类用法堵死。
 *
 * @param choices 可选模型清单；为空时退化成普通输入框（拉取前就是这个状态）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDropdownField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    choices: List<String>,
    placeholder: String = "",
    modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  // 没有候选时不用 ExposedDropdownMenuBox：它会渲染一个永远为空的下拉箭头，
  // 用户点了没反应，看起来像坏了。
  if (choices.isEmpty()) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
    return
  }

  ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = { expanded = it },
      modifier = modifier.fillMaxWidth(),
  ) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = true,
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      // 限高 + 内滚：某些服务商的 /models 有几百个（OpenRouter 等），
      // 不限高会把整个对话框撑出屏幕。
      androidx.compose.foundation.layout.Column(
          modifier =
              Modifier.heightIn(max = 280.dp)
                  .verticalScroll(rememberScrollState()),
      ) {
        choices.forEach { model ->
          DropdownMenuItem(
              text = {
                Text(
                    text = model,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color =
                        if (model == value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                )
              },
              onClick = {
                onValueChange(model)
                expanded = false
              },
          )
        }
      }
    }
  }
}
