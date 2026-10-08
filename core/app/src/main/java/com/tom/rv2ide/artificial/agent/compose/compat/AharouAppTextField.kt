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

package com.tom.rv2ide.artificial.agent.compose.compat

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.semanticColors

/**
 * `com.aharou.core.ui.AppTextField` 的等价物。
 *
 * <p>Aharou 的通用输入框（12dp 圆角卡片质感 + 明暗两套底色），被询问面板用到。
 * 收进 compat 是因为它是 `core.ui` 的通用原子：本次只有 AskUserQuestionPanel 引用，
 * 但输入栏一族（后续接入任务）同样要用它 —— 各造一份就会出现两套输入框配色。
 *
 * <p>同时提供弹窗专用的 [dialogTextFieldColors]，让输入框透出弹窗底色。
 */

/**
 * 全局统一的 App 输入框颜色规范：
 * - 浅色模式：主题卡片色背景 + 柔和外边框 + 聚焦主色高光；
 * - 暗色模式：深色表面底色（surface/surfaceVariant）+ 描边 + 聚焦主色微光。
 */
@Composable
fun dialogTextFieldColors(): TextFieldColors =
    appTextFieldColors(
        unfocusedContainerColor = Color.Transparent,
        focusedContainerColor = Color.Transparent)

@Composable
fun appTextFieldColors(
    isLight: Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f,
    unfocusedContainerColor: Color = MaterialTheme.semanticColors.cardSurface,
    focusedContainerColor: Color =
        if (isLight) MaterialTheme.semanticColors.cardSurface
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
): TextFieldColors {
  return OutlinedTextFieldDefaults.colors(
      unfocusedBorderColor =
          if (isLight) MaterialTheme.colorScheme.outlineVariant
          else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
      focusedBorderColor = MaterialTheme.colorScheme.primary,
      unfocusedContainerColor = unfocusedContainerColor,
      focusedContainerColor = focusedContainerColor,
      focusedLabelColor = MaterialTheme.colorScheme.primary,
      unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
      cursorColor = MaterialTheme.colorScheme.primary,
      focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
      unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
      focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
      unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
      // 禁用态沿用未聚焦时的底色：M3 默认把 disabled 容器色置为透明，enabled 一变背景就在有色与透明之间跳一下，
      // 看着就是输入框在闪。禁用语义交给较浅的边框与文字表达。
      disabledContainerColor = unfocusedContainerColor,
      disabledBorderColor =
          if (isLight) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
          else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

/**
 * 全局统一的基础输入框组件：
 * 采用 12dp 圆角卡片质感，自动适配浅色纯白/深色深蓝背景，支持前缀/后缀图标与完整键盘配置。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: String? = null,
    placeholder: String? = null,
    labelComposable: (@Composable () -> Unit)? = null,
    placeholderComposable: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: Shape = RoundedCornerShape(Radius.mdLarge),
    colors: TextFieldColors = appTextFieldColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
  val actualLabel: (@Composable () -> Unit)? = labelComposable ?: label?.let { { Text(it) } }
  val actualPlaceholder: (@Composable () -> Unit)? =
      placeholderComposable ?: placeholder?.let { { Text(it) } }

  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = modifier,
      enabled = enabled,
      readOnly = readOnly,
      textStyle = textStyle,
      label = actualLabel,
      placeholder = actualPlaceholder,
      leadingIcon = leadingIcon,
      trailingIcon = trailingIcon,
      supportingText = supportingText,
      isError = isError,
      visualTransformation = visualTransformation,
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      singleLine = singleLine,
      maxLines = maxLines,
      minLines = minLines,
      interactionSource = interactionSource,
      shape = shape,
      colors = colors)
}
