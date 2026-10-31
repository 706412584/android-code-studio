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

package com.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.assistant.ui.compat.AgentMode
import com.assistant.ui.compat.ReasoningEffort
import com.assistant.ui.components.independent.ChatHeader
import com.assistant.ui.components.independent.ChatInputBar
import com.assistant.ui.components.independent.settingsPageBackground
import com.assistant.ui.theme.AIEditorTheme

/**
 * 完整的对话页：**顶栏 + 消息列表 + 输入栏**，整页一个 Compose 树。
 *
 * <p><b>为什么必须是一个 Compose 树</b>：ACS 与 Aharou 的顶栏/输入栏都是
 * `MaterialTheme` 的下游——分别挂在不同的 `ComposeView` 里，每个都要自己包
 * `AIEditorTheme`，漏一个就会出现「消息区是蓝色、输入栏是 M3 默认紫」的割裂
 * （实测就是这样）。合成一棵树后，主题只在最外层包一次。
 *
 * <p><b>布局</b>：顶栏贴顶、输入栏贴底、消息列表占中间剩余高度。`imePadding()`
 * 让输入栏跟着键盘上移；`navigationBarsPadding()` 避免被手势条压住。
 *
 * @param chatView 消息面板（[AssistantChatView] 暴露的 View），用 AndroidView 嵌进来
 */
@Composable
fun AssistantChatScreen(
    chatView: android.view.View,
    /** 顶栏副标题：当前模型名（null 时不显示副标题）。 */
    modelName: String?,
    /** 顶栏标题：会话名。 */
    conversationTitle: String = "新的对话",
    /** 顶栏副标题前的服务商图标；null 时不显示。 */
    providerIcon: (@Composable () -> Unit)? = null,
    isBusy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  // 沉浸阅读：隐藏顶栏与输入栏，只留消息。ACS 的「全屏」是换宿主，
  // 本 App 本来就是全屏，所以这颗按钮改做「无干扰阅读」。
  var immersive by remember { mutableStateOf(false) }

  AIEditorTheme(darkTheme = darkTheme, followHostFrame = false) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(settingsPageBackground())
                // 键盘弹出时把整列顶上去：输入栏必须始终可见，
                // 否则用户打字时看不到自己输入的内容。
                .imePadding(),
    ) {
      if (!immersive) {
      // ---- 顶栏 ----
      // 与 ACS/Aharou 一致：汉堡 + 右侧一排图标。
      // 本 App 没有 Git / 终端 / 会话树，因此：
      //   - 汉堡 → 打开设置（这里唯一能去的地方，语义上对应「侧栏」）
      //   - +    → 新会话
      //   - 全屏 → 沉浸阅读（隐藏顶栏与输入栏）
      ChatHeader(
          inputTokens = 0,
          outputTokens = 0,
          onOpenDrawer = onOpenSettings,
          onNewChat = onNewChat,
          // Git / 终端在本 App 尚不存在，传空实现而不是隐藏——
          // ChatHeader 没有「隐藏单个按钮」的参数，隐藏要改组件；
          // 空实现至少保持顶栏与参考项目同形（用户要求一比一）。
          onNavigateToTerminal = {},
          onNavigateToGit = {},
          // 全屏按钮：ACS 的顶栏有这颗（↗）。本 App 的对话页本来就是全屏，
          // 因此它的语义改成「进入无干扰阅读模式」——点一下隐藏顶栏与输入栏，
          // 再点恢复。传 null 会整颗不渲染，与参考项目的顶栏形状不一致。
          isFullscreen = immersive,
          onToggleFullscreen = { immersive = !immersive },
          // 参考项目 Aharou 的顶栏左侧是「标题 + 模型名」两行，ACS 留空。
          // 这里按 Aharou 来：标题用会话名，副标题用模型名（带服务商 logo）。
          title = conversationTitle,
          subtitle = modelName,
          subtitleIcon = providerIcon,
      )
      }

      // ---- 消息列表 ----
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { chatView },
            modifier = Modifier.fillMaxSize(),
        )
      }

      // ---- 输入栏 ----
      if (!immersive) {
      var text by remember { mutableStateOf("") }
      var mode by remember { mutableStateOf(AgentMode.BUILD) }
      var reasoning by remember { mutableStateOf(ReasoningEffort.DEFAULT) }
      Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        ChatInputBar(
            value = text,
            onValueChange = { text = it },
            onSend = { value ->
              text = ""
              onSend(value)
            },
            onStop = onStop,
            isBusy = isBusy,
            activeModelName = modelName,
            providers = emptyList(),
            currentMode = mode,
            onToggleMode = { mode = it },
            reasoningEffort = reasoning,
            onReasoningEffortChange = { reasoning = it },
            pendingAttachments = emptyList(),
            onRemoveAttachment = {},
            onReadAttachment = { null },
            canUploadFiles = false,
            canUploadImages = false,
            onUploadFile = {},
            onUploadImage = {},
            onTakePhoto = {},
        )
      }
      }
    }
  }
}
