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

package com.tom.rv2ide.artificial.agent.compose.model

import com.tom.rv2ide.adapters.AssistantMessageAdapter
import com.tom.rv2ide.artificial.agent.DiffLineType
import com.tom.rv2ide.artificial.agent.DiffResult

/**
 * 把 XML 渲染路径的条目模型 [AssistantMessageAdapter.Item] 转成 Compose 渲染层的 [AgentUIMessage]。
 *
 * <p><b>这是两条渲染路径之间唯一的桥</b>：转换是单向的（Item → AgentUIMessage），且是纯函数、不持有状态，
 * 因此可以在 JVM 上直接测，不需要 Compose 运行环境或 Android 设备。
 *
 * <p><b>三条需要知道的映射约定</b>：
 * 1. **工具组带着标识一起过来，不摊平**（2026-10-09 修正）。`ToolGroup` 的组内元素仍是独立消息，
 *    但每条都带上 [AgentUIMessage.groupId] 与 [AgentUIMessage.groupExpanded]，渲染层按标识聚合，
 *    就能还原适配器已经算好的分组。
 *
 *    <p>先前这里把组信息整个丢掉，理由是"渲染层按连续性自行分组"。**那个理由是错的**：
 *    ACS 的成组规则并不只是相邻即合并——它由 `ToolGrouping.decide` 决定，子 agent 类工具必须
 *    独立（它们带 `steps`，折进组里步骤就没展示位了），推理块能否并入又取决于"前一项是否已经是组"。
 *    让渲染层重新推断这套规则等于把同一个判定实现两遍，两边不一致时只会表现为
 *    "XML 里是一组、Compose 里是两行"这种只有肉眼能发现的差异。而且当时 Compose 侧**根本没人调用**
 *    `ToolCallGroupHeader`——分组等于没有实现：连续十几个工具调用会各占一行，把正文挤出屏幕，
 *    正是适配器当初引入分组要解决的问题。
 *
 *    <p>`DiffGroup`（本轮改动汇总卡）仍摊平：它在 Aharou 里**没有对应组件**（Aharou 的差异卡是
 *    逐工具消息的），照搬需要新写一个汇总卡组件，不属于"移植"范畴，故留待后续。
 * 2. **推理独立成条**。`Item.Thinking` 转成 `content` 为空、`reasoning` 非空的 ASSISTANT 消息，
 *    而不是合并进相邻回答。理由见 [AgentUIMessage.reasoning] 的注释。
 * 3. **diff 序列化后复用 Aharou 的差异卡**。Aharou 的卡是**解析工具输出文本**（`parseEditDiff`）
 *    得到的，认 `{path, added_lines, removed_lines, start_line, diff}` 这个形状，其中 `diff` 是
 *    `\n` 分隔的行文本，首字符 `+` / `-` / 其他 分别表示增 / 删 / 不变。ACS 侧是结构化
 *    [com.tom.rv2ide.artificial.agent.DiffResult]，本层把它序列化成上述形状，**并把 `toolName`
 *    标成 Aharou 的工具名 `editFile`**——两者缺一不可，否则它现成的差异卡（行号列、增删底色、
 *    超长折叠、复制）不会被走到。结构化结果同时保留在 [AgentUIMessage.diff] 里，不改动 [AgentUIMessage.diffId] /
 *    [AgentUIMessage.reverted]，使 ACS 自己的回滚入口仍可用。
 */
object AcsMessageMapper {

  /**
   * 触发 Aharou 差异卡的工具名。
   *
   * 写它的工具名而不是 ACS 自己的工具名，是复用其差异卡的必要条件——它的判据是
   * `toolName == "editFile" || toolName == "writeFile"`。
   */
  private const val AHAROU_EDIT_TOOL = "editFile"

  /** diff 内容仍在后台计算时的说明文案。 */
  private const val COMPUTING_DIFF_TEXT = "正在计算改动…"

  /** diff 记录被裁剪后的说明文案。 */
  private const val EXPIRED_DIFF_TEXT = "（diff 记录已过期，无法还原改动内容）"

  /** 截断说明行；以空格开头，在差异卡里按"不变行"渲染，不占增删计数。 */
  private const val TRUNCATED_LINE = " …（改动过大，此处已截断）"

  /**
   * 把条目列表整体转换。
   *
   * 输入顺序即输出顺序；组内元素保持原有先后，并各自带上所属组的标识（见类注释约定 1）。
   */
  fun toUiMessages(items: List<AssistantMessageAdapter.Item>): List<AgentUIMessage> {
    val out = ArrayList<AgentUIMessage>(items.size)
    for (item in items) append(item, out, group = null)
    return out
  }

