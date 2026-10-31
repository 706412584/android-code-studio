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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.compat.SessionUseCase
import com.tom.rv2ide.artificial.agent.compose.components.bubbles.RippleText
import com.tom.rv2ide.artificial.agent.compose.components.style.BotGlyph
import com.tom.rv2ide.artificial.agent.compose.components.style.ChatCodeCard
import com.tom.rv2ide.artificial.agent.compose.components.style.ChatMonoPanel
import com.tom.rv2ide.artificial.agent.compose.components.style.ChatStyle
import com.tom.rv2ide.artificial.agent.compose.components.style.breakableText
import com.tom.rv2ide.artificial.agent.compose.components.style.rememberBoundNestedScrollConnection
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage
import com.tom.rv2ide.artificial.agent.compose.model.ToolRunStatus
import com.tom.rv2ide.artificial.agent.compose.theme.Brand
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.artificial.agent.compose.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Image
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.res.stringResource
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.artificial.agent.ToolResultImageSupport
import com.tom.rv2ide.artificial.agent.compose.compat.ImageSource
import com.tom.rv2ide.artificial.agent.compose.compat.LocalImageViewer
import com.tom.rv2ide.artificial.agent.compose.compat.ImageViewerRequest
import androidx.compose.material3.Surface

internal val DiffAddBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffAddBg

internal val DiffAddText: Color
    @Composable get() = MaterialTheme.semanticColors.diffAdd

internal val DiffRemoveBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemoveBg

internal val DiffRemoveText: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemove

internal const val DIFF_COLLAPSE_THRESHOLD = 20
internal const val TOOL_SECTION_LINE_LIMIT = 20

/** 工具「指令 / 结果」展开窗口的最大高度：超出后在窗口内滚动，不再无限撑高。 */
private val ToolSectionMaxHeight = 240.dp

/** 工具窗口底部渐隐遮罩高度（仅当内容还可继续下滚时显示）。 */
private val ToolSectionFadeHeight = 24.dp

/**
 * 工具卡文案。
 *
 * <p>TODO(i18n)：Aharou 原版用 `com.aharou.R.string.*` 引用（`common_tool` / `tool_instruction` /
 * `tool_result` / `common_expand` / `common_collapse_action` / `tool_expand_remaining` /
 * `tool_changed_files_summary` / `chat_tool_calls_count` / `chat_tool_running|succeeded|failed` /
 * `chat_status_editing_file` 等 9 条）。ACS 侧本次**不新增字符串资源**，先用常量承载中文文案，
 * 待接入时统一换成 `stringResource(...)`。
 */
internal object ToolCardStrings {
  val TOOL: String @Composable get() = stringResource(R.string.compose_tool_card_tool)
  val INSTRUCTION: String @Composable get() = stringResource(R.string.compose_tool_card_instruction)
  val RESULT: String @Composable get() = stringResource(R.string.compose_tool_card_result)
  val BACK: String @Composable get() = stringResource(R.string.compose_tool_card_back)
  val EXPAND: String @Composable get() = stringResource(R.string.compose_tool_card_expand)
  val COLLAPSE: String @Composable get() = stringResource(R.string.compose_tool_card_collapse)
  val RUNNING: String @Composable get() = stringResource(R.string.compose_tool_card_running)
  val SUCCEEDED: String @Composable get() = stringResource(R.string.compose_tool_card_succeeded)
  val FAILED: String @Composable get() = stringResource(R.string.compose_tool_card_failed)
  val TODO_COMPLETED: String @Composable get() = stringResource(R.string.compose_tool_card_todo_completed)
  val TODO_IN_PROGRESS: String @Composable get() = stringResource(R.string.compose_tool_card_todo_in_progress)
  /** 子代理「查看结果」入口（cc-haha AgentCallCard 的按钮同文案）。 */
  val VIEW_RESULT: String @Composable get() = stringResource(R.string.compose_tool_card_view_result)
  val WEB_SEARCH_TITLE: String @Composable get() = stringResource(R.string.compose_tool_card_web_search_title)
  val IMAGE_PREVIEW_LABEL: String @Composable get() = stringResource(R.string.compose_tool_card_image_preview)
  val TAP_TO_VIEW: String @Composable get() = stringResource(R.string.compose_tool_card_tap_to_view)
  @Composable
  fun expandRemaining(hiddenCount: Any): String = stringResource(R.string.compose_tool_card_expand_remaining, hiddenCount)
  @Composable
  fun webSearchWarning(warning: Any): String = stringResource(R.string.compose_tool_card_web_search_warning, warning)
  @Composable
  fun webSearchResultsCount(count: Any): String = stringResource(R.string.compose_tool_card_web_search_results_count, count)
  @Composable
  fun changedFilesSummary(files: Any, added: Any, removed: Any): String = stringResource(R.string.compose_tool_card_changed_files_summary, files, added, removed)
  @Composable
  fun toolCallsCount(count: Any): String = stringResource(R.string.compose_tool_card_tool_calls_count, count)

  /**
   * 工具执行中的场景文案；未归类的工具回落「正在调用工具」。
   *
   * <p>保留 when 结构而不是做成一张「工具名 → 资源 id」的表：分支条件里有 `lowercase()`
   * 与多个别名（`editFile`/`edit_file` 等），表的键得先归一化才等价，反而更容易漏。
   */
  @Composable
  fun runningLabel(toolName: String?): String =
      when (toolName?.lowercase()) {
        "editfile", "writefile", "edit_file", "write_file" ->
            stringResource(R.string.compose_tool_card_running_edit)
        "readfile", "list", "sendfile", "viewimage", "read_file", "list_files" ->
            stringResource(R.string.compose_tool_card_running_read)
        "search", "websearch", "webfetch", "browser" ->
            stringResource(R.string.compose_tool_card_running_search)
        "bash", "terminal" -> stringResource(R.string.compose_tool_card_running_command)
        "generateimage" -> stringResource(R.string.compose_tool_card_running_image)
        "todo" -> stringResource(R.string.compose_tool_card_running_todo)
        "task" -> stringResource(R.string.compose_tool_card_running_task)
        "memory" -> stringResource(R.string.compose_tool_card_running_memory)
        else -> stringResource(R.string.compose_tool_card_running_default)
      }
}

/**
 * 工具消息（扁平行）：一行「工具图标 + 工具名 + 路径 + 增删统计 + 箭头」，行上方一条
 * 1px 细线做分隔，不再套描边卡片；点击展开查看「指令 / 结果」，或对 editFile / writeFile
 * 展开为白底描边的差异卡（头部路径 + 复制，页脚增删统计）。
 *
 * <p>状态不再只用颜色表达：运行中图标脉冲、失败额外转红，读屏另有状态语义。
 * [liveOutput] 非空时进入「实时输出」模式：显示逐行累积输出。
 *
 * <p><b>展开态由 [expandedOverride] + [onExpandedChange] 受控</b>（宿主持久化，对应 ACS 的
 * `AgentUIMessage.expanded`）：从前用 `remember(message.id)` 存在这里，item 滚出视口被
 * LazyColumn 回收、或全屏路由把整棵聊天组合 dispose 后就会缩回默认态。null 表示用户还没手动
 * 开关过——此时**默认收起**，所有工具（含差异卡、待办卡）一视同仁，看细节要点开。
 *
 * <p><b>与 Aharou 原版的两处接线差异</b>（均为「宿主能力不存在」而非行为裁剪）：
 * 1. 行点击从前是「掀开 Aharou Computer 面板」，ACS 无该面板，改为与行尾箭头同义的展开/收起。
 * 2. 「在浏览器中围观 URL」「在终端中运行命令」两个入口依赖 Aharou 的 browser / terminal
 *    特性，ACS 侧不引入；如需接 ACS 终端，由宿主另加 CompositionLocal 注入。
 */
