/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/**
 * 读 ACS 主题里的颜色属性（如 `?attr/colorBackground`）。
 *
 * <p><b>为什么不用 `MaterialTheme.colorScheme.*`</b>：管理页是 IDE 里的普通页面，
 * 用户可能在四个主题（blue_wave / greenish / sunny_glow / vscode）间切换；Compose 侧
 * 的色板来自助手面板那套预设，与 IDE 主题是两套来源。直接读主题属性才能与设置页、
 * 编辑器侧栏完全同色（用户实测反馈：管理页背景没有跟随 IDE 配色）。
 *
 * @return 解析失败时返回 null，调用方退回 [MaterialTheme] 的对应槽位。
 */
@Composable
internal fun ideThemeColor(
    attrId: Int,
    fallback: Color = Color.Unspecified,
): Color {
  val context = LocalContext.current
  val resolved =
      remember(context, attrId) {
        val typed = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attrId, typed, true)) {
          null
        } else if (typed.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util
            .TypedValue.TYPE_LAST_COLOR_INT) {
          Color(typed.data)
        } else {
          runCatching {
            Color(androidx.core.content.ContextCompat.getColor(context, typed.resourceId))
          }.getOrNull()
        }
      }
  return resolved ?: if (fallback == Color.Unspecified) MaterialTheme.colorScheme.background else fallback
}

/**
 * AI 管理页的统一外壳：返回 + 标题（+ 可选动作槽）+ 内容 + 可选固定底栏。
 *
 * <p>所有管理/编辑页面共用一个外壳，版式一致（服务商、MCP、记忆、Skill、Agent…）。
 *
 * <p><b>edge-to-edge</b>：页面是普通界面（系统栏可见），用 [safeDrawingPadding] 避让
 * 状态栏/导航栏，否则顶栏被状态栏压住、底部动作条被导航栏压住。
 *
 * <p><b>底栏不随内容滚动</b>：长表单的保存按钮若随内容滚，用户滚到底前看不到保存入口。
 * 放在 [bottomBar] 即固定在内容区之下。
 */
@Composable
internal fun ManagePage(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
  // 背景取 IDE 主题的 android:colorBackground，与设置页（PreferencesActivity）同色。
  //
  // <p>此前用 settingsPageBackground()（助手面板语义色 pageBackground），那是**助手面板**
  // 那套预设（默认蓝 / 咖啡 / 石墨 / 森林）里的颜色，与用户在 IDE 设置里选的主题
  // （blue_wave / greenish / sunny_glow / vscode）无关——所以管理页与设置页明显不同色
  // （用户实测反馈）。这里改成直接读主题属性。
  val background = ideThemeColor(android.R.attr.colorBackground)
  Column(
      modifier = Modifier.fillMaxSize().background(background).safeDrawingPadding(),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(
            FeatherIcons.ArrowLeft,
            contentDescription = stringResource(R.string.back),
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp),
        )
      }
      Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f).padding(start = Spacing.xs),
      )
      if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
    Box(modifier = Modifier.weight(1f)) { content() }
    if (bottomBar != null) bottomBar()
  }
}

/**
 * 编辑页的固定底栏：取消 + 主操作。两个按钮的排布与文案在整 App 保持一致。
 *
 * @param confirmText 主操作文案（用具体动词：保存/添加/删除…，不用「确定」）
 * @param confirming 主操作进行中：显示进行文案并禁用两键，避免重复提交
 */
@Composable
internal fun ManageBottomBar(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    // 这是 @Composable 函数，默认参数在 Composable 作用域内求值，可直接取资源。
    confirmText: String = stringResource(R.string.save),
    confirmingText: String = stringResource(R.string.manage_saving),
    confirming: Boolean = false,
    confirmEnabled: Boolean = true,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              .padding(horizontal = Spacing.md, vertical = Spacing.sm),
      horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm),
  ) {
    androidx.compose.material3.TextButton(onClick = onCancel, enabled = !confirming) {
      Text(stringResource(R.string.cancel))
    }
    androidx.compose.material3.Button(
        onClick = onConfirm,
        enabled = !confirming && confirmEnabled,
        modifier = Modifier.weight(1f),
    ) {
      Text(if (confirming) confirmingText else confirmText)
    }
  }
}