  /**
   * 一条消息所属的分组上下文。
   *
   * @param id 组标识（取自适配器条目的 id，同一组内各条一致）
   * @param pinned 用户手动开关过的展开意图；**null = 没操作过**（渲染层按自动策略
   *   决定展开与否——工具组是「末尾展开、有后续内容折叠」，见 ToolGroupRow）
   */
  private class GroupCtx(val id: String, val pinned: Boolean?)

  private fun append(
      item: AssistantMessageAdapter.Item,
      out: MutableList<AgentUIMessage>,
      group: GroupCtx?,
  ) {
    when (item) {
      is AssistantMessageAdapter.Message -> out += fromMessage(item).inGroup(group)
      is AssistantMessageAdapter.Thinking -> out += fromThinking(item).inGroup(group)
      is AssistantMessageAdapter.ToolCall -> out += fromToolCall(item).inGroup(group)
      is AssistantMessageAdapter.Diff -> out += fromDiff(item).inGroup(group)
      // 工具组：把组标识传给每个子项，渲染层据此把它们折成一行组头 + 展开区。
      // `pinnedExpanded` 为 null 表示用户没手动开关过，按默认收起处理——与组头组件的
      // 文档一致（默认收起，运行中也不自动弹开）。
      is AssistantMessageAdapter.ToolGroup -> {
        // 三态直传：pinnedExpanded 的 null（未操作）与 false（手动折叠）对渲染层
        // 意义完全不同——前者交给自动策略，后者必须尊重用户意图。
        val ctx = GroupCtx(item.id.toString(), item.pinnedExpanded)
        item.children.forEach { append(it, out, ctx) }
      }
      // 本轮改动汇总：用 `d` 前缀的组标识把它们折成一张卡。前缀与工具组的 `g` 区分开
      // ——两者渲染成完全不同的组件（工具组是折叠条，改动组是带「全部撤销」的卡片），
      // 光靠 id 数值分不出该走哪个分支。
      //
      // Aharou 没有对应组件（它把汇总卡摊平），这里由 ACS 侧补：用户要求「撤销改动
      // 在消息尾部追加卡片、限高、每行撤销 + 全部撤销」，逐条平铺做不到「全部撤销」。
      is AssistantMessageAdapter.DiffGroup -> {
        val ctx = GroupCtx("d${item.id}", item.pinnedExpanded)
        item.children.forEach { append(it, out, ctx) }
      }
    }
  }

  /** 把分组标识盖到消息上；[group] 为 null 时原样返回（不造无谓的 copy）。 */
  private fun AgentUIMessage.inGroup(group: GroupCtx?): AgentUIMessage =
      if (group == null) this
      else
          copy(
              groupId = group.id,
              // groupExpanded 是「最终值」的近似：有显式意图就用它，没有时先给 false
              // （默认收起），渲染层拿到 groupPinned == null 会按自动策略覆盖它。
              groupExpanded = group.pinned ?: false,
              groupPinned = group.pinned,
          )

  private fun fromMessage(m: AssistantMessageAdapter.Message) =
      AgentUIMessage(
          id = m.id.toString(),
          role = m.role.toUiRole(),
          content = m.text,
          // TRACE 是 ACS 特有的"过程信息"，Aharou 侧最接近的是工具行：
          // 映射为 TOOL 且 toolName 留空——Aharou 的组件对 null 工具名是安全的
          // （toolIcon(String?) 与 toolLabel = toolName ?: "工具" 都做了兜底）
          toolName = null,
          diffId = m.diffId,
          reverted = m.reverted,
          durationMs = m.durationMs,
          isTrace = m.role == AssistantMessageAdapter.Role.TRACE,
      )

  private fun fromThinking(t: AssistantMessageAdapter.Thinking) =
      AgentUIMessage(
          id = t.id.toString(),
          role = MessageRole.ASSISTANT,
          // 空正文 + 非空 reasoning：渲染组件据此只画推理气泡
          // （MessageBubbles 的判据是 !hasContent && !hasReasoning 才跳过）
          content = "",
          reasoning = t.text,
          expanded = t.expanded,
          toolStatus = if (t.streaming) ToolRunStatus.RUNNING else ToolRunStatus.DONE,
      )