@Composable
internal fun ToolMessageBody(
    message: AgentUIMessage,
    liveOutput: String? = null,
    expandedOverride: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onToggle: (() -> Unit)? = null
) {
  val streaming = liveOutput != null
  val running = message.isToolRunning(liveOutput)
  // 子代理派遣：命中就整行换成卡片（状态 + 任务 + 步骤 + 结论）。
  // 判定只看工具名与参数，不看 content，所以流式追加期间不会反复重解析。
  // 失败（isError）时不出卡：错误信息是模型要读的正文，普通工具行的结果区更合适。
  if (message.isSubAgentDispatchCard()) {
    SubAgentDispatchCard(message)
    return
  }
  // 解析结果的缓存键用 content 长度而不是整串内容：工具结果可以有几十万字符，
  // 逐次重组都拿整串做一次 O(n) 相等比较，本身就是可观的浪费。
  // 工具结果只会追加增长，长度相同即视为同一份内容。
  val contentKey = message.content.length
  // 解析（diff/todo/搜索/通知/路径等）从组合期移到 Default：工具结果可达数十万字符，主线程解析会掉帧。
  // 外层 key(...) 让 key 变化时整体重建（重新解析、丢弃旧输入的结果）；初值 null 表示尚未解析完成，
  // 各处按空值渲染，避免先画出空卡片或错内容。
  val derived by
      key(message.id, contentKey, message.toolName, message.isError, running, message.toolArgs) {
        produceState<ToolMessageDerived?>(initialValue = null) {
          value =
              withContext(Dispatchers.Default) {
                ToolMessageDerived(
                    edit =
                        if (!running &&
                            !message.isError &&
                            (message.toolName == "editFile" || message.toolName == "writeFile")
                        ) parseEditDiff(message.content)
                        else null,
                    resultText = if (!running) formatToolResult(message.content) else null,
                    argHint = toolArgHint(message.toolArgs),
                    argsFull = formatToolArgs(message.toolArgs),
                    todo =
                        if (message.toolName == "todo" && !running && !message.isError) {
                          parseTodoResult(message.content)
                        } else null,
                    webSearch =
                        if (message.toolName == "websearch" && !running && !message.isError) {
                          parseWebSearchResult(message.content)
                        } else null,
                    notifications =
                        if (!running) parseToolNotifications(message.content) else emptyList(),
                    filePath = extractFilePathArg(message.toolArgs),
                )
              }
        }
      }
  val edit = derived?.edit
  val resultText = derived?.resultText
  val argHint = derived?.argHint
  val argsFull = derived?.argsFull
  val todoData = derived?.todo
  val webSearchData = derived?.webSearch
  val notifications = derived?.notifications.orEmpty()

  // 执行中也可折叠/展开（如 bash 刷屏时可收起只看标题行），无论当前是否有输出；无输出时折叠态无内容，但保持可点击与箭头一致
  val hasLiveOutput = !liveOutput.isNullOrBlank()
  // derived == null（解析中）时保持可展开，避免箭头先缺失后补上。
  //
  // steps.isNotEmpty() 必须计入：子代理运行中（running=true、streaming=false、尚无结果
  // 文本）时，此前四个条件全不满足 → 箭头不显示，用户**看不到展开步骤的入口**，
  // 直到工具结束/失败（有结果文本）箭头才出现——实测反馈「失败后才显示展开图标」
  // 就是这个判据缺了 steps 造成的。运行中的子代理恰恰是最需要看步骤的时刻。
  val expandable =
      streaming ||
          message.steps.isNotEmpty() ||
          (!running &&
              (derived == null ||
                  edit != null ||
                  !resultText.isNullOrBlank() ||
                  !argsFull.isNullOrBlank() ||
                  (todoData != null && todoData.items.isNotEmpty()) ||
                  webSearchData != null))
  // 工具调用一律默认收起（差异卡、待办卡也不例外）：要不要看细节由用户点开，
  // 手动开关过（expandedOverride 非 null）就以用户的选择为准。展开态由宿主保管，
  // 因此滚出视口、切页返回都不会再丢。
  val effectiveExpanded = expandedOverride == true

  // 「查看结果」弹窗开关（子代理卡片专属，cc-haha AgentCallCard 的「查看结果」Modal 同款）：
  // 子代理的结论可能很长（调查结论/报告），内联渲染会把卡片撑得极高，独立弹窗里
  // Markdown 渲染 + 滚动阅读才可读。remember(message.id)：条目被回收时弹窗随之关闭。
  var showResultDialog by remember(message.id) { mutableStateOf(false) }

  val toolLabel = message.toolName ?: ToolCardStrings.TOOL
  // 文件相关工具：从结构化 diff 或工具参数里取路径，统一按「工具名 + 路径 + 文件名」展示
  val filePath = if (edit != null) edit.path else derived?.filePath
  // 显式标注返回类型：两个回调都是可空调用，lambda 末式会是 Unit?，
  // 直接推成 () -> Unit? 会与 clickable / pointerInput 要求的 () -> Unit 不匹配。
  val toggle: () -> Unit = {
    onExpandedChange?.invoke(!effectiveExpanded)
    onToggle?.invoke()
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = ChatStyle.toolRowMinHeight)
                // Aharou 此处进入「Aharou Computer」面板；ACS 无该面板，行点击与行尾箭头同义。
                .clickable(enabled = expandable, onClick = toggle),
        verticalAlignment = Alignment.CenterVertically) {
          ToolStatusIcon(
              running = running, isError = message.isError, toolName = message.toolName)
          Spacer(Modifier.width(Spacing.sm))
          Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (!filePath.isNullOrBlank()) {
              Text(
                  text = toolLabel,
                  color = MaterialTheme.colorScheme.onSurface,
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis)
              Spacer(Modifier.width(Spacing.xs))
              // 路径段（可省略）+ 文件名段（永远完整，优先级最高）：等宽字，与代码卡头部一致
              val monoLabel =
                  MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
              val pathDir = filePath.substringBeforeLast('/')
              if (pathDir.isNotEmpty()) {
                Text(
                    text = pathDir + "/",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = monoLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
              }
              Text(
                  text = filePath.substringAfterLast('/'),
                  color = MaterialTheme.colorScheme.onSurface,
                  style = monoLabel,
                  maxLines = 1)
            } else {
              Text(
                  text = toolLabel,
                  color = MaterialTheme.colorScheme.onSurface,
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis)
              if (!argHint.isNullOrBlank()) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = argHint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
              }
            }
          }
          if (running) {
            // 运行中的这条：行尾用统一的涟漪场景文案说明「正在做什么」。工具名在开始执行前
            // 就已到达（AgentEvent.ToolCallStarted），所以不必停在笼统的「执行中」；
            // 折叠态也看得见，不必展开才知道它在跑。
            Spacer(Modifier.width(Spacing.sm))
            RippleText(text = ToolCardStrings.runningLabel(message.toolName))
          }
          if (edit != null) {
            DiffStat(added = edit.added, removed = edit.removed)
            Spacer(Modifier.width(Spacing.sm))
          }
          if (todoData != null && todoData.total > 0) {
            Text(
                text = "${todoData.completed}/${todoData.total}",
                color =
                    if (todoData.completed == todoData.total) DiffAddText
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(Spacing.sm))
          }
          if (expandable) {
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = toggle)
                        .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
              Icon(
                  if (effectiveExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                  contentDescription =
                      if (effectiveExpanded) ToolCardStrings.COLLAPSE else ToolCardStrings.EXPAND,
                  tint = Brand.IconGray,
                  modifier = Modifier.size(18.dp))
            }
          }
        }
    // 子代理步骤的折叠态预览（cc-haha AgentCallCard 的 AgentProgressLine 语义，
    // XML 路径 toolStepsHint 的 Compose 对应物）：有步骤时显示「N 步 · 最后一步」，
    // 子代理折叠后与普通工具的区别必须可见——否则用户不知道它跑了多少步、最后在做什么。
    // 运行中与结束态都显示（结束态是回看的唯一凭据）。
    if (message.steps.isNotEmpty()) {
      Spacer(Modifier.height(2.dp))
      Text(
          text = "${message.steps.size} 步 · ${message.steps.last()}",
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = ChatStyle.rowIconSize + Spacing.sm))
    }
    // 生成图常显预览（cc-haha ImageGenerationBlock 语义）：图片是这条结果的主体，
    // 折进卡片里用户看不到「查看图片功能回来了」。只在非 running 且磁盘有文件时显示。
    //
    // **折叠态由这里渲染，展开态由下方展开分支渲染**——不要两处都渲染：展开时
    // 两份卡片会叠出两个「点击查看大图」（用户实测反馈的重复 UI）。
    // 展开分支的判据是 effectiveExpanded，这里的判据取反，恰好互补、每个状态一份。
    if (!running && !message.isError && !effectiveExpanded) {
      val imagePaths = remember(message.content) { generatedImagePaths(message) }
      if (imagePaths.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.xs))
        ToolResultImageFromPaths(imagePaths)
      }
    }
    if (streaming) {
      // 展开态与落库卡片同构：先「指令」（工具参数），再「结果」（实时输出尾部）；
      // 折叠态只保留标题行。「还在跑」由标题行行尾的涟漪场景文案表达，不在这里重复一遍。
      AnimatedVisibility(
          visible = effectiveExpanded,
          enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
          exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
      ) {
        Column {
          if (!argsFull.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            ToolSection(label = ToolCardStrings.INSTRUCTION, content = argsFull)
          }
          if (hasLiveOutput) {
            val truncated =
                remember(liveOutput) { liveOutput.takeLastLines(TOOL_SECTION_LINE_LIMIT) }
            Spacer(Modifier.height(Spacing.sm))
            ToolSection(label = ToolCardStrings.RESULT, content = truncated, live = true)
          }
        }
      }
    } else {
      AnimatedVisibility(
          visible = effectiveExpanded,
          enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
          exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
      ) {
        Column(modifier = Modifier.pointerInput(message.id) { detectDoubleTapToCollapse(toggle) }) {
          if (todoData != null && todoData.items.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            TodoCard(items = todoData.items)
          } else if (webSearchData != null) {
            Spacer(Modifier.height(Spacing.xs))
            WebSearchResultCard(result = webSearchData)
          } else if (message.imageBase64 != null) {
            // 工具结果里的图（截图 / 生成图 / 第三方 MCP 返回的图）。
            //
            // **为什么单独一个分支而不是与下面并列**：图片是这条工具结果的**主要内容**，
            // 不是附加信息。落到下面的 `else` 里会把「结果」文本区也画出来，而那种结果的
            // content 往往就是一大段 base64 或尺寸描述——正文与图重复且难看。
            Spacer(Modifier.height(Spacing.xs))
            ToolResultImage(message)
          } else if (generatedImagePaths(message).isNotEmpty()) {
            // 生成图的**路径引用**分支（cc-haha 同款协议/UI 分离）：图片文件不进模型
            // 上下文（base64 会撑爆窗口并引发 GC 风暴），工具结果只写落盘路径；
            // UI 从 content 里提取路径、从磁盘加载内联显示。用户看到的仍是图片，
            // 模型看到的是几百字节文本。位置在 imageBase64 之后：旧会话里内联
            // base64 的结果优先走原分支，不受影响。
            //
            // **这里只负责展开态**：折叠态由上方「生成图常显预览」块渲染（其判据
            // 带 !effectiveExpanded）。两处互补、每个状态恰好一份——若上方块漏了
            // 展开态排除，展开时会叠出两个「点击查看大图」（用户实测反馈）。
            Spacer(Modifier.height(Spacing.xs))
            ToolResultImageFromPaths(generatedImagePaths(message))
          } else if (edit != null) {
            // 差异卡：头部给路径与「复制」，页脚给增删统计
            Spacer(Modifier.height(Spacing.xs))
            ChatCodeCard(
                title = edit.path.ifBlank { null },
                copyText = edit.hunks.joinToString("\n") { it.diff },
                footer =
                    ToolCardStrings.changedFilesSummary(
                        edit.added, edit.removed, if (edit.path.isBlank()) 0 else 1)) {
                  Column {
                    edit.hunks.forEach { h -> DiffView(diff = h.diff, startLine = h.startLine) }
                    Spacer(Modifier.height(Spacing.xs))
                  }
                }
          } else {
            // 步骤区（子代理过程）：普通工具没有步骤，整块隐藏——空标题只是噪音。
            // 放在「指令」之前：步骤是子代理的主体内容，指令（task 参数）只是它的输入。
            if (message.steps.isNotEmpty()) {
              Spacer(Modifier.height(Spacing.sm))
              ToolStepsSection(steps = message.steps)
            }
            if (!argsFull.isNullOrBlank()) {
              Spacer(Modifier.height(Spacing.sm))
              ToolSection(label = ToolCardStrings.INSTRUCTION, content = argsFull)
            }
            // 「查看结果」入口（cc-haha AgentCallCard 的 Modal 按钮同款）：子代理结论
            // 可能很长，独立弹窗里 Markdown 渲染 + 滚动阅读才可读；内联结果区保留
            // （快速扫读），弹窗给完整版。只给子代理类工具——普通工具的 resultText
            // 就是短摘要，弹窗是多余的一跳。
            if (isAgentToolName(message.toolName) && message.content.isNotBlank()) {
              Spacer(Modifier.height(Spacing.xs))
              TextButton(onClick = { showResultDialog = true }) {
                Text(ToolCardStrings.VIEW_RESULT)
              }
            }
            if (!resultText.isNullOrBlank()) {
              Spacer(Modifier.height(Spacing.sm))
              ToolSection(label = ToolCardStrings.RESULT, content = resultText)
            }
          }
        }
      }
    }
    if (notifications.isNotEmpty()) {
      Spacer(Modifier.height(Spacing.sm))
      notifications.forEach { ToolNotificationRow(it) }
    }
    // 文件列表：工具结束后常显在消息底部，一行一个文件，点击用系统 app 打开。
    // 宿主（Aharou 的 LocalAttachmentOpener）未接时 onClick 传 null，行只剩展示语义。
    if (!running && message.attachments.isNotEmpty()) {
      MessageAttachmentList(attachments = message.attachments)
    }
    // 子代理「查看结果」弹窗（见上方按钮）。挂在本函数末尾而不是展开区里：
    // 展开区随折叠动画进出组合，弹窗放里面会在折叠瞬间被连带 dispose。
    if (showResultDialog) {
      SubAgentResultDialog(
          title = message.toolName ?: ToolCardStrings.TOOL,
          content = message.content,
          onDismiss = { showResultDialog = false })
    }
  }
}

