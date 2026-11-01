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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp

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

  // 有候选时：整行可点，点开在**下方**列出候选。
  //
  // <p><b>为什么不用 ExposedDropdownMenuBox</b>：它在只读模式下
  // （`PrimaryNotEditable`）的点击区只覆盖文字本身，点到输入框的内边距会穿透到
  // 下面的控件（实测：点下拉后焦点跳到了 API Key 输入框）；可编辑模式又会弹键盘
  // 把候选挤到输入框上面、只露出一个（实测）。两种模式都不合用。
  //
  // <p><b>手填仍要支持</b>：有些服务商不实现 `/models`，那时 `choices` 为空，
  // 上面的分支给出普通可编辑输入框。
  //
  // <p><b>不用键盘</b>：选择动作不需要输入，因此这里根本不涉及焦点与软键盘
  // （与 ACS 的 `MaterialAutoCompleteTextView + inputType="none"` 同一取舍）。
  Box(modifier = modifier.fillMaxWidth()) {
    // 只读外观的输入框：显示当前值 + 右侧箭头。整块区域响应点击。
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                if (expanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            ),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = value.ifEmpty { placeholder },
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector =
                if (expanded) FeatherIcons.ChevronUp
                else FeatherIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
      }
    }
    // 字段名浮在边框上（Material 的 outlined 样式）。
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp)
                .align(Alignment.TopStart)
                .offset(x = 12.dp, y = (-8).dp),
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.heightIn(max = 280.dp),
    ) {
      // 限高 + 内滚：某些服务商的 /models 有几百个（OpenRouter 等），
      // 不限高会把整个对话框撑出屏幕。
      Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
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