  private fun fromToolCall(t: AssistantMessageAdapter.ToolCall) =
      AgentUIMessage(
          id = t.id.toString(),
          role = MessageRole.TOOL,
          content = t.output,
          toolName = t.toolName,
          toolArgs = t.input,
          isError = t.status == AssistantMessageAdapter.ToolStatus.FAILED,
          toolStatus = t.status.toRunStatus(),
          toolSummary = t.summary,
          steps = t.steps,
          expanded = t.expanded,
          imageBase64 = t.imageBase64,
          imageMimeType = t.imageMimeType,
      )

  private fun fromDiff(d: AssistantMessageAdapter.Diff): AgentUIMessage {
    val result = d.result
    // result == null 有两种成因，文案必须区分，否则会把"还没算完"说成"记录已过期"——
    // 运行刚结束时用户最可能立刻展开汇总组，此时报错是错误信息。
    if (result == null) {
      return AgentUIMessage(
          id = d.id.toString(),
          role = MessageRole.TOOL,
          toolName = null,
          toolArgs = """{"path":${quote(d.filePath)}}""",
          // 计算中也给可读文案，而不是留空只靠 toolStatus 表达：渲染侧的运行态判据
          // 由移植组件决定（Aharou 原判据是 content 前缀哨兵，ACS 用 toolStatus），
          // 文案写进 content 则两种判据下用户都能看到当前在做什么。
          content = if (d.computing) COMPUTING_DIFF_TEXT else EXPIRED_DIFF_TEXT,
          toolStatus = if (d.computing) ToolRunStatus.RUNNING else ToolRunStatus.DONE,
          expanded = d.expanded,
          diffId = d.diffId,
          reverted = d.reverted,
          toolSummary = d.filePath,
      )
    }
    return AgentUIMessage(
        id = d.id.toString(),
        role = MessageRole.TOOL,
        toolName = AHAROU_EDIT_TOOL,
        toolArgs = """{"path":${quote(d.filePath)}}""",
        content = serializeEditDiff(d.filePath, result),
        toolStatus = ToolRunStatus.DONE,
        toolSummary = "+${result.added} -${result.removed}",
        expanded = d.expanded,
        diff = result,
        diffId = d.diffId,
        reverted = d.reverted,
    )
  }

  /**
   * 把结构化 diff 序列化成 Aharou `parseEditDiff` 认的形状。
   *
   * <p>ACS 的 [DiffResult] 是整文件的扁平行列表，没有 hunk 概念；所以这里用
   * Aharou 支持的「单个 `diff` 字符串 + 一个 `start_line`」分支，而不是 `hunks` 数组——
   * 硬拆 hunk 等于凭空造出 ACS 没有的分组信息。
   *
   * <p>[DiffResult.truncated] 为真时会在末尾补一行说明。Aharou 的卡没有截断概念，
   * 不补的话用户会把「只显示了这么多」误读成「文件只改了这么多」。
   */
  private fun serializeEditDiff(path: String, result: DiffResult): String {
    val first = result.lines.firstOrNull()
    val startLine = first?.oldNo ?: first?.newNo ?: 1
    return buildString {
      append("{\"path\":").append(quote(path))
      append(",\"added_lines\":").append(result.added)
      append(",\"removed_lines\":").append(result.removed)
      append(",\"start_line\":").append(startLine)
      append(",\"diff\":").append(quote(renderDiffText(result)))
      append('}')
    }
  }

  /** 行文本的首字符即行的类型标记（Aharou 的 DiffView 就是这么判读的）。 */
  private fun renderDiffText(result: DiffResult): String {
    val lines = result.lines.map { line ->
      val prefix =
          when (line.type) {
            DiffLineType.INSERT -> "+"
            DiffLineType.DELETE -> "-"
            DiffLineType.EQUAL -> " "
          }
      prefix + line.text
    }
    val all = if (result.truncated) lines + TRUNCATED_LINE else lines
    return all.joinToString("\n")
  }

  /** 最小 JSON 字符串转义。只为上面那一个 path 字段服务，不引入 JSON 库依赖。 */
  private fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }

  private fun AssistantMessageAdapter.Role.toUiRole() =
      when (this) {
        AssistantMessageAdapter.Role.USER -> MessageRole.USER
        AssistantMessageAdapter.Role.ASSISTANT -> MessageRole.ASSISTANT
        AssistantMessageAdapter.Role.TRACE -> MessageRole.TOOL
      }

  private fun AssistantMessageAdapter.ToolStatus.toRunStatus() =
      when (this) {
        AssistantMessageAdapter.ToolStatus.RUNNING -> ToolRunStatus.RUNNING
        AssistantMessageAdapter.ToolStatus.DONE -> ToolRunStatus.DONE
        AssistantMessageAdapter.ToolStatus.FAILED -> ToolRunStatus.FAILED
      }
}