/**
 * 子代理结论弹窗（cc-haha AgentCallCard 的 900px Modal 对应物）。
 *
 * <p>内容用 [com.tom.rv2ide.artificial.agent.compose.components.markdown.MarkdownContent]
 * 渲染：子代理的结论本身是模型写的 Markdown（含标题/列表/代码块），纯文本显示会
 * 丢掉结构。限高 70% 屏高 + 内部滚动：结论可达数千字，不限高会顶出屏幕。
 */
@Composable
private fun SubAgentResultDialog(title: String, content: String, onDismiss: () -> Unit) {
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically) {
          Text(
              text = ToolCardStrings.VIEW_RESULT,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f))
          TextButton(onClick = onDismiss) { Text(ToolCardStrings.BACK) }
        }
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        com.tom.rv2ide.artificial.agent.compose.components.markdown.MarkdownContent(
            text = content,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.md),
            cacheEnabled = false,
        )
      }
    }
  }
}

private data class ToolMessageDerived(
    val edit: EditDiff?,
    val resultText: String?,
    val argHint: String?,
    val argsFull: String?,
    val todo: ParsedTodoResult?,
    val webSearch: ParsedWebSearchResult?,
    val notifications: List<ToolNotificationInfo>,
    val filePath: String?,
)

internal data class ToolNotificationInfo(
    val summary: String,
    val succeeded: Boolean,
    val isMessage: Boolean = false
)

