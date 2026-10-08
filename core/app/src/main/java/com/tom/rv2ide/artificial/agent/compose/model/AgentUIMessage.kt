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

package com.tom.rv2ide.artificial.agent.compose.model

import androidx.compose.runtime.Immutable
import com.tom.rv2ide.artificial.agent.DiffResult

/**
 * Compose 渲染层的消息模型。
 *
 * <p><b>为什么照着 Aharou 的 `AgentUIMessage` 定字段名</b>：被移植的 Compose 组件直接读
 * `message.content` / `message.toolName` / `message.reasoning` 这些字段。只要字段名与类型一致，
 * 移植过来的组件**一行都不用改**，只需改 import。若在这里另起一套命名，每个被移植的文件都要
 * 逐字段改名——那正是移植 diff 膨胀、且最容易改漏的地方。
 *
 * <p><b>与 Aharou 原版的差异，只有"扩展字段"一类</b>：Aharou 的模型是为它自己的持久化设计的，
 * 没有 ACS 的若干概念（结构化 diff、工具运行态、步骤进度、结果配图、耗时）。这些以**带默认值的
 * 追加字段**形式挂在末尾——追加字段不会影响移植过来的组件，它们不认识这些字段，照旧只读自己关心的那些。
 *
 * <p><b>不依赖 View 层</b>：本文件刻意不 import `AssistantMessageAdapter`。XML 渲染路径（适配器）
 * 是本次移植要**取代**的东西，模型反向依赖它，等于把待废弃的类型变成新架构的地基。因此
 * [ToolRunStatus] 在这里独立定义，由映射层负责转换。
 */
@Immutable
data class AgentUIMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    /**
     * 消息时间（毫秒）。0 表示"未知"。
     *
     * <p>ACS 的 `AssistantMessageAdapter.Item` 不携带时间，映射层一律填 0，
     * 而不是填 `System.currentTimeMillis()`：映射在每次列表变化时重跑，用"当前时间"会让
     * 同一条历史消息每次映射得到不同值 → 组件不断看到"新对象" → 无效重组。
     * 渲染层若要在 0 时隐藏时间显示，需自行判断（见移植渲染组件的接线任务）。
     */
    val timestamp: Long = 0L,
    val attachments: List<AgentAttachment> = emptyList(),
    /** 仅 TOOL 消息：工具名。null 表示无具体工具（如 TRACE 过程信息），渲染层须容忍 null。 */
    val toolName: String? = null,
    /** 仅 TOOL 消息：本次调用的入参（JSON 文本）。 */
    val toolArgs: String? = null,
    val isError: Boolean = false,
    /**
     * 仅 ASSISTANT 消息：本条承载的推理过程文本。
     *
     * <p>与 Aharou 的用法有一处语义差别：Aharou 把推理嵌套在"跟随其后的那条回答"里，
     * 而 ACS 的 `Item.Thinking` 是独立条目。映射层选择**让每个 Thinking 独立成一条
     * `content` 为空、`reasoning` 非空的 ASSISTANT 消息**，而不是跨条目合并——合并需要在
     * 列表级做前瞻，还会产生"末尾孤儿推理该挂给谁"的边界情况；独立成条则映射是逐项直译，
     * 且视觉结果与 Aharou 一致（推理气泡在上、回答在下）。
     */
    val reasoning: String? = null,
    val isCompactionMarker: Boolean = false,
    val isContextSummary: Boolean = false,
    val isCompactionFailure: Boolean = false,
    val isBackgroundNotification: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val error: String? = null,
    val variantGroupId: String? = null,
    val variantIndex: Int = 0,
    val variantCount: Int = 1,

    // ---- 以下为 ACS 扩展：Aharou 无对应概念，移植过来的组件不认识它们 ----

    /** 工具运行态。Aharou 靠 content 前缀哨兵推断运行中，ACS 有显式状态，故单独承载。 */
    val toolStatus: ToolRunStatus = ToolRunStatus.DONE,
    /** 折叠态展示的参数摘要（不含大段内容）。 */
    val toolSummary: String? = null,
    /** 工具执行期间的步骤进度；普通工具为空，子 agent 等长任务用它回看中间过程。 */
    val steps: List<String> = emptyList(),
    /**
     * 用户手动展开的意图（工具卡 / 推理块 / diff 卡共用）。
     *
     * <p>**必须存在数据里**：Compose 的 `remember` 会在条目滚出视口被回收后丢失，
     * 与 RecyclerView 复用 ViewHolder 导致"展开的是另一条"是同一类故障。
     */
    val expanded: Boolean = false,
    /** 仅 TOOL 消息：结果里的图片（base64，无 data URL 前缀）。 */
    val imageBase64: String? = null,
    val imageMimeType: String = "",
    /**
     * 仅 TOOL 消息：结构化文件改动。
     *
     * <p>与 Aharou 的差异卡渲染方式并存：映射层另把结构化结果序列化进 `content` 并标上
     * `toolName = "editFile"`，以复用 Aharou 现成的差异卡（见 `AcsMessageMapper`）。
     * 本字段保留原始结构，供将来改用原生渲染或需要精确行号/词级高亮时使用。
     */
    val diff: DiffResult? = null,
    /** 回滚句柄；null 表示该改动不可回滚。 */
    val diffId: String? = null,
    /** 该改动是否已被撤销。 */
    val reverted: Boolean = false,
    /** 本轮耗时（毫秒）；0 表示无数据。 */
    val durationMs: Long = 0L,
    /**
     * 该条是否是 ACS 的 TRACE 过程信息（工具调用/系统提示，弱化显示）。
     *
     * <p>映射到 `role = TOOL` 且 `toolName == null`，因为 Aharou 的组件对 null 工具名是安全的
     * （`toolIcon(String?)` / `toolLabel = toolName ?: "工具"`）。本字段供 ACS 自己的渲染胶水
     * 进一步弱化样式用。
     */
    val isTrace: Boolean = false,
    /**
     * 所属分组（ACS 的 `ToolGroup`）的稳定标识；null 表示不属于任何组，独立成行。
     *
     * <p><b>为什么要把组信息带到模型里，而不是让渲染层按"连续性"自行分组</b>：ACS 的成组规则
     * 不是单纯的"相邻即合并"——它由 `ToolGrouping.decide` 决定，且**子 agent 类工具
     * （`agent` / `agent_pipeline` / `agent_output`）必须独立**（它们带 `steps`，折进组里那串
     * 步骤就没有展示位了），而推理块能否并入又取决于"前一项是否已经是组"。让渲染层重新推断
     * 这套规则等于把同一个判定实现两遍，两边一旦不一致就会出现"XML 里是一组、Compose 里是两行"
     * 这类只有肉眼能发现的差异。因此映射层直接把适配器已经算好的分组结果带过来。
     *
     * <p>同一组的条目在列表里必然连续（适配器的组装顺序保证），渲染层按本字段聚合即可。
     */
    val groupId: String? = null,
    /**
     * 所属分组是否展开。仅 [groupId] 非空时有意义。
     *
     * <p>取自 `ToolGroup.pinnedExpanded`——即**用户手动开关过的意图**；用户没动过时该值为 null，
     * 映射层按"默认收起"处理（与组头组件的文档一致：默认收起，运行中也不自动弹开）。
     */
    val groupExpanded: Boolean = false,
)

