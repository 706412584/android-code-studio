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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistant.ui.compat.AgentMode
import com.assistant.ui.compat.ReasoningEffort
import com.assistant.ui.components.independent.ChatHeader
import com.assistant.ui.components.independent.ChatInputBar
import com.assistant.ui.components.independent.settingsPageBackground
import com.assistant.ui.theme.AIEditorTheme

/** 抽屉右侧关闭条的宽度。固定 dp，不按屏宽比例算（见抽屉布局的注释）。 */
private val CLOSE_STRIP_WIDTH = 56.dp

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
    /** 输入框初值。宿主在「把文件加入输入框」后靠它把内容推进来。 */
    initialInput: String = "",
    /** 初值被采纳后通知宿主清空（否则下次重组会再次覆盖用户的输入）。 */
    onInitialInputConsumed: () -> Unit = {},
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenSessions: () -> Unit,
    onNewChat: () -> Unit,
    /**
     * 抽屉内容（会话列表）。非 null 且 [drawerVisible] 为 true 时盖在整页之上。
     *
     * <p>做成插槽而不是写死 [AssistantSessionsDrawer]：宿主可能想换成别的抽屉
     * （将来的「文件」Tab），而这一层不该替它决定。
     */
    drawer: (@Composable () -> Unit)? = null,
    drawerVisible: Boolean = false,
    /** 点遮罩关闭抽屉。抽屉打开时点空白处必须能关，否则用户只能按返回键。 */
    onDismissDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    /** 消息渲染区的外观（卡片缩放 / 正文颜色），来自设置页。 */
    style: com.assistant.ui.theme.AssistantStyle = com.assistant.ui.theme.AssistantStyle(),
    /**
     * 待发送附件（由宿主持有）。
     *
     * <p><b>为什么附件状态在宿主而不在这里</b>：选取动作（SAF 启动器）必须由 Activity
     * 注册——`rememberLauncherForActivityResult` 拿不到 Activity 之外的结果回调。
     * 因此这里只负责渲染与转发，选取与落盘在 [ChatActivity]。
     */
    attachments: List<com.assistant.ui.compat.PendingUploadAttachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    /** 读取附件正文（消息气泡点开附件时用）。 */
    onReadAttachment: suspend (String) -> String? = { null },
    /** 是否还能再加附件（到上限时灰掉入口）。 */
    canUploadFiles: Boolean = false,
    /** 能否选图片；与 [canUploadFiles] 分开：图片要额外做 base64。 */
    canUploadImages: Boolean = false,
    onUploadFile: () -> Unit = {},
    onUploadImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
) {
  AIEditorTheme(darkTheme = darkTheme, followHostFrame = false, style = style) {
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(settingsPageBackground())
                // 键盘弹出时把整列顶上去：输入栏必须始终可见，
                // 否则用户打字时看不到自己输入的内容。
                .imePadding(),
    ) {
      // ---- 顶栏 ----
      // 与 ACS/Aharou 一致：汉堡 + 右侧一排图标。
      // 本 App 没有 Git / 终端 / 会话树，因此：
      //   - 汉堡 → 打开设置（这里唯一能去的地方，语义上对应「侧栏」）
      //   - +    → 新会话
      //   - 全屏 → 沉浸阅读（隐藏顶栏与输入栏）
      ChatHeader(
          inputTokens = 0,
          outputTokens = 0,
          onOpenDrawer = onOpenSessions,
          onNewChat = onNewChat,
          // Git / 终端在本 App 尚不存在，传空实现而不是隐藏——
          // ChatHeader 没有「隐藏单个按钮」的参数，隐藏要改组件；
          // 空实现至少保持顶栏与参考项目同形（用户要求一比一）。
          onNavigateToTerminal = {},
          onNavigateToGit = {},
          // 全屏按钮不提供：本 App 的对话页本来就是全屏，这颗没有对应动作。
          // 传 null 时 ChatHeader 不渲染它。
          onToggleFullscreen = null,
          // 参考项目 Aharou 的顶栏左侧是「标题 + 模型名」两行，ACS 留空。
          // 这里按 Aharou 来：标题用会话名，副标题用模型名（带服务商 logo）。
          title = conversationTitle,
          subtitle = modelName,
          subtitleIcon = providerIcon,
      )

      // ---- 消息列表 ----
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { chatView },
            modifier = Modifier.fillMaxSize(),
        )
      }

      // ---- 输入栏 ----
      var text by remember { mutableStateOf(initialInput) }
      // 宿主推来新初值时同步进输入框（文件加入 / 引用历史消息）。
      // 用 LaunchedEffect(initialInput) 而不是直接把 initialInput 当 value：
      // 后者会让输入框变成受控组件，用户打字立刻被宿主值覆盖。
      androidx.compose.runtime.LaunchedEffect(initialInput) {
        if (initialInput.isNotEmpty()) {
          text = initialInput
          onInitialInputConsumed()
        }
      }
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
            pendingAttachments = attachments,
            onRemoveAttachment = onRemoveAttachment,
            onReadAttachment = onReadAttachment,
            // 忙碌时不允许再加：附加的图片会随"当前这条"发出，而这条已在跑，
            // 加进去只会让人以为它属于本轮（ChatInputBar 内部也会再按 isBusy 收一道）。
            canUploadFiles = canUploadFiles && !isBusy,
            canUploadImages = canUploadImages && !isBusy,
            onUploadFile = onUploadFile,
            onUploadImage = onUploadImage,
            onTakePhoto = onTakePhoto,
        )
      }
    }

    // ---- 抽屉（会话列表） ----
    // 盖在整页之上而不是并排：手机宽度放不下「抽屉 + 对话」两栏，
    // 并排会把两边都挤到不可用（与 ACS 的侧栏判断同一取舍）。
    if (drawerVisible && drawer != null) {
      // 遮罩只盖**抽屉之外**的右侧区域，不做整屏遮罩。
      //
      // <p>整屏遮罩 + 抽屉叠放时，Compose 的命中测试会让遮罩抢走抽屉内的点击
      // （实测：点删除键结果是「关闭抽屉」）。把遮罩缩成右侧一条后两者互不重叠，
      // 点击归属没有歧义。左侧抽屉保持 86% 宽，右侧露出的 14% 就是关闭区。
      Box(modifier = Modifier.fillMaxSize()) {
        // 抽屉：先声明，在下层
        Surface(
            modifier =
              Modifier.fillMaxHeight()
                  .fillMaxWidth()
                  .padding(end = CLOSE_STRIP_WIDTH)
                  .align(androidx.compose.ui.Alignment.TopStart),
            color = settingsPageBackground(),
        ) {
          drawer()
        }
        // 右侧关闭区：后声明，在上层，但只占右边那条。
        //
        // <p>用固定 dp 宽度而不是 `fillMaxWidth(0.14f)`：后者在 fillMaxHeight 之后
        // 参与约束求解时算出的宽度不稳定（实测覆盖到了抽屉内部，点会话行变成关闭抽屉）。
        // 抽屉宽 = 屏宽 - 关闭区宽，两边都不依赖分数计算。
        Box(
            modifier =
                Modifier.fillMaxHeight()
                    .width(CLOSE_STRIP_WIDTH)
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .background(androidx.compose.ui.graphics.Color(0x66000000))
                    .clickable(
                        indication = null,
                        interactionSource =
                            remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        onClick = onDismissDrawer,
                    ),
        )
      }
    }
    }
  }
}