/**
 * 从工具结果 transport JSON 顶层的 `notifications` 字段提取搭车通知（后台任务/子代理完成）。
 * 该字段由 Aharou 的 StatefulAgentWorkflow 在 AI 忙碌时注入；ACS 侧目前不产生该字段，
 * 解析器保留是为了将来对齐时不必再改本文件。
 */
internal fun parseToolNotifications(raw: String): List<ToolNotificationInfo> {
  val array =
      parseToolTransport(raw.withoutToolStatusPrefix())?.get("notifications") as? JsonArray
          ?: return emptyList()
  return array.mapNotNull { element ->
    val obj = element as? JsonObject ?: return@mapNotNull null
    val summary =
        (obj["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
    val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
    ToolNotificationInfo(
        summary = summary, succeeded = status == "completed", isMessage = status == "message")
  }
}

/** 工具卡片底部的搭车通知提示条：状态点 + 摘要。 */
@Composable
private fun ToolNotificationRow(info: ToolNotificationInfo) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Box(
            modifier =
                Modifier.size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                          info.isMessage -> MaterialTheme.colorScheme.primary
                          info.succeeded -> MaterialTheme.semanticColors.success
                          else -> MaterialTheme.colorScheme.error
                        }))
        Text(
            text = info.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis)
      }
}

private fun String.takeLastLines(maxLines: Int): String {
  if (maxLines <= 0 || isEmpty()) return ""
  var seen = 0
  for (i in lastIndex downTo 0) {
    if (this[i] == '\n' && ++seen == maxLines) {
      return substring(i + 1)
    }
  }
  return this
}

/**
 * 双击折叠检测：detectTapGestures 的 awaitFirstDown 默认 requireUnconsumed=true，
 * 而展开区内容包在 SelectionContainer / clickable 里会消费 down，导致首击被忽略、
 * 双击永远不触发。这里首击不要求未消费，只做「快速连点两次」判定。
 */
private suspend fun PointerInputScope.detectDoubleTapToCollapse(onDoubleTap: () -> Unit) {
  val viewConfig = viewConfiguration
  awaitEachGesture {
    val firstDown = awaitFirstDown(requireUnconsumed = false)
    // 两次点击期间若发生明显位移（用户在滑动），放弃双击判定
    if (!awaitTapOrSwipe(firstDown.id, firstDown.position, viewConfig.touchSlop))
        return@awaitEachGesture
    val secondDown =
        withTimeoutOrNull(viewConfig.doubleTapTimeoutMillis) {
          awaitFirstDown(requireUnconsumed = false)
        } ?: return@awaitEachGesture
    if (secondDown.uptimeMillis - firstDown.uptimeMillis < viewConfig.doubleTapMinTimeMillis) {
      return@awaitEachGesture
    }
    if ((secondDown.position - firstDown.position).getDistance() > viewConfig.touchSlop) {
      return@awaitEachGesture
    }
    if (!awaitTapOrSwipe(secondDown.id, secondDown.position, viewConfig.touchSlop))
        return@awaitEachGesture
    onDoubleTap()
  }
}

/** 等待指定指针抬起；期间若位移超过 slop（发生滑动），返回 false。 */
private suspend fun AwaitPointerEventScope.awaitTapOrSwipe(
    pointerId: PointerId,
    downPosition: Offset,
    slop: Float
): Boolean {
  while (true) {
    val event = awaitPointerEvent(PointerEventPass.Main)
    val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
    if ((change.position - downPosition).getDistance() > slop) return false
    if (!change.pressed) return true
  }
}

/**
 * 工具状态图标：按工具类型给出字形（文件 / 终端 / 搜索 / 任务…），并按状态着色——
 * 运行中脉冲、失败整枚图标转红、成功回到中性色（满屏绿色会抢注意力）。
 *
 * <p>失败不再额外挂一枚警示图标：行首图标格保持一枚字形，状态只由颜色与读屏语义表达，
 * 免得同一行出现两个图标、行首位置还随成败跳动。色弱用户靠 [statusLabel]（读屏）分辨成败。
 */
@Composable
internal fun ToolStatusIcon(running: Boolean, isError: Boolean, toolName: String?) {
  val tint =
      when {
        isError -> DiffRemoveText
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
      }
  val pulseAlpha =
      if (running) {
        val transition = rememberInfiniteTransition(label = "tool-status-pulse")
        transition
            .animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec =
                    infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
                label = "tool-status-pulse-alpha")
            .value
      } else {
        1f
      }
  val statusLabel =
      when {
        running -> ToolCardStrings.RUNNING
        isError -> ToolCardStrings.FAILED
        else -> ToolCardStrings.SUCCEEDED
      }
  Row(verticalAlignment = Alignment.CenterVertically) {
    // 子代理类工具用机器人字形（cc-haha 的 Bot 图标，用户明确要求复刻）：
    // 「agent」系列工具在 cc-haha 桌面端一律以 Bot 呈现，与普通工具（锤子/终端）
    // 区分开——子代理是「另一个智能体在干活」，不是一个普通工具调用。
    if (isAgentToolName(toolName)) {
      BotGlyph(
          tint = tint,
          iconSize = ChatStyle.rowIconSize,
          modifier = Modifier.graphicsLayer { alpha = pulseAlpha })
    } else {
      Icon(
          imageVector = toolIcon(toolName),
          contentDescription = statusLabel,
          tint = tint,
          modifier =
              Modifier
                  // 与思考行同一格宽：两行的图标中心与后续文字左边缘才对得齐
                  .size(ChatStyle.rowIconSize)
                  .graphicsLayer { alpha = pulseAlpha })
    }
  }
}