/** 消息角色。取值与 Aharou 的 `MessageRole` 一致。 */
enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
}

/**
 * 工具的显式运行态。
 *
 * <p>与 `AssistantMessageAdapter.ToolStatus` 是一一对应的关系，由映射层做转换。
 * 之所以不直接复用后者：适配器是被本次移植取代的 XML 路径，新架构不该依赖它。
 * 两条路径并存期间，若需增删取值，**两边必须同步**。
 */
enum class ToolRunStatus {
    RUNNING,
    DONE,
    FAILED,
}

/**
 * 消息附件。
 *
 * <p>Aharou 的模型带附件，其组件会读 `message.attachments`；ACS 目前的条目模型没有附件概念，
 * 映射层一律给空列表。保留该类型是为了让移植过来的组件能原样编译——不是预留扩展位。
 */
@Immutable
data class AgentAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean,
)

/**
 * 「不出墨」的码点：虽非空白、也不属格式(Cf)/控制(Cc)，却渲染为零宽或纯空白。
 *
 * <p>关键是 Hangul filler 一族——它们的 Unicode 类别是 Lo（其他字母），所以**任何按类别判定的
 * 方案都抓不到**，必须显式列举。部分模型在纯工具调用轮次会吐出 U+3164 等填充字符，
 * 每调一次工具就漏出一个空气泡。
 */
internal val BLANK_GLYPH_CODE_POINTS: Set<Char> =
    setOf(
            0x115F,
            0x1160,
            0x3164,
            0xFFA0, // Hangul filler（Lo，零宽，按类别抓不到）
            0x2800, // Braille pattern blank（So，纯空点）
            0x034F, // 组合用字位连接符 CGJ
            0x17B4,
            0x17B5, // Khmer 固有元音（零宽）
            0x2060,
            0xFEFF, // 词连接符 / BOM（Cf，冗余兜底）
            0x200B,
            0x200C,
            0x200D, // 零宽空格 / ZWNJ / ZWJ（Cf，冗余兜底）
        )
        .mapTo(HashSet()) { it.toChar() }

/**
 * 文本是否含「可见(出墨)」内容。判定 = 至少有一个字符既非空白、又不属不可见类别(Cf/Cc/代理)、
 * 也不在 [BLANK_GLYPH_CODE_POINTS] 黑名单内。比 [CharSequence.isBlank] 严格得多：
 * 后者只把 whitespace 当空，会让零宽/填充字符漏出空白气泡。
 *
 * <p>这是「是否渲染助手气泡」的唯一关门，各渲染/过滤层共用它。注意：保留代理对（emoji 等）
 * 与普通可见字符；零宽连接符 ZWJ 只在「整条文本是否为空」上当空，不会从展示文本里被剔除，
 * 故 emoji 连字序列不受影响。
 */
fun CharSequence.hasVisibleContent(): Boolean {
    var i = 0
    while (i < length) {
        val ch = get(i)
        if (!ch.isWhitespace() &&
            ch.category != CharCategory.FORMAT &&
            ch.category != CharCategory.CONTROL &&
            ch !in BLANK_GLYPH_CODE_POINTS) {
            // 代理区划（emoji 所在补全平面）仅在成对的合法高/低代理时算可见，孤立或残缺代理仍判空
            if (ch.category != CharCategory.SURROGATE) return true
            if (Character.isHighSurrogate(ch) &&
                i + 1 < length &&
                Character.isLowSurrogate(get(i + 1)))
                return true
        }
        i++
    }
    return false
}