/**
 * 该工具名是否属于「子代理」一族（用机器人字形呈现）。
 *
 * <p>覆盖三类：内置 `agent`（子代理）、`agentb_*`（内置角色 agent）、
 * `agentx_*`（自定义 agent）。`agent_pipeline` / `agent_output` 是编排类工具，
 * 语义上也是「派遣代理」，一并归入。
 */
internal fun isAgentToolName(toolName: String?): Boolean {
  val name = toolName?.lowercase() ?: return false
  return name == "agent" ||
      name == "agent_pipeline" ||
      name == "agent_output" ||
      name == "task" ||
      name.startsWith("agentb_") ||
      name.startsWith("agentx_")
}

/**
 * 工具名字形映射。键同时覆盖 Aharou 的工具注册名与 ACS 的规范名/别名（比对时忽略大小写）：
 * ACS 用 snake_case（规范名 `file_read` / `file_edit`，别名 `read_file` / `edit_file`），
 * Aharou 用驼峰，全部收——同一语义的工具必须显示同一字形，否则用户在子代理步骤里
 * 认不出「这就是主对话里那个读文件工具」。
 */
private fun toolIcon(toolName: String?): ImageVector =
    when (toolName?.lowercase()) {
      "editfile",
      "writefile",
      "edit_file",
      "write_file",
      "file_edit",
      "file_write",
      "file_delete",
      "generateimage",
      "generate_image" -> FeatherIcons.Edit3
      "readfile",
      "list",
      "sendfile",
      "viewimage",
      "read_file",
      "file_read",
      "list_files",
      "list_dir",
      "glob" -> FeatherIcons.FileText
      "search",
      "websearch",
      "webfetch",
      "browser",
      "web_search",
      "web_fetch",
      "grep" -> FeatherIcons.Search
      "bash",
      "terminal",
      "shell_execute" -> FeatherIcons.Terminal
      "todo",
      "todo_update" -> FeatherIcons.Check
      "task" -> FeatherIcons.Cpu
      "memory",
      "memory_update" -> FeatherIcons.Database
      else -> FeatherIcons.Tool
    }

/**
 * 工具是否仍在执行。
 *
 * <p><b>判据优先级</b>：实时输出 > [ToolRunStatus.RUNNING]（ACS 扩展字段，权威）>
 * Aharou 的 content 前缀哨兵。
 *
 * <p>Aharou 靠 `content.startsWith(PENDING_TOOL_MARKER)` 推断运行中，因为它没有显式状态；
 * ACS 的映射层已把显式状态填进 [AgentUIMessage.toolStatus]，那是权威来源——它不会因为
 * 结果文本恰好以某个字符开头而误判，也不依赖哨兵常量存在。
 *
 * <p>哨兵判据仍保留为兜底，但**只在常量非空时生效**：若 compat 侧把「无此哨兵」表达为
 * 空串，`startsWith("")` 恒为 true，会把每一条工具都判成运行中——所以这里显式要求非空。
 */
internal fun AgentUIMessage.isToolRunning(liveOutput: String?): Boolean {
  if (liveOutput != null) return true
  if (toolStatus == ToolRunStatus.RUNNING) return true
  return hasPendingToolMarker(content)
}

/** 内容是否带 Aharou 的「仍在跑」哨兵前缀。空常量视为「无此哨兵」，不做任何推断。 */
private fun hasPendingToolMarker(content: String): Boolean {
  val markers =
      listOf(
          SessionUseCase.PENDING_TOOL_MARKER,
          SessionUseCase.LEGACY_PENDING_TOOL_MARKER,
      )
  return markers.any { it.isNotEmpty() && content.startsWith(it) }
}

/**
 * 「N 次工具调用」分组头：把一轮任务里连续的工具调用折成一行，**默认收起**，
 * 点一下手动展开/收起（运行中也不自动弹开）。布局对齐思考行：左侧工具图标（锤子/施工）
 * ＋中间调用计数＋折叠箭头。
 *
 * <p>[running] 为真时给「N 次工具调用」这行文案本身走涟漪高光，表示这批调用还在跑。
 * 不另挂三个跳动的点，也不在这里重复场景文案（「正在编辑文件」这类说的是**具体哪个工具**，
 * 展开后由每条工具行的运行状态给出，见 [ToolMessageBody]）。
 */
@Composable
internal fun ToolCallGroupHeader(
    count: Int,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .heightIn(min = ChatStyle.toolRowMinHeight)
              .clip(RoundedCornerShape(ChatStyle.panelCorner))
              .clickable(
                  // 无障碍：点这一行会展开还是收起，读屏能念出来（展开状态本身由 count 文本表达）
                  onClickLabel = if (expanded) ToolCardStrings.COLLAPSE else ToolCardStrings.EXPAND,
                  onClick = onToggle),
      verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.Construction,
            contentDescription = null,
            tint = Brand.IconGray,
            modifier = Modifier.size(ChatStyle.rowIconSize))
        Spacer(Modifier.width(Spacing.sm))
        // 权重挂在外层占位，涟漪画在文字自身的尺寸上：光带只扫「N 次工具调用」这几个字，
        // 不会在整行空白里慢慢爬。
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
          RippleText(
              text = ToolCardStrings.toolCallsCount(count),
              highlight = if (running) Color.White else null)
        }
        Icon(
            imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
            contentDescription =
                if (expanded) ToolCardStrings.COLLAPSE else ToolCardStrings.EXPAND,
            tint = Brand.IconGray,
            modifier = Modifier.size(18.dp))
      }
}

/**
 * 工具结果里的图片：预览 + 点击看大图。
 *
 * <p><b>为什么本层只做「能不能看」的判定，不做解码</b>：真正的解码与灯箱由宿主包在面板
 * 外层的 `ProvideAcsImageViewer` 负责（它按需解码并转给 ACS 已有的
 * `ToolResultImageSupport.showLightbox`）。本层若自己解一遍，就会为了画一张预览图而
 * 在主线程或额外协程里持有几 MB 的 Bitmap，而点开大图时宿主还要再解一次——同一份数据
 * 解两遍，且两份的生命周期互不相干。这里只调 `ToolResultImageSupport.decode` 的
 * **轻量判定**：它校验大小与 base64 合法性，决定「显示预览入口」还是「显示不可用提示」。
 *
 * <p><b>畸形数据只给提示、不画破图</b>：base64 来自工具甚至第三方 MCP server，畸形是常态
 * （与 XML 路径 `AssistantMessageAdapter.bindImage` 同款取舍）。显示破图占位比不显示更糟
 * ——用户会以为界面坏了。
 */
@Composable
private fun ToolResultImage(message: AgentUIMessage) {
  val base64 = message.imageBase64 ?: return
  val mimeType = message.imageMimeType
  // 键用长度 + 前缀而不是整串：整串相等比较本身要扫几 MB，而 base64 只会整体替换。
  val key = base64.length to base64.take(64)

  val decoded by
      produceState<ToolResultImageSupport.Decoded?>(initialValue = null, key, mimeType) {
        value =
            withContext(Dispatchers.Default) {
              ToolResultImageSupport.decode(mimeType, base64)
            }
      }

  when (val result = decoded) {
    // 判定中：不占位，避免下方内容被顶一下
    null -> Unit
    is ToolResultImageSupport.Decoded.Rejected ->
        Text(
            text =
                stringResource(
                    if (result.reason == ToolResultImageSupport.Reason.TOO_LARGE)
                        R.string.ai_assistant_image_too_large
                    else R.string.ai_assistant_image_unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    is ToolResultImageSupport.Decoded.Ok -> {
      val viewer = LocalImageViewer.current
      val title = message.toolName ?: ToolCardStrings.IMAGE_PREVIEW_LABEL
      // 预览用卡片形态（与差异卡、待办卡同一视觉层），点整块进大图。
      Surface(
          shape = RoundedCornerShape(ChatStyle.panelCorner),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
          modifier =
              Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(ChatStyle.panelCorner))
                  .clickable {
                    viewer.show(
                        ImageViewerRequest(
                            sources = listOf(ImageSource.Base64(base64)),
                            title = title,
                        ))
                  },
      ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
              imageVector = FeatherIcons.Image,
              contentDescription = null,
              tint = Brand.IconGray,
              modifier = Modifier.size(ChatStyle.rowIconSize))
          Spacer(Modifier.width(Spacing.sm))
          Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = ToolCardStrings.TAP_TO_VIEW,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
          }
          Icon(
              imageVector = FeatherIcons.ChevronRight,
              contentDescription = null,
              tint = Brand.IconGray,
              modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

/**
 * 从工具结果的 content 里提取**本机存在的**图片绝对路径（cc-haha InlineImageGallery 的
 * 提取语义）：匹配以图片扩展名结尾的绝对路径行，且磁盘上确有该文件。
 *
 * <p>纯内存正则 + 一次 exists 检查，缓存到 content 键上——LazyColumn 每次重组都会调用
 * 本分支，不缓存的话每帧都跑正则与 IO。
 */
internal fun generatedImagePaths(message: AgentUIMessage): List<String> {
  val content = message.content
  if (content.length > 100_000) return emptyList() // 防御：异常大 content 不跑正则
  val matcher = IMAGE_PATH_PATTERN.findAll(content)
  val paths = ArrayList<String>(2)
  for (m in matcher) {
    val path = m.groupValues[1]
    val file = java.io.File(path)
    // 磁盘确认：会话可回放跨项目，历史里的路径可能已被清理——不存在的文件不渲染破图。
    if (file.isFile) paths.add(path)
  }
  return paths
}

/** 绝对路径 + 图片扩展名（与 ImageGenerationTool 的落盘命名对应：.png/.jpg/.webp）。 */
private val IMAGE_PATH_PATTERN =
    Regex("""(/(?:[\w .-]+/)*[\w.-]+\.(?:png|jpe?g|webp))""", RegexOption.IGNORE_CASE)

/**
 * 生成图路径引用的内联预览（磁盘加载，不经过模型上下文）。
 *
 * <p>渲染形态与 [ToolResultImage] 一致（卡片 + 点击灯箱），数据源从 base64 换成
 * [ImageSource.LocalFile]——查看器自己处理磁盘读取与降采样。
 */
@Composable
private fun ToolResultImageFromPaths(paths: List<String>) {
  val viewer = LocalImageViewer.current
  val title = ToolCardStrings.IMAGE_PREVIEW_LABEL
  Surface(
      shape = RoundedCornerShape(ChatStyle.panelCorner),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(ChatStyle.panelCorner))
              .clickable {
                viewer.show(
                    ImageViewerRequest(
                        sources = paths.map { ImageSource.LocalFile(it) },
                        title = title,
                    ))
              },
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          imageVector = FeatherIcons.Image,
          contentDescription = null,
          tint = Brand.IconGray,
          modifier = Modifier.size(ChatStyle.rowIconSize))
      Spacer(Modifier.width(Spacing.sm))
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text =
                if (paths.size == 1) java.io.File(paths[0]).name
                else stringResource(R.string.compose_tool_card_image_count, paths.size),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = ToolCardStrings.TAP_TO_VIEW,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
      }
      Icon(
          imageVector = FeatherIcons.ChevronRight,
          contentDescription = null,
          tint = Brand.IconGray,
          modifier = Modifier.size(18.dp))
    }
  }
}

/**
 * 子代理步骤区：每步一行，**按主 agent 工具行的样式渲染**（cc-haha AgentCallCard 的
 * 「最近子工具调用」列表对应物）——图标 + 工具名 + 目标，与主对话里的工具行同构。
 *
 * <p>行文本由 [com.tom.rv2ide.artificial.agent.SubAgentStepReporter] 生成，形如
 * `子 agent 步骤 3 · file_read: a.kt`；解析出工具名后复用主工具行的图标映射
 * （[toolIcon]），用户在主对话里认得的字形，在子代理步骤里同样认得。
 * 解析不出的行（「子 agent 思考中（第 2 轮）」等）保留原文单行显示。
 */
@Composable
internal fun ToolStepsSection(steps: List<String>) {
  Text(
      text = "步骤（${steps.size}）",
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold)
  Spacer(Modifier.height(2.dp))
  val scrollState = rememberScrollState()
  ChatMonoPanel(
      modifier =
          Modifier.heightIn(max = ToolSectionMaxHeight)
              .nestedScroll(rememberBoundNestedScrollConnection(scrollState))
              .verticalScroll(scrollState)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          steps.forEach { step -> SubAgentStepRow(step) }
        }
      }
}

/** 子代理步骤的单行渲染：能解析出工具名时走「图标 + 工具名 + 目标」，否则原文显示。 */
@Composable
private fun SubAgentStepRow(step: String) {
  val parsed = remember(step) { parseSubAgentStep(step) }
  if (parsed == null) {
    Text(
        text = step,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis)
    return
  }
  val (name, target) = parsed
  Row(verticalAlignment = Alignment.CenterVertically) {
    // 与主工具行同一套字形（含子代理自己的机器人字形）：同一工具在主对话与
    // 子代理步骤里显示同一个图标，扫读时不必重新建立联想。
    if (isAgentToolName(name)) {
      BotGlyph(
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          iconSize = ChatStyle.rowIconSize)
    } else {
      Icon(
          imageVector = toolIcon(name),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(ChatStyle.rowIconSize))
    }
    Spacer(Modifier.width(Spacing.sm))
    Text(
        text = name,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis)
    if (target.isNotBlank()) {
      Spacer(Modifier.width(Spacing.xs))
      Text(
          text = target,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false))
    }
  }
}

/**
 * 解析 `子 agent 步骤 N · <toolName>: <target>` 格式的步骤行。
 *
 * <p>格式定义在 [com.tom.rv2ide.artificial.agent.SubAgentStepReporter]：前缀是
 * `子 agent 步骤 N · `，之后是 `工具名: 目标`。目标里可能自带冒号（命令、URL），
 * 因此只按**第一个**冒号切分。解析失败（思考中/完成/失败行）返回 null，由调用方
 * 按原文渲染——这些行不是工具调用，套工具行样式会误导。
 */
internal fun parseSubAgentStep(step: String): Pair<String, String>? {
  val prefix = "子 agent 步骤 "
  if (!step.startsWith(prefix)) return null
  val sep = step.indexOf(" · ")
  if (sep < 0) return null
  val rest = step.substring(sep + 3)
  val colon = rest.indexOf(':')
  if (colon <= 0) return null
  val name = rest.substring(0, colon).trim()
  val target = rest.substring(colon + 1).trim()
  if (name.isEmpty()) return null
  return name to target
}

/** 展开区的一段带小标题的内容块（如「指令」「结果」）：弱底等宽小面板，超出限高后在窗口内滚动。 */
@Composable
internal fun ToolSection(label: String, content: String, live: Boolean = false) {  Text(
      text = label,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold)
  Spacer(Modifier.height(2.dp))

  // 超长输出走折叠护栏时**不进**下面的限高滚动窗：护栏态自带「预览 + 展开」动作行，
  // 塞进 240dp 的窗口里动作行会被裁掉，用户看得到输出却点不到展开（实机验证发现）。
  if (!live && toolOutputNeedsGuard(content)) {
    ToolOutputText(text = content, color = MaterialTheme.colorScheme.onSurfaceVariant)
    return
  }

  val scrollState = rememberScrollState()
  var followLive by remember { mutableStateOf(true) }
  var displayedContent by remember { mutableStateOf(content) }
  LaunchedEffect(scrollState, live) {
    if (!live) return@LaunchedEffect
    scrollState.interactionSource.interactions.collect { interaction ->
      if (interaction is DragInteraction.Start) followLive = false
    }
  }
  LaunchedEffect(scrollState, live) {
    if (!live) return@LaunchedEffect
    snapshotFlow { scrollState.isScrollInProgress to scrollState.canScrollForward }
        .collect { (scrolling, canScrollForward) ->
          if (!scrolling && !canScrollForward) followLive = true
        }
  }
  LaunchedEffect(content, live, followLive, scrollState.isScrollInProgress) {
    if (!live || (followLive && !scrollState.isScrollInProgress)) displayedContent = content
  }
  val fadeColor = MaterialTheme.colorScheme.background
  Box {
    ChatMonoPanel(
        modifier =
            Modifier.heightIn(max = ToolSectionMaxHeight)
                .nestedScroll(rememberBoundNestedScrollConnection(scrollState))
                .verticalScroll(scrollState)) {
          // 到这里只可能是两种情形：流式输出，或落库后且未超阈值的短结果。
          // 超阈值的那条已在上方提前返回、不进入本滚动窗。
          //
          // 流式路径不做折叠（文本每 tick 都在变，跨阈值会让正文在「徽标 ↔ 正文」间反复跳），
          // 但单行仍要封顶——无换行的长行才是拖慢测量的主因。
          val shown = if (live) displayedContent else content
          val bounded = remember(shown) { capToolOutputLines(shown) }
          SelectionContainer {
            Text(
                text = breakableText(bounded),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
          }
        }
    // 底部渐隐：只有还能继续往下滚时才盖一层，提示“下面还有内容”
    if (scrollState.canScrollForward) {
      Box(
          modifier =
              Modifier.align(Alignment.BottomStart)
                  .fillMaxWidth()
                  .height(ToolSectionFadeHeight)
                  .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor))))
    }
  }
}

/** 增删统计胶囊：绿色「+N」与红色「−M」。 */
@Composable
internal fun DiffStat(added: Int, removed: Int) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (added > 0) {
      Text(
          text = "+$added",
          color = DiffAddText,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold)
    }
    if (added > 0 && removed > 0) Spacer(Modifier.width(Spacing.xs))
    if (removed > 0) {
      Text(
          text = "−$removed",
          color = DiffRemoveText,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold)
    }
  }
}

/**
 * 彩色行级差异视图。
 *
 * <p>输入是 `\n` 分隔的行文本，**首字符即行的类型标记**：`+` 增、`-` 删、其他为不变。
 * 行号列由 [startLine] 起算，删除行占旧行号、新增行占新行号——这与
 * `AcsMessageMapper.serializeEditDiff` 序列化出的形状一一对应（`{path, added_lines,
 * removed_lines, start_line, diff}`），因此 ACS 的结构化 diff 无需另写渲染即可复用本视图。
 */
@Composable
internal fun DiffView(diff: String, startLine: Int) {
  val lines = remember(diff) { diff.split("\n") }
  val collapsible = lines.size > DIFF_COLLAPSE_THRESHOLD
  var expanded by remember(diff) { mutableStateOf(false) }
  val visibleLines = if (collapsible && !expanded) lines.take(DIFF_COLLAPSE_THRESHOLD) else lines

  val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
  val removeCount = lines.count { it.startsWith("-") }
  val addCount = lines.count { it.startsWith("+") }
  val maxLineNo = startLine + lines.size - removeCount - addCount + maxOf(removeCount, addCount)
  val gutterChars = maxOf(2, maxLineNo.toString().length)
  val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

  Column(modifier = Modifier.fillMaxWidth()) {
    // 横向滚动容器在外层、文本选择在内层：滚动手势优先，
    // 避免 SelectionContainer 偶发抢占左右滑动（选择模式激活后拖动被选词消费）。
    // 底色与圆角由外层 ChatCodeCard 提供，这里只负责行渲染与横滑。
    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      SelectionContainer {
        Column {
          var oldLineNo = startLine
          var newLineNo = startLine
          visibleLines.forEach { line ->
            val marker = line.firstOrNull()
            val (bg, fg) =
                when (marker) {
                  '+' -> DiffAddBg to DiffAddText
                  '-' -> DiffRemoveBg to DiffRemoveText
                  else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                }
            val lineNo =
                when (marker) {
                  '-' -> oldLineNo++
                  '+' -> newLineNo++
                  else -> {
                    val n = newLineNo
                    oldLineNo++
                    newLineNo++
                    n
                  }
                }
            val gutter = lineNo.toString().padStart(gutterChars)
            val styled =
                buildAnnotatedString {
                  withStyle(SpanStyle(color = gutterColor)) {
                    append(gutter)
                    append("  ")
                  }
                  withStyle(SpanStyle(color = fg)) { append(line.ifEmpty { " " }) }
                }
            Text(
                text = styled,
                style = mono,
                softWrap = false,
                modifier =
                    Modifier.fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = Spacing.sm, vertical = 1.dp))
          }
        }
      }
    }
    if (collapsible) {
      DiffExpandToggle(
          expanded = expanded,
          hiddenCount = lines.size - DIFF_COLLAPSE_THRESHOLD,
          onToggle = { expanded = !expanded })
    }
  }
}

/** 长差异的页脚切换 */
@Composable
internal fun DiffExpandToggle(expanded: Boolean, hiddenCount: Int, onToggle: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(Radius.sm))
              .clickable(onClick = onToggle)
              .padding(vertical = Spacing.xs),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center) {
        Icon(
            if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
            contentDescription =
                if (expanded) ToolCardStrings.COLLAPSE else ToolCardStrings.EXPAND,
            tint = Brand.IconGray,
            modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text =
                if (expanded) ToolCardStrings.COLLAPSE
                else ToolCardStrings.expandRemaining(hiddenCount),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium)
      }
}

/** editFile 单处编辑的差异片段。 */
internal data class EditHunk(val startLine: Int, val diff: String)

/** editFile 结果中解析出的结构化差异 */
internal data class EditDiff(
    val path: String,
    val added: Int,
    val removed: Int,
    val hunks: List<EditHunk>
)

/**
 * 从持久化的 TOOL 内容中解析 editFile / writeFile 的结构化差异。
 *
 * <p>认两种形状：`hunks` 数组（多处编辑），或单个 `diff` + `start_line`。
 * 后者正是 `AcsMessageMapper` 序列化 ACS 结构化 [com.tom.rv2ide.artificial.agent.DiffResult]
 * 所产出的形状——ACS 的 diff 是整文件扁平行列表、没有 hunk 概念，所以走单 diff 分支。
 */
internal fun parseEditDiff(content: String): EditDiff? {
  val dataObj = extractToolDataObject(content)
  if (dataObj != null) {
    return parseEditDiffObject(dataObj)
  }

  val start = content.indexOf('{')
  val end = content.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  return runCatching {
    parseEditDiffObject(Json.parseToJsonElement(content.substring(start, end + 1)).jsonObject)
  }.getOrNull()
}

private fun parseEditDiffObject(obj: JsonObject): EditDiff? {
  val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: ""
  val added = obj["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
  val removed = obj["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0

  val hunks =
      obj["hunks"]?.jsonArray?.mapNotNull { el ->
        val ho = el.jsonObject
        val d = ho["diff"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        EditHunk(startLine = ho["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d)
      }
          ?: run {
            val d = obj["diff"]?.jsonPrimitive?.contentOrNull ?: return null
            listOf(EditHunk(startLine = obj["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d))
          }
  if (hunks.isEmpty()) return null
  return EditDiff(path = path, added = added, removed = removed, hunks = hunks)
}

/** 把落库的原始工具结果清洗成可读文本 */
internal fun formatToolResult(raw: String): String {
  val s = raw.withoutToolStatusPrefix()
  parseToolTransport(s)?.let { obj ->
    return when (obj["status"]?.jsonPrimitive?.contentOrNull) {
      "error" -> obj["message"]?.jsonPrimitive?.contentOrNull ?: s
      "success", "partial" -> formatToolData(obj["data"]) ?: s
      else -> s
    }
  }

  when {
    s.startsWith("Error(") -> {
      val msgIdx = s.indexOf("message=")
      if (msgIdx >= 0) {
        var body = s.substring(msgIdx + "message=".length)
        val codeIdx = body.lastIndexOf(", code=")
        body = if (codeIdx >= 0) body.substring(0, codeIdx) else body.removeSuffix(")")
        return body.trim()
      }
    }
    s.startsWith("Success(data=") -> {
      val inner = s.removePrefix("Success(data=").removeSuffix(")")
      return formatJsonData(inner) ?: inner.trim()
    }
    s.startsWith("Partial(data=") -> {
      var inner = s.removePrefix("Partial(data=")
      val msgIdx = inner.lastIndexOf(", message=")
      inner = if (msgIdx >= 0) inner.substring(0, msgIdx) else inner.removeSuffix(")")
      return formatJsonData(inner) ?: inner.trim()
    }
  }
  return s
}

private fun parseToolTransport(raw: String): JsonObject? {
  return runCatching {
    val obj = Json.parseToJsonElement(raw.trim()).jsonObject
    if (obj["status"] != null) obj else null
  }.getOrNull()
}

private fun extractToolDataObject(raw: String): JsonObject? {
  return (parseToolTransport(raw)?.get("data") as? JsonObject)
}

private fun formatToolData(data: JsonElement?): String? {
  return when (data) {
    is JsonPrimitive -> data.contentOrNull ?: data.toString()
    is JsonObject -> {
      val main = data["content"] ?: data["output"] ?: data["stdout"] ?: data["text"]
      val mainStr = (main as? JsonPrimitive)?.contentOrNull
      mainStr
          ?: data.entries.joinToString("\n") { (k, v) ->
            val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k: $vv"
          }
    }
    null -> null
    else -> data.toString()
  }
}

/**
 * 剥掉 Aharou 的「仍在跑 / 已停止」哨兵前缀。
 *
 * <p>只在哨兵常量**非空**时剥离：ACS 侧无该哨兵，compat 若以空串表达「无此哨兵」，
 * `removePrefix("")` 是恒等操作（无害），但把空串当哨兵去 `startsWith` 判定就会恒真。
 * 这里统一按「非空才当哨兵」处理，两种约定下都正确。
 */
internal fun String.withoutToolStatusPrefix(): String =
    trim()
        .removePrefixIfNonEmpty(SessionUseCase.LEGACY_STOPPED_TOOL_MARKER)
        .removePrefixIfNonEmpty(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
        .removePrefixIfNonEmpty(SessionUseCase.PENDING_TOOL_MARKER)
        .trim()

private fun String.removePrefixIfNonEmpty(prefix: String): String =
    if (prefix.isEmpty()) this else removePrefix(prefix)

/** 把 `data=` 里的 JsonElement 文本渲染成可读结果 */
internal fun formatJsonData(jsonStr: String): String? =
    runCatching {
      when (val el = Json.parseToJsonElement(jsonStr.trim())) {
        is JsonPrimitive -> formatToolData(el) ?: jsonStr.trim()
        is JsonObject -> formatToolData(el) ?: jsonStr.trim()
        else -> jsonStr.trim()
      }
    }.getOrNull()

/** 把传入参数 JSON 列成 `key: value` 多行 */
internal fun formatToolArgs(argsJson: String?): String? {
  if (argsJson.isNullOrBlank()) return null
  return runCatching {
    val obj = Json.parseToJsonElement(argsJson).jsonObject
    if (obj.isEmpty()) return null
    obj.entries.joinToString("\n") { (k, v) ->
      val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
      "$k: $vv"
    }
  }.getOrNull() ?: argsJson.trim()
}

/** 标题行内联的参数摘要 */
internal fun toolArgHint(argsJson: String?): String? {
  if (argsJson.isNullOrBlank()) return null
  return runCatching {
    val obj = Json.parseToJsonElement(argsJson).jsonObject
    val preferred =
        listOf(
            "command",
            "cmd",
            "path",
            "file_path",
            "file",
            "query",
            "pattern",
            "url",
            "name")
    val v = preferred.firstNotNullOfOrNull { obj[it] } ?: obj.values.firstOrNull()
    val str = (v as? JsonPrimitive)?.contentOrNull ?: v?.toString()
    str?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }
  }.getOrNull()
}

/** 从工具参数 JSON 中提取文件路径（readFile/editFile/writeFile 的 path 参数）。 */
private fun extractFilePathArg(argsJson: String?): String? {
  if (argsJson.isNullOrBlank()) return null
  return runCatching {
    val obj = Json.parseToJsonElement(argsJson).jsonObject
    listOf("path", "file_path", "file").firstNotNullOfOrNull { key ->
      (obj[key] as? JsonPrimitive)?.contentOrNull
    }
  }.getOrNull()
}
