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

package com.tom.rv2ide.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.R
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory
import com.tom.rv2ide.artificial.agent.AssistantCodeHighlighter
import com.tom.rv2ide.artificial.agent.AssistantDiffRenderer
import com.tom.rv2ide.artificial.agent.AssistantMarkdown
import com.tom.rv2ide.artificial.agent.AssistantMessageActions
import com.tom.rv2ide.artificial.agent.AssistantToolGroupSummary
import com.tom.rv2ide.artificial.agent.AssistantUiStyleStore
import com.tom.rv2ide.artificial.agent.CodeFenceParser
import com.tom.rv2ide.artificial.agent.ToolResultImageSupport
import com.tom.rv2ide.artificial.agent.DiffLineType
import com.tom.rv2ide.artificial.agent.DiffResult
import com.tom.rv2ide.databinding.ItemAssistantDiffBinding
import com.tom.rv2ide.databinding.ItemAssistantMessageBinding
import com.tom.rv2ide.databinding.ItemAssistantThinkingBinding
import com.tom.rv2ide.databinding.ItemDiffGroupBinding
import com.tom.rv2ide.databinding.ItemToolCallBinding
import com.tom.rv2ide.databinding.ItemToolGroupBinding
import com.tom.rv2ide.resources.R.string

/**
 * 悬浮助手面板的会话列表：文本消息与工具卡片混排。
 *
 * <p><b>为什么需要工具卡片</b>：把工具调用拼成一行纯文本塞进状态栏，用户无法判断
 * 「这次调用到底改了什么」——参数被截断、输出看不到、失败原因只有一个行内片段。
 * 卡片折叠时给摘要、展开时给完整的输入输出，才使过程可审查。
 *
 * <p><b>精确通知而非整表刷新</b>：流式输出期间每收到一个增量就更新末条消息，
 * `notifyDataSetChanged` 会让滚动位置抖动并重建全部 ViewHolder。
 * [Item] 做成不可变 data class，使"内容变了"这件事在代码里一眼可见。
 */
class AssistantMessageAdapter(
    /**
     * 外观配置（字号 / 卡片大小 / 字体颜色 / 头像）。
     *
     * <p>可为 null：适配器在若干测试与预览场景被无参构造，那些场景用内置默认值即可
     * （与 {@link AssistantUiStyleStore} 的默认值一致）。生产路径由
     * FloatingAssistantView 注入真实实例。
     */
    private val styleStore: com.tom.rv2ide.artificial.agent.AssistantUiStyleStore? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

  /**
   * 按工具名解析展示分类。
   *
   * <p>做成注入的窄接口而不是直接持有 `ToolRegistry`：适配器只想知道「这张卡片该用什么
   * 颜色和图标」，不该因此依赖工具执行层。解析不到时返回 GENERIC，卡片仍能正常显示。
   */
  fun interface CategoryResolver {
    fun categoryOf(toolName: String): ToolDisplayCategory
  }

  private var categoryResolver: CategoryResolver? = null

  /** 设置分类解析器。未设置时所有卡片按 GENERIC 渲染。 */
  fun setCategoryResolver(resolver: CategoryResolver?) {
    this.categoryResolver = resolver
  }

  // ---- 外观配置的读取入口（styleStore 为 null 时回退默认值）----

  /** 该控件的目标字号（sp），已按用户设定缩放。 */
  private fun scaledSp(baseSp: Float): Float =
      styleStore?.scaleSp(baseSp) ?: baseSp

  /** 卡片缩放系数。 */
  private fun cardScale(): Float =
      styleStore?.cardScale ?: AssistantUiStyleStore.DEFAULT_CARD_SCALE

  /** 助手正文自定义颜色；0 表示跟随主题。 */
  private fun customAssistantTextColor(): Int =
      styleStore?.textColor ?: AssistantUiStyleStore.COLOR_FOLLOW_THEME

  /** 自定义头像路径；null 表示用内置头像。 */
  private fun avatarPath(): String? = styleStore?.avatarPath

  /**
   * 内置头像的资源 id。
   *
   * <p>自定义图片（{@link #avatarPath}）存在时优先用它——用户明确选了自己的图，
   * 不该被内置选择覆盖。
   */
  private fun builtinAvatarRes(): Int =
      when (styleStore?.avatarBuiltin ?: AssistantUiStyleStore.AVATAR_BUILTIN_DEFAULT) {
        AssistantUiStyleStore.AVATAR_BUILTIN_ANIME -> R.drawable.assistant_avatar_anime
        AssistantUiStyleStore.AVATAR_BUILTIN_CAT -> R.drawable.assistant_avatar_cat
        AssistantUiStyleStore.AVATAR_BUILTIN_FOX -> R.drawable.assistant_avatar_fox
        AssistantUiStyleStore.AVATAR_BUILTIN_SPARK -> R.drawable.assistant_avatar_spark
        else -> R.drawable.assistant_avatar_default
      }

  /**
   * 把 TextView 的字号设为「基准值 × 用户缩放」。
   *
   * <p>布局里的字号全部来自 XML 的 TextAppearance，代码里没有一处 setTextSize——
   * 要做运行时缩放只能在这里重设。基准值见 {@link AssistantUiStyleStore.BaseSp}。
   */
  private fun applyTextScale(view: TextView, baseSp: Float) {
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(baseSp))
  }

  /** 消息角色。UI 只区分"谁说的"，不关心协议层的具体类型。 */
  enum class Role {
    USER,
    ASSISTANT,
    /** 工具调用/系统提示等过程信息，弱化显示。 */
    TRACE
  }

  /** 工具调用状态。 */
  enum class ToolStatus {
    RUNNING,
    DONE,
    FAILED
  }

  /** 列表项。id 稳定，用于流式更新与状态回填。 */
  sealed class Item {
    abstract val id: Long
  }

  /**
   * 一条文本消息。
   *
   * @param id 稳定标识：流式更新末条消息时 id 不变，DiffUtil 才能识别为"同一项内容变了"
   * @param diffId 该消息对应一次可回滚的文件改动；null 表示不可回滚（多数消息如此）
   * @param reverted 该改动是否已被撤销。撤销后按钮要变成不可再点的状态——
   *   否则用户会重复点击并收到「已经回滚过了」的错误
   */
  data class Message(
      override val id: Long,
      val role: Role,
      val text: String,
      val diffId: String? = null,
      val reverted: Boolean = false,
      /**
       * 本轮耗时（毫秒）；0 表示无数据、不显示。
       *
       * <p><b>为什么由视图侧自记而不是协议层给</b>：`AgentSession` 的 `startedAt` 是方法内
       * 局部变量，`turnStarted`/`turnFinished` 事件都不带时间戳，`AgentRunResult` 也只有
       * turns/toolCallCount。要在协议层加时间戳需要改动事件契约与所有构造点；
       * 而「这一轮花了多久」纯粹是展示信息，视图在 `RUN_STARTED`/`RUN_FINISHED` 时
       * 自己记两个时刻即可，零协议层改动。
       */
      val durationMs: Long = 0L,
  ) : Item()

  /**
   * 一段模型推理过程（思维链）。
   *
   * <p><b>为什么要单独一种 item 而不是塞进 [Message]</b>：推理与正文的展示规则完全不同
   * ——推理默认折叠、限高、弱化配色，正文则完整展开。混在一起会让「折叠哪个」这件事
   * 无法表达。
   *
   * @param text 累积的推理文本。流式期间不断追加
   * @param streaming 是否仍在生成。决定标题文案与进度指示器
   * @param expanded 展开状态。**存在数据里而不是 ViewHolder 里**——RecyclerView 会复用
   *   ViewHolder，把展开状态放在控件上会导致滚动后「展开的是另一条」
   */
  data class Thinking(
      override val id: Long,
      val text: String,
      val streaming: Boolean = true,
      val expanded: Boolean = false,
  ) : Item()

  /**
   * 一次工具调用。
   *
   * @param summary 折叠态展示的参数摘要（不含大段内容）
   * @param input 完整参数 JSON，展开后可见
   * @param output 工具输出；运行中为空
   * @param expanded 展开状态。**必须存在数据里而不是 ViewHolder 里**——
   *   RecyclerView 会复用 ViewHolder，把展开状态放在控件上会导致滚动后
   *   「展开的是另一条卡片」
   */
  data class ToolCall(
      override val id: Long,
      val toolName: String,
      val summary: String,
      val input: String,
      val output: String = "",
      val status: ToolStatus = ToolStatus.RUNNING,
      val expanded: Boolean = false,
      /**
       * 步骤进度记录（工具执行期间经 PROGRESS 事件累积）。
       *
       * <p>对普通工具通常为空；对子 agent 这类长任务，它记录运行期间每步在做什么——
       * 子 agent 的中间过程刻意不进主对话，这些行是用户回看时的唯一凭据。
       */
      val steps: List<String> = emptyList(),
      /**
       * 工具结果里的图片（base64，不含 data URL 前缀）；无图为 null。
       *
       * <p>工具（`phone_screenshot`、`image_generation`，以及第三方 MCP server）可以返回
       * 一张图片。协议层早就把 base64 带回来了（{@code ToolResult.getImageBase64}），
       * 模型也一直能看到它（{@code AgentSession.toolResultImageJson} 回灌），
       * 但界面此前**从不显示**——用户只知道「工具跑完了」，看不到截的是什么图。
       *
       * <p>存 base64 而不是解码后的字节：这个 data class 会进 `snapshot()` 并被比较，
       * 持有几 MB 的 ByteArray 会让每次 copy 都复制一份大对象；解码在绑定时按需做，
       * 且只在可见时做。
       */
      val imageBase64: String? = null,
      val imageMimeType: String = "",
  ) : Item()

  /**
   * 一次文件改动的 diff 卡片。
   *
   * <p><b>为什么单独一种 item 而不是复用 [Message]</b>：消息是纯文本，渲染成一段
   * markdown；diff 需要行号列、增删标记、词级底色与折叠，这些都靠 [DiffResult]
   * 而不是文本表达。塞进 Message 就只能把 diff 预先拍成字符串，行号列宽与词级
   * 高亮将无法按控件宽度重算。
   *
   * @param diffId 回滚句柄。卡片上的撤销按钮据此调用 `DiffReverter`
   * @param result diff 结果；为 null 时看 [computing]——true 表示还在算，
   *   false 表示记录已过期（`FileDiffStore` 会裁剪旧记录），两者文案不同
   * @param expanded 展开状态。**存在数据里而不是 ViewHolder 里**——RecyclerView 会复用
   *   ViewHolder，把展开状态放在控件上会导致滚动后「展开的是另一条」
   * @param reverted 是否已撤销，决定按钮是否可再点
   * @param computing diff 内容仍在后台计算中。为 true 时 [result] 必为 null——
   *   「还没算完」与「记录已过期」都表现为 result==null，但文案必须不同：
   *   运行刚结束时用户最可能立刻展开汇总组，此时说「记录已过期」是错误信息。
   */
  data class Diff(
      override val id: Long,
      val filePath: String,
      val diffId: String?,
      val result: DiffResult?,
      val expanded: Boolean = false,
      val reverted: Boolean = false,
      val computing: Boolean = false,
  ) : Item()

  /**
   * 一轮运行的**全部文件改动汇总**卡片。
   *
   * <p><b>为什么不是一张卡片一次改动</b>：改一个文件冒一张卡，长任务里十几次编辑会在
   * 对话流里插进十几张 diff——用户读的是回答，却被 diff 反复打断；而且每张卡的
   * 「改了哪个文件」要一张张看才知道。改成运行结束后出一张汇总卡，折叠态一行说明
   * 「本轮改动 N 个文件 · +X -Y」，展开才逐文件列出（每行复用 [Diff] 卡片本体）。
   *
   * <p>子项仍是 [Diff] 而不是新结构：单文件卡片的渲染（行号列、词级高亮、复制、撤销）
   * 已经存在且有测试，换成平行结构只会让两处渲染逻辑分叉。
   */
  data class DiffGroup(
      override val id: Long,
      val children: List<Diff>,
      /** 用户手动展开/折叠的意图；null 表示跟随默认（折叠）。 */
      val pinnedExpanded: Boolean? = null,
  ) : Item() {
    val totalAdded: Int
      get() = children.sumOf { it.result?.added ?: 0 }

    val totalRemoved: Int
      get() = children.sumOf { it.result?.removed ?: 0 }

    /**
     * 去重后的文件数。
     *
     * <p>组头说的是「改了几个文件」，而 `children` 是**改动次数**——同一个文件改两次会有
     * 两项。直接取 size 会把「改了 1 个文件」说成 2 个。列表本身不去重：两次改动各是一份
     * 独立记录，都值得看。
     */
    val fileCount: Int
      get() = children.map { it.filePath }.distinct().size
  }

  /**
   * 一组连续的工具调用与推理块，折叠态渲染成一行活动摘要。
   *
   * <p><b>为什么要分组</b>：一次任务里连续十几次 `file_read` 会产生十几张卡片，
   * 把正文挤出屏幕。分组后折叠成一句「读取 5 个文件 · 执行 2 条命令」，
   * 需要细节时再展开。
   *
   * <p><b>为什么 Thinking 也进组、不打断分组</b>（实测修正）：最初的设计让 Thinking
   * 打断分组，理由是「它是我方独立的折叠条目，不该被吸收」。但真机日志证明这个假设不成立——
   * 每次工具调用**之前**模型都会先输出一段推理，于是「末项永远是 Thinking」，
   * 分组条件永不满足，**分组等于没做**（实测：连续 12 个 file_read 仍产生 12 张卡片）。
   * 因此改为把 Thinking 也收进组内：它是「这一轮在干什么」的一部分，本就属于同一段活动。
   *
   * <p><b>为什么是独立 item 而不是给 [ToolCall] 加 groupId</b>：加 id 并不减少条目数，
   * 拿不到「少渲染」这个主要收益；而且组内改动只需一次 `notifyItemChanged`，
   * 逐条通知会触发 N 次重绑。
   *
   * @param children 组内的条目，按出现顺序，元素是 [ToolCall] 或 [Thinking]。
   *   **它们不在顶层 items 里**，因此所有按 id 查找的方法都必须能穿透到这里
   *   （见 [indexOfToolCall] / [replaceToolCall]）——这是本类最容易出错的约定，
   *   漏掉一处就是「子 agent 步骤丢失」或「图片不显示」这类静默故障。
   * @param pinnedExpanded 用户手动展开/折叠的意图；null 表示「跟随自动策略」。
   *   非 null 时压过自动折叠，否则用户点了展开、下一轮事件一来又被自动折回去。
   */
  data class ToolGroup(
      override val id: Long,
      val children: List<Item>,
      val pinnedExpanded: Boolean? = null,
  ) : Item() {
    /** 组内的工具调用（不含推理块）。摘要与失败计数只关心这些。 */
    val toolCalls: List<ToolCall>
      get() = children.filterIsInstance<ToolCall>()
  }

  private val items = mutableListOf<Item>()
  private var nextId = 0L

  /**
   * 撤销按钮的点击回调；由视图层注入（回滚需要工作区上下文才能安全执行）。
   *
   * <p>同时传消息 id 与 diffId：调用方需要前者把该条标记为已撤销，后者去执行回滚。
   */
  private var onRevert: ((Long, String) -> Unit)? = null

  fun setOnRevertClickListener(listener: (Long, String) -> Unit) {
    this.onRevert = listener
  }

  /**
   * 消息长按菜单回调：用于弹出复制（原文/纯文）、引用提问等高级操作。
   */
  var onMessageActionRequested: ((Message, View) -> Unit)? = null

  /**
   * 图片解码用的协程作用域；由视图层注入（适配器本身不是 LifecycleOwner）。
   *
   * <p>注入而不是在适配器里自建作用域：解码必须在视图销毁时一并取消，
   * 否则回到主线程写一个已 detach 的 ImageView。视图层用 lifecycleScope，
   * 天然满足这一点。
   */
  internal var imageScope: kotlinx.coroutines.CoroutineScope? = null

  /**
   * 当前是否有运行在进行。
   *
   * <p>由视图层在 `RUN_STARTED` / `RUN_FINISHED` / `FAILED` 时置位。
   * 工具组的自动折叠依赖它：只有「运行中**且**位于列表末尾」的组才默认展开，
   * 其余（历史里的、已被后续内容顶下去的）一律折叠——一次任务跑完，
   * 十几张工具卡片应该自动收成一行，而不是铺满屏幕。
   */
  internal var isRunActive: Boolean = false
    set(value) {
      if (field == value) {
        return
      }
      field = value
      // 活动态变化会改变末组的折叠状态，必须重绑它。
      // 不重绑的话，运行结束时最后一组仍停在展开态（用户得滚动一下才收起来）。
      val last = items.lastIndex
      if (last >= 0 && items[last] is ToolGroup) {
        notifyItemChanged(last)
      }
    }

  // ---- 文本消息 ----

  /** 追加一条消息并返回它的 id；流式更新用该 id 定位。 */
  fun append(role: Role, text: String): Long = append(role, text, null, 0L)
  fun append(role: Role, text: String, diffId: String?): Long = append(role, text, diffId, 0L)
  fun append(role: Role, text: String, diffId: String?, durationMs: Long): Long {
    val id = nextId++
    items.add(Message(id, role, text, diffId, reverted = false, durationMs = durationMs))
    notifyItemInserted(items.size - 1)
    return id
  }

  // ---- 改动汇总卡片 ----

  /**
   * 追加一张「本轮全部改动」汇总卡片。
   *
   * <p>在**运行结束时**调用一次，而不是每次改动调用（见 [DiffGroup] 的说明）。
   *
   * @param entries 本轮改动的文件，按发生顺序；diffId 为空时该行只显示文件名
   * @return 各子项的 id，与 [entries] 同序，供异步回填 diff 内容
   */
  fun appendDiffGroup(entries: List<Pair<String, String?>>): List<Long> {
    if (entries.isEmpty()) {
      return emptyList()
    }
    // computing=true：内容由调用方随后异步回填（见 updateDiff）。不标这一位的话，
    // 卡片在回填到达前会显示「记录已过期」——运行刚结束时用户最可能立刻展开，
    // 那是一条错误信息。
    val children =
        entries.map { (path, diffId) ->
          Diff(nextId++, path, diffId, result = null, computing = true)
        }
    items.add(DiffGroup(nextId++, children))
    notifyItemInserted(items.size - 1)
    return children.map { it.id }
  }

  /**
   * 在顶层或汇总组内替换一张 diff 卡片。
   *
   * <p>与 [replaceToolCall] 同构：汇总组把子项收进 `children` 后它们不在顶层，
   * 按 id 找子项的方法都必须能穿透，否则「展开单文件」「撤销」「回填 diff 内容」
   * 会全部静默失效（不报错，只是点了没反应）——这与分组功能刚上线时
   * 工具卡片踩过的坑完全相同。
   */
  private fun replaceDiff(id: Long, transform: (Diff) -> Diff): Boolean {
    val topIndex = indexOfDiff(id)
    if (topIndex < 0) {
      return false
    }
    when (val top = items[topIndex]) {
      is Diff -> {
        if (top.id == id) {
          items[topIndex] = transform(top)
          notifyItemChanged(topIndex)
          return true
        }
      }
      is DiffGroup -> {
        val childIndex = top.children.indexOfFirst { it.id == id }
        if (childIndex >= 0) {
          val next = top.children.toMutableList()
          next[childIndex] = transform(next[childIndex])
          items[topIndex] = top.copy(children = next)
          notifyItemChanged(topIndex)
          return true
        }
      }
      else -> {}
    }
    return false
  }

  /** diff 卡片（含汇总组内）所在的顶层下标；未找到为 -1。 */
  private fun indexOfDiff(id: Long): Int =
      items.indexOfFirst { item ->
        when (item) {
          is Diff -> item.id == id
          is DiffGroup -> item.children.any { it.id == id }
          else -> false
        }
      }

  /**
   * 回填 diff 计算结果。
   *
   * <p>diff 在后台线程算，算完才回填。回填时**必须按 id 重新定位**而不是记下标：
   * 计算期间列表可能已经插入了新的消息或工具卡片，下标会漂移。
   *
   * <p>同时把 [Diff.computing] 置回 false：算完了才谈得上「记录已过期」
   * （result 仍为 null 时就是查不到记录，而不是还没算）。
   */
  fun updateDiff(id: Long, result: DiffResult?) {
    replaceDiff(id) { it.copy(result = result, computing = false) }
  }

  /** 切换 diff 卡片的展开状态。 */
  fun toggleDiffExpanded(id: Long) {
    replaceDiff(id) { it.copy(expanded = !it.expanded) }
  }

  /** 把 diff 卡片标记为已撤销，使按钮进入禁用态。 */
  fun markDiffReverted(id: Long) {
    replaceDiff(id) { it.copy(reverted = true) }
  }

  /** 切换改动汇总卡片的展开状态；把用户意图写进 [DiffGroup.pinnedExpanded]。 */
  fun toggleDiffGroupExpanded(id: Long, currentlyCollapsed: Boolean) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? DiffGroup ?: return
    items[index] = old.copy(pinnedExpanded = currentlyCollapsed)
    notifyItemChanged(index)
  }

  /**
   * 把某条消息标记为已撤销。
   *
   * <p>只改状态不删消息：撤销是用户可见的动作，消息凭空消失会让人以为列表出了问题。
   */
  fun markReverted(id: Long) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    if (old.reverted) {
      return
    }
    items[index] = old.copy(reverted = true)
    notifyItemChanged(index)
  }

  /**
   * 覆盖指定消息的正文。
   *
   * <p>id 不存在时静默忽略——消息可能已被清空（用户点了"新会话"），
   * 此时迟到的增量不该让界面崩掉。
   */
  fun update(id: Long, text: String) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    if (old.text == text) {
      return
    }
    items[index] = old.copy(text = text)
    notifyItemChanged(index)
  }

  /**
   * 回填本轮耗时到指定消息上。
   *
   * <p>耗时由视图侧在 `RUN_STARTED` / `RUN_FINISHED` 时自己记（见 SessionUiState），
   * 不走协议层——协议层要补时间戳得改事件契约与全部构造点，而它只是展示信息。
   */
  fun updateDuration(id: Long, durationMs: Long) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    items[index] = old.copy(durationMs = durationMs)
    notifyItemChanged(index)
  }

  /** 追加到指定消息的正文之后（增量累积用）。 */
  fun appendTo(id: Long, delta: String) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    items[index] = old.copy(text = old.text + delta)
    notifyItemChanged(index)
  }

  /**
   * 移除一条**内容为纯空白**的消息，返回是否真的移除了。
   *
   * <p><b>为什么需要</b>：模型「只发起工具调用、不写正文」的轮次里，流式阶段仍会吐出
   * 纯空白增量（`"\n"`）。这些增量建出了助手气泡，但内容只有换行——渲染出来是一个
   * 空白的灰色气泡，在工具卡片上方堆一大片。用户看到的是「一堆没内容的助手消息」。
   *
   * <p>只删纯空白的：有任何实际内容就保留，哪怕只有一个字。判定用 isBlank 而非
   * isEmpty，因为 `"\n\n"` 正是最常见的形态。
   */
  fun removeIfBlank(id: Long): Boolean {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return false
    if (old.role != Role.ASSISTANT || !old.text.isBlank()) {
      return false
    }
    items.removeAt(index)
    notifyItemRemoved(index)
    return true
  }

  /**
   * 按 id 移除任意类型的条目（消息 / 工具卡片 / 推理块）。
   *
   * <p>供断流重发使用：重发会产生一份全新的回答，上一次尝试的部分输出必须整条丢弃，
   * 否则两段内容会在列表里并存。与 [removeIfBlank] 的区别是它**无条件移除**——
   * 重试场景下部分内容可能有几百字，但依然是作废的。
   *
   * @return 是否真的移除了
   */
  fun remove(id: Long): Boolean {
    val index = indexOf(id)
    if (index < 0) {
      return false
    }
    items.removeAt(index)
    notifyItemRemoved(index)
    return true
  }

  /** 按 id 取文本消息；不存在或不是消息时返回 null。 */
  fun find(id: Long): Message? = items.firstOrNull { it.id == id } as? Message

  /** 按 id 取工具卡片（含组内）；不存在时返回 null。 */
  fun findToolCall(id: Long): ToolCall? =
      when (val top = items.getOrNull(indexOfToolCall(id))) {
        is ToolCall -> top.takeIf { it.id == id }
        is ToolGroup -> top.toolCalls.firstOrNull { it.id == id }
        else -> null
      }

  // ---- 工具卡片 ----

  /** 追加一张「运行中」的工具卡片并返回它的 id。 */
  fun appendToolCall(toolName: String, summary: String, input: String): Long {
    val id = nextId++
    val call = ToolCall(id, toolName, summary, input)
    val last = items.lastOrNull()

    // 决策逻辑在 ToolGrouping（纯函数、可单测）；这里只负责改列表与发通知。
    // 抽出去的原因：notify* 依赖 RecyclerView 的观察者机制，纯 JVM 单测驱动不了，
    // 而「什么该成组」恰恰是最容易错、最需要测试的部分。
    val decision =
        ToolGrouping.decide(
            lastIsGroup = last is ToolGroup,
            lastToolName = (last as? ToolCall)?.toolName,
            incomingToolName = toolName,
        )
    when (decision) {
      ToolGrouping.Decision.MERGE_INTO_LAST_GROUP -> {
        val group = last as ToolGroup
        items[items.size - 1] = group.copy(children = group.children + call)
        // 必须同时通知「旧的末项」：它从「末项」变成「非末项」时，自动折叠策略
        // 会把它从展开改为折叠，但仅 notifyItemInserted 不会重绑它，
        // 于是它会永久停在展开态（直到用户滚动离开再回来）。
        notifyItemChanged(items.size - 1)
        notifyItemInserted(items.size)
      }
      ToolGrouping.Decision.MERGE_LAST_TWO -> {
        // 首两张合并成组：把旧的单卡片替换成组，位置不变。
        val groupId = nextId++
        items[items.size - 1] = ToolGroup(groupId, listOf(last as ToolCall, call))
        notifyItemChanged(items.size - 1)
      }
      ToolGrouping.Decision.APPEND -> {
        items.add(call)
        notifyItemInserted(items.size - 1)
      }
    }
    return id
  }

  /**
   * 回填工具执行结果。
   *
   * <p>id 不存在时静默忽略：用户可能已点了「新会话」清空列表，迟到的结果不该崩。
   */
  fun completeToolCall(id: Long, output: String, isError: Boolean) {
    completeToolCall(id, output, isError, null, "")
  }

  /**
   * 完成一次工具调用，并带上结果里的图片。
   *
   * <p>重载而不是改签名：旧调用点（以及测试）不需要知道图片，改动它们纯属噪声。
   *
   * @param imageBase64 工具结果图片的 base64；无图为 null
   */
  fun completeToolCall(
      id: Long,
      output: String,
      isError: Boolean,
      imageBase64: String?,
      imageMimeType: String,
  ) {
    // 必须穿透分组：工具可能已被并入 ToolGroup，顶层 items 找不到它。
    // 走 indexOf(id) + `as? ToolCall` 的旧写法在分组后会静默失败
    // ——卡片永久停在「运行中」，且图片永远不会出现。
    replaceToolCall(id) { old ->
      old.copy(
          output = output,
          status = if (isError) ToolStatus.FAILED else ToolStatus.DONE,
          imageBase64 = imageBase64,
          imageMimeType = imageMimeType,
      )
    }
  }

  /**
   * 向「运行中」的工具卡片追加一行步骤记录。
   *
   * <p>只回填到最近一张仍在运行的卡片（与 TOOL_FINISHED 的关联方式一致）——
   * 步骤由工具执行期间产生，此刻不可能有别的卡片在跑。没有运行中的卡片时静默丢弃：
   * 迟到的进度行没有归属，插到任意卡片上都是错误关联。
   */
  fun appendToolStep(step: String) {
    // 找「最近一张运行中的卡片」，但必须能看见组内的：分组后运行中的卡片
    // 通常就在末组里，只看顶层会完全找不到目标，子 agent 的步骤会**静默丢失**
    // ——用户看到的是「步骤区一直空着」，没有任何错误提示。
    var target: ToolCall? = null
    for (item in items) {
      when (item) {
        is ToolCall -> if (item.status == ToolStatus.RUNNING) target = item
        is ToolGroup -> item.toolCalls.filter { it.status == ToolStatus.RUNNING }.lastOrNull()?.let {
          target = it
        }
        else -> {}
      }
    }
    val running = target ?: return
    replaceToolCall(running.id) { it.copy(steps = it.steps + step) }
  }

  /**
   * 覆盖指定卡片的步骤列表（回放历史时使用）。
   *
   * <p>与 [appendToolStep] 的分工：那个走实时增量（运行中累积），这个走历史恢复
   * （一次性给出完整列表）。id 不存在时静默忽略，与其它回填方法同约定。
   */
  fun setToolSteps(id: Long, steps: List<String>) {
    // 穿透分组：回放历史时工具卡片可能已被并入组内。
    replaceToolCall(id) { old ->
      if (old.steps == steps) old else old.copy(steps = steps)
    }
  }

  /** 所有工具调用，含组内的（顺序保持出现顺序）。 */
  private fun allToolCalls(): List<ToolCall> =
      items.flatMap { item ->
        when (item) {
          is ToolCall -> listOf(item)
          is ToolGroup -> item.toolCalls
          else -> emptyList()
        }
      }

  /** 最近一张仍在运行的工具卡片 id；没有则 null。 */
  fun lastRunningToolCallId(): Long? =
      allToolCalls().lastOrNull { it.status == ToolStatus.RUNNING }?.id

  /** 切换卡片展开状态（含组内子卡片）。 */
  fun toggleExpanded(id: Long) {
    replaceToolCall(id) { old -> old.copy(expanded = !old.expanded) }
  }

  // ---- 思维链 ----

  /**
   * 追加一段推理增量，返回承载它的思维链块 id。
   *
   * <p>[lastThinkingId] 为 null 时新建一块。一次运行里的多轮推理会各建一块——
   * 因为中间隔着工具调用，合并成一块会让「这段推理属于哪一步」看不出来。
   *
   * @param streaming 新建的块是否标记为「仍在生成」。**回放历史时必须传 false**：
   *     历史里的推理早已结束，传 true（默认）会让回放出的块永久显示「思考中」与
   *     进度动画——重启应用后历史对话里所有思考块都卡在思考态，正是这个默认值造成的。
   *     追加到既有块时该参数无效（块自身的状态保持不变）。
   */
  fun appendThinking(delta: String, lastThinkingId: Long?, streaming: Boolean = true): Long {
    // 先尝试追加到既有块（**必须穿透分组**）。
    //
    // <p>推理块会被 [appendThinking] 自己收进 [ToolGroup]（见下方 shouldThinkingJoinGroup），
    // 此后它只存在于 `group.children` 里。早先这里走 `indexOf(id) + as? Thinking`——
    // 那是**顶层**查找，组内块找不到，于是每一次推理增量都新建一块。
    // 真机表现：一轮里十几次工具调用各带一段推理，产生十几条「思考过程」折叠条，
    // 且空白增量同样建块（下面的空白守卫只看 lastThinkingId 是否为 null，
    // 而它此刻非 null、只是查不到），出现一排点开什么都没有的空折叠条。
    if (lastThinkingId != null) {
      val appended = replaceThinking(lastThinkingId) { it.copy(text = it.text + delta) }
      if (appended) {
        return lastThinkingId
      }
    }
    // 纯空白增量不建块。模型在轮次边界会吐出 "\n" 之类的增量，
    // 建出来的块标题是「思考过程」但内容是空的——用户看到一排空折叠条，
    // 以为是渲染坏了。
    if (delta.isBlank()) {
      return -1L
    }
    val id = nextId++
    val block = Thinking(id, delta, streaming = streaming, expanded = false)

    // 末项是工具组时把推理块也收进去，**不打断分组**。
    //
    // 实测修正：最初让 Thinking 打断分组，理由是「它是独立的折叠条目」。但真机日志显示
    // 每次工具调用**之前**模型都会先输出一段推理，于是末项永远是 Thinking，
    // 分组条件永不满足——连续 12 个 file_read 仍产生 12 张卡片，分组等于没做。
    // 推理本就是「这一轮在干什么」的一部分，收进同一组才符合用户的阅读单位。
    val last = items.lastOrNull()
    if (last is ToolGroup && ToolGrouping.shouldThinkingJoinGroup(true)) {
      items[items.size - 1] = last.copy(children = last.children + block)
      notifyItemChanged(items.size - 1)
      return id
    }

    items.add(block)
    notifyItemInserted(items.size - 1)
    return id
  }

  /**
   * 结束流式：标题从「思考中」切到「已完成思考」，进度指示器隐藏。
   *
   * <p>穿透分组：推理块现在可能被收在 [ToolGroup] 里（见 [appendThinking]），
   * 只查顶层会让组内推理块永久停在「思考中」。
   */
  fun finishThinking(id: Long) {
    replaceThinking(id) { if (it.streaming) it.copy(streaming = false) else it }
  }

  fun toggleThinkingExpanded(id: Long) {
    replaceThinking(id) { it.copy(expanded = !it.expanded) }
  }

  /** 把仍在流式的思维链块全部收尾（运行结束或失败时调用），含组内的。 */
  fun finishAllThinking() {
    for (i in items.indices) {
      when (val top = items[i]) {
        is Thinking -> {
          if (top.streaming) {
            items[i] = top.copy(streaming = false)
            notifyItemChanged(i)
          }
        }
        is ToolGroup -> {
          if (top.children.none { it is Thinking && it.streaming }) {
            continue
          }
          items[i] =
              top.copy(
                  children =
                      top.children.map {
                        if (it is Thinking && it.streaming) it.copy(streaming = false) else it
                      })
          notifyItemChanged(i)
        }
        else -> {}
      }
    }
  }

  /** 在顶层或组内替换一个推理块。与 [replaceToolCall] 同构。 */
  private fun replaceThinking(id: Long, transform: (Thinking) -> Thinking): Boolean {
    val topIndex = items.indexOfFirst { item ->
      when (item) {
        is Thinking -> item.id == id
        is ToolGroup -> item.children.any { it is Thinking && it.id == id }
        else -> false
      }
    }
    if (topIndex < 0) {
      return false
    }
    when (val top = items[topIndex]) {
      is Thinking -> {
        if (top.id == id) {
          items[topIndex] = transform(top)
          notifyItemChanged(topIndex)
          return true
        }
      }
      is ToolGroup -> {
        val childIndex = top.children.indexOfFirst { it is Thinking && it.id == id }
        if (childIndex >= 0) {
          val next = top.children.toMutableList()
          next[childIndex] = transform(next[childIndex] as Thinking)
          items[topIndex] = top.copy(children = next)
          notifyItemChanged(topIndex)
          return true
        }
      }
      else -> {}
    }
    return false
  }

  /**
   * 把指定的工具卡片标记为失败（例如循环被取消时仍在运行的那张）。
   *
   * <p>必须穿透分组：漏掉会让**被分组的工具在失败后永久停在「运行中」**，
   * 用户以为任务还在跑（而实际上整个运行已经结束）。
   */
  fun failToolCall(id: Long, reason: String) {
    replaceToolCall(id) { old ->
      if (old.status != ToolStatus.RUNNING) old
      else old.copy(status = ToolStatus.FAILED, output = reason)
    }
  }

  /** 仍在「运行中」的工具卡片 id；循环异常结束时用来收尾。 */
  fun runningToolCallIds(): List<Long> =
      allToolCalls().filter { it.status == ToolStatus.RUNNING }.map { it.id }

  /** 末条工具卡片的 id；用于把 TOOL_FINISHED 关联到对应的 TOOL_STARTED。 */
  fun lastToolCallId(): Long? = allToolCalls().lastOrNull()?.id

  // ---- 列表维护 ----

  fun clear() {
    val oldSize = items.size
    items.clear()
    if (oldSize > 0) {
      notifyItemRangeRemoved(0, oldSize)
    }
  }

  fun isEmpty(): Boolean = items.isEmpty()

  /** 全部项的只读快照，供调试与测试。 */
  fun snapshot(): List<Item> = items.toList()

  private fun indexOf(id: Long): Int = items.indexOfFirst { it.id == id }


  /**
   * 工具调用在列表里的位置（可穿透分组）。
   *
   * <p><b>为什么需要它</b>：分组后 [ToolCall] 可能嵌套在 [ToolGroup].children 里，
   * 顶层 `items` 找不到它。所有「按工具 id 定位」的操作都必须走这里，否则会出现
   * 静默故障：图片不显示、子 agent 步骤丢失、卡片永久停在「运行中」——
   * 都不报错，只是功能悄悄失效。
   *
   * @return 顶层下标；未找到为 -1
   */
  private fun indexOfToolCall(id: Long): Int =
      items.indexOfFirst { item ->
        when (item) {
          is ToolCall -> item.id == id
          is ToolGroup -> item.children.any { it.id == id }
          else -> false
        }
      }

  /**
   * 在某个顶层条目的范围内替换一个工具调用。
   *
   * <p>落在顶层（未分组）时直接替换；落在组内时**派生一个新的 [ToolGroup]** 而不是就地改
   * children——data class 的 children 是 `List`，就地改会让新旧状态指向同一份数据，
   * RecyclerView 的 diff 与快照对比都会失效。
   *
   * @return 是否找到了目标
   */
  private fun replaceToolCall(id: Long, transform: (ToolCall) -> ToolCall): Boolean {
    val topIndex = indexOfToolCall(id)
    if (topIndex < 0) {
      return false
    }
    when (val top = items[topIndex]) {
      is ToolCall -> {
        if (top.id == id) {
          items[topIndex] = transform(top)
          notifyItemChanged(topIndex)
          return true
        }
      }
      is ToolGroup -> {
        val childIndex = top.children.indexOfFirst { it is ToolCall && it.id == id }
        if (childIndex >= 0) {
          val next = top.children.toMutableList()
          // 索引已确认该位置是 ToolCall（见 indexOfFirst 的条件）。
          next[childIndex] = transform(next[childIndex] as ToolCall)
          items[topIndex] = top.copy(children = next)
          notifyItemChanged(topIndex)
          return true
        }
      }
      else -> {}
    }
    return false
  }

  override fun getItemViewType(position: Int): Int =
      when (items[position]) {
        is Message -> TYPE_MESSAGE
        is ToolCall -> TYPE_TOOL_CALL
        is Thinking -> TYPE_THINKING
        // 组内只有一个子项时不渲染组头：一行「本轮改动 1 个文件」配上唯一那张卡片
        // 是纯冗余。与 ToolGroup 的单子项退化同一取舍。
        is DiffGroup ->
            if ((items[position] as DiffGroup).children.size <= 1) TYPE_DIFF else TYPE_DIFF_GROUP
        // 组内只有一个子项时不渲染组头：一张卡片配一个「1 个文件」的标题
        // 比直接显示那张卡片更啰嗦。组在只剩一项时会退回单卡片（见 shrinkGroups）。
        is ToolGroup ->
            if ((items[position] as ToolGroup).children.size <= 1) TYPE_TOOL_CALL
            else TYPE_TOOL_GROUP
        // Diff 只作为 DiffGroup 的子项存在（见 DiffGroup 的说明）；顶层不产出它。
        // 分支保留是为了 when 穷尽，真出现时按单张卡片渲染而不是崩溃。
        is Diff -> TYPE_DIFF
      }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val inflater = LayoutInflater.from(parent.context)
    return when (viewType) {
      TYPE_TOOL_CALL -> ToolCallVH(ItemToolCallBinding.inflate(inflater, parent, false), this)
      TYPE_TOOL_GROUP -> ToolGroupVH(ItemToolGroupBinding.inflate(inflater, parent, false), this)
      TYPE_THINKING -> ThinkingVH(ItemAssistantThinkingBinding.inflate(inflater, parent, false), this)
      TYPE_DIFF -> DiffVH(ItemAssistantDiffBinding.inflate(inflater, parent, false), this)
      TYPE_DIFF_GROUP -> DiffGroupVH(ItemDiffGroupBinding.inflate(inflater, parent, false), this)
      else -> MessageVH(ItemAssistantMessageBinding.inflate(inflater, parent, false), this)
    }
  }


  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    when (val item = items[position]) {
      is Message -> (holder as MessageVH).bind(item, onRevert)
      is ToolCall -> (holder as ToolCallVH).bind(item)
      is Thinking -> (holder as ThinkingVH).bind(item)
      is Diff -> (holder as DiffVH).bind(item)
      is DiffGroup -> {
        if (item.children.size <= 1) {
          // 单子项的组按单张卡片渲染（见 getItemViewType）。
          item.children.firstOrNull()?.let { (holder as DiffVH).bind(it) }
        } else {
          (holder as DiffGroupVH).bind(item)
        }
      }
      is ToolGroup -> {
        if (item.children.size <= 1) {
          // 单子项的组按普通卡片渲染（见 getItemViewType）。
          item.toolCalls.firstOrNull()?.let { (holder as ToolCallVH).bind(it) }
        } else {
          (holder as ToolGroupVH).bind(item, isLive = isRunActive && position == items.size - 1)
        }
      }
    }
  }

  override fun getItemCount(): Int = items.size

  /** 文本消息。 */
  class MessageVH(
      private val binding: ItemAssistantMessageBinding,
      private val adapter: AssistantMessageAdapter,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(message: Message, onRevert: ((Long, String) -> Unit)?) {
      val context = binding.root.context
      val card = binding.messageCard

      val roleText = when (message.role) {
          Role.USER -> context.getString(R.string.ai_assistant_role_user)
          Role.ASSISTANT -> context.getString(R.string.ai_assistant_role_assistant)
          Role.TRACE -> context.getString(R.string.ai_assistant_role_trace)
      }

      if (message.role == Role.ASSISTANT && message.durationMs > 0L) {
          val durationText = AssistantMessageActions.formatDuration(message.durationMs)
          binding.messageRole.text = "$roleText · $durationText"
      } else {
          binding.messageRole.text = roleText
      }

      // 正文渲染。
      //
      // 只有助手回复需要拆段（代码块要独立成控件）。用户输入与过程日志保持纯文本：
      // 过程日志里大量出现路径与命令，渲染器会把下划线、星号当标记处理导致显示变形；
      // 用户输入同理，用户打的字应该原样显示。
      val content = binding.messageContent
      content.removeAllViews()
      if (message.role == Role.ASSISTANT) {
        renderAssistantContent(content, message.text)
      } else {
        val text = inflateText(content)
        text.text = message.text
        content.addView(text)
      }

      // 角色差异：对齐方向、气泡、宽度上限、配色。四件事一起设——它们共同表达
      // 「谁在说话」，拆到 XML 里做会变成三套布局，改一处必漏两处。
      //
      // 边界取自参考项目：用户的话是气泡（右对齐、有底色、限宽），
      // 助手的回答是文档（铺满、无底色、不限宽）。助手回复里的代码块与表格
      // 需要横向空间，塞进窄气泡会被压成竖长条。
      val parent = binding.root as ViewGroup
      val isUser = message.role == Role.USER
      // 对齐设在**容器**（messageCardHost，一个 FrameLayout）里，不是根 LinearLayout：
      // 根是横向的，而横向 LinearLayout 不响应子视图的横向 layout_gravity
      // （AOSP 只取 VERTICAL_GRAVITY_MASK）——直接给卡片设 END 会让用户气泡贴左边。
      // 容器占满剩余宽度（weight=1，见 XML），卡片在容器内自由对齐。
      val hostParams = binding.messageCardHost.layoutParams as LinearLayout.LayoutParams
      hostParams.width = 0
      hostParams.weight = 1f
      binding.messageCardHost.layoutParams = hostParams

      // 卡片在容器内的宽度与对齐：
      // - 助手：MATCH_PARENT 铺满（「文档」形态，代码块需要横向空间），靠左。
      // - 用户：WRAP_CONTENT 按内容收缩 + 靠右（「气泡」形态）。
      val params = card.layoutParams as android.widget.FrameLayout.LayoutParams
      params.gravity = if (isUser) Gravity.END else Gravity.START
      params.width =
          if (isUser) ViewGroup.LayoutParams.WRAP_CONTENT
          else ViewGroup.LayoutParams.MATCH_PARENT
      card.layoutParams = params

      // 用户气泡的宽度上限。纯 WRAP_CONTENT 遇到一条长消息会顶满整个面板宽度，
      // 与助手消息在视觉上分不开；限到 90% 才能看出「这是我说的话」。
      // 助手消息不限宽——代码块越宽越好读。
      //
      // 上限设在文本段上：LinearLayout 没有 maxWidth，而用户消息的内容只有一个
      // 文本段（用户输入不走拆段，见上面的渲染分支）。助手消息不限宽——代码块越宽越好读。
      // 卡片缩放参与这个估算：内边距随设置缩放，减去的 28dp（左右 padding 之和）
      // 必须按同一系数换算，否则放大卡片时气泡会顶穿 90% 上限。
      val messageCardScale = adapter.cardScale()
      val bubbleMaxWidth =
          maxOf(
              (parent.width * USER_BUBBLE_MAX_WIDTH_RATIO).toInt()
                  - dp(card, (28 * messageCardScale).toInt()),
              dp(card, 160))
      for (i in 0 until content.childCount) {
        (content.getChildAt(i) as? TextView)?.maxWidth =
            if (isUser) bubbleMaxWidth else Int.MAX_VALUE
      }

      // 助手消息的**下**内边距收窄。
      //
      // <p>XML 里的 10dp 对用户气泡是必要的（那是气泡自身的留白，气泡有底色，
      // 不留白会显得文字贴着边）。但助手消息没有气泡（见 XML 顶部注释），
      // 它下方的 10dp 不是「留白」而是纯空隙——与紧随其后的工具卡片自己的上边距
      // 叠加后，实测视觉间隔接近 40dp，用户反馈「消息和下一张工具卡离得太开」。
      // 上边距保持 10dp：那是与前一条内容的**分隔**，收掉会让两条消息粘在一起。
      val verticalBase = if (isUser) 10 else 4
      content.setPadding(
          content.paddingLeft,
          content.paddingTop,
          content.paddingRight,
          dp(content, verticalBase),
      )

      // 色值必须走 Material 库的 attr：本项目 app 模块的 R.attr 里没有这些主题属性。
      // 助手消息不给底色（透明 + 无描边），让 Markdown 直接落在面板底色上。
      val (container, onContainer) =
          when (message.role) {
            Role.USER ->
                com.google.android.material.R.attr.colorPrimaryContainer to
                    com.google.android.material.R.attr.colorOnPrimaryContainer
            Role.ASSISTANT -> null to com.google.android.material.R.attr.colorOnSurface
            // 过程信息用 surfaceVariant：本仓库主题里**没有定义** colorSurfaceContainer* 系列，
            // 写那些角色会取到 M3 库默认的紫调，与自定义色板不匹配。
            Role.TRACE ->
                com.google.android.material.R.attr.colorSurfaceVariant to
                    com.google.android.material.R.attr.colorOnSurfaceVariant
          }

      if (container == null) {
        card.setCardBackgroundColor(ColorStateList.valueOf(Color.TRANSPARENT))
        card.strokeWidth = 0
      } else {
        card.setCardBackgroundColor(
            ColorStateList.valueOf(MaterialColors.getColor(card, container))
        )
        card.strokeWidth = 0
      }
      val textColor = MaterialColors.getColor(card, onContainer)
      binding.messageRole.setTextColor(textColor)
      // 正文颜色只给文本段，不给代码块：代码块有自己的底色与 token 配色，
      // 统一染成气泡的前景色会把语法高亮整片覆盖掉。
      //
      // 用户自定义色**只作用于助手正文**（Role.ASSISTANT）：用户气泡与过程信息
      // 保持主题色。理由是可读性——用户可能调出一个在 colorPrimaryContainer 上
      // 对比度不足的颜色，而助手正文铺在面板底色上，容错度高得多。
      val assistantCustom = adapter.customAssistantTextColor()
      val overrideColor =
          if (message.role == Role.ASSISTANT && assistantCustom != 0) assistantCustom else null
      for (i in 0 until content.childCount) {
        val child = content.getChildAt(i)
        if (child is TextView) {
          child.setTextColor(overrideColor ?: textColor)
        }
      }

      // 角色标签只在过程信息上显示，见布局注释。
      binding.messageRole.visibility = if (message.role == Role.TRACE) View.VISIBLE else View.GONE

      // ---- 外观：字号 / 卡片 / 头像 ----

      // 字号按用户设定缩放。只作用于文本段与角色标签；代码块有自己的等宽字号，
      // 缩放它会让代码行宽与容器不匹配（见 renderAssistantContent）。
      adapter.applyTextScale(binding.messageRole, AssistantUiStyleStore.BaseSp.LABEL_SMALL)
      for (i in 0 until content.childCount) {
        (content.getChildAt(i) as? TextView)?.let {
          adapter.applyTextScale(it, AssistantUiStyleStore.BaseSp.BODY_MEDIUM)
        }
      }

      // 消息卡片的内边距与圆角随「卡片大小」设置缩放，与工具卡/思考卡口径一致。
      // XML 里是 14/10dp、圆角 18dp（见 item_assistant_message.xml），这里按同一系数重设。
      val density = card.resources.displayMetrics.density
      fun dpF(value: Float): Int = (value * density).toInt()
      (card.getChildAt(0) as? View)?.setPadding(
          dpF(14f * messageCardScale),
          dpF(10f * messageCardScale),
          dpF(14f * messageCardScale),
          dpF(10f * messageCardScale),
      )
      card.radius = dpF(18f * messageCardScale).toFloat()

      // 头像只给助手消息：用户知道自己说了什么，不需要头像确认；
      // 过程信息是系统输出，加头像会让人误以为「AI 在自言自语地报状态」。
      val avatar = binding.messageAvatar
      if (message.role == Role.ASSISTANT) {
        avatar.visibility = View.VISIBLE
        val builtin = adapter.builtinAvatarRes()
        val custom = adapter.avatarPath()
        // **统一走 Glide 加载**，不混用 setImageResource。
        //
        // 早先用「Glide.clear + setImageResource」加载内置图，实测头像是空白的：
        // clear() 会向 ImageView 投递一次「清除」请求，它与随后同步设置的
        // resource 竞争——ViewHolder 复用时（RecyclerView 频繁重绑）清除可能后到，
        // 把刚画上的图擦掉。统一交给 Glide 的请求队列，顺序由它保证。
        //
        // 自定义图片必须带 signature：头像固定写到同一路径
        // （filesDir/assistant_avatar.png），而 Glide 的 File 模型缓存键只取路径
        // （ObjectKey 只比 file.toString()，不含 lastModified）——不加签名时换图后
        // 仍命中旧缓存，用户会以为选图没生效。用文件修改时间做签名即可区分。
        val request =
            if (custom != null) {
              val avatarFile = java.io.File(custom)
              com.bumptech.glide.Glide.with(avatar)
                  .load(avatarFile)
                  .signature(com.bumptech.glide.signature.ObjectKey(avatarFile.lastModified()))
            } else {
              com.bumptech.glide.Glide.with(avatar).load(builtin)
            }
        request.circleCrop().into(avatar)
      } else {
        // 用户消息与过程信息不显示头像。clear 掉进行中的请求，避免 ViewHolder
        // 复用时上一条消息的头像残留一帧。
        com.bumptech.glide.Glide.with(avatar).clear(avatar)
        avatar.visibility = View.GONE
      }

      // 撤销按钮：只有携带 diffId 的消息才可能显示，且默认收起、长按才展开。
      // 已撤销时改为禁用并换文案——让按钮消失会让用户怀疑自己是否点到了，
      // 禁用态能明确传达「已经生效了」。
      // 长按交互与操作条：
      // 如果消息带有 diffId，优先通过原有逻辑处理或长按显示撤销；
      // 若没有 diffId 或有外部操作监听，则触发自定义操作菜单（复制、引用提问等）。
      val diffId = message.diffId
      card.isLongClickable = true
      card.setOnLongClickListener {
        if (diffId != null) {
          binding.messageRevert.visibility =
              if (binding.messageRevert.isVisible) View.GONE else View.VISIBLE
          true
        } else if (adapter.onMessageActionRequested != null) {
          adapter.onMessageActionRequested?.invoke(message, card)
          true
        } else {
          false
        }
      }

      if (diffId == null) {
        binding.messageRevert.visibility = View.GONE
      } else {
        binding.messageRevert.isEnabled = !message.reverted
        binding.messageRevert.setText(
            if (message.reverted) string.ai_assistant_revert_done
            else string.ai_assistant_revert
        )
        binding.messageRevert.setOnClickListener { onRevert?.invoke(message.id, diffId) }
      }
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    /** inflate 一个正文段。样式与 item_assistant_message.xml 里的正文完全一致。 */
    private fun inflateText(parent: ViewGroup): TextView =
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_assistant_text, parent, false) as TextView

    /**
     * 把助手回复按代码围栏拆段后渲染。
     *
     * <p>没有围栏（绝大多数短回复）时退化为「一个正文段」，不做额外工作——
     * 拆分本身是 O(n) 的字符串扫描，但每次流式刷新都跑一遍，能省则省。
     */
    private fun renderAssistantContent(content: LinearLayout, text: String) {
      if (!text.contains("```") && !text.contains("~~~")) {
        val view = inflateText(content)
        AssistantMarkdown.renderOrPlain(view, text)
        content.addView(view)
        return
      }

      for (segment in CodeFenceParser.split(text)) {
        when (segment) {
          is CodeFenceParser.Segment.Text -> {
            // 空白段（围栏之间的换行）不渲染，否则代码块之间会多出空行。
            if (segment.markdown.isBlank()) {
              continue
            }
            val view = inflateText(content)
            AssistantMarkdown.renderOrPlain(view, segment.markdown.trimEnd('\n'))
            content.addView(view)
          }
          is CodeFenceParser.Segment.Code -> {
            val card =
                LayoutInflater.from(content.context)
                    .inflate(R.layout.item_assistant_code, content, false)
            bindCodeBlock(card, segment.language, segment.code)
            content.addView(card)
          }
        }
      }
    }

    /**
     * 绑定一个代码块：语言标签、行数、高亮、复制、超长折叠。
     *
     * <p>折叠是**截断字符串**而不是给代码区设 maxHeight：后者会形成
     * 「RecyclerView → 横向滚动 → 纵向滚动」三层嵌套，触摸仲裁在实机上很容易出问题
     * （内层滚不动 / 外层被卡住）。截断只把前 N 行交给 TextView，不产生第三层滚动。
     */
    private fun bindCodeBlock(card: View, language: String, code: String) {
      val context = card.context
      val languageView = card.findViewById<TextView>(R.id.codeLanguage)
      val linesView = card.findViewById<TextView>(R.id.codeLines)
      val textView = card.findViewById<TextView>(R.id.codeText)
      val copyButton = card.findViewById<com.google.android.material.button.MaterialButton>(R.id.codeCopy)
      val toggle = card.findViewById<com.google.android.material.button.MaterialButton>(R.id.codeToggle)

      val allLines = code.split('\n')
      val collapsed = allLines.size > CODE_COLLAPSE_THRESHOLD

      // 语言标签：模型没标注时显示「纯文本」而不是留空——空着的信息栏看起来像渲染出错。
      val displayLanguage =
          if (language.isBlank()) context.getString(string.ai_assistant_code_plaintext)
          else language
      languageView.text = displayLanguage
      linesView.text = context.getString(string.ai_assistant_code_lines, allLines.size)

      // 折叠态：按「可读行数」留一行省略提示，让用户知道下面还有内容。
      val visibleCode =
          if (collapsed) allLines.take(CODE_COLLAPSE_THRESHOLD).joinToString("\n") else code
      AssistantCodeHighlighter.highlightInto(textView, language, visibleCode)

      // 代码必须能横向滚动，否则长行会被强制换行、缩进层级全丢。
      textView.setHorizontallyScrolling(true)

      copyButton.setOnClickListener {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(displayLanguage, code))
        copyButton.setText(string.ai_assistant_code_copied)
        // 1.5s 后复原，与参考项目一致：短到不干扰阅读，长到能看清。
        copyButton.postDelayed(
            { copyButton.setText(string.ai_assistant_code_copy) },
            CODE_COPIED_RESET_MS,
        )
      }

      if (collapsed) {
        toggle.visibility = View.VISIBLE
        toggle.setText(context.getString(string.ai_assistant_code_expand, allLines.size))
        toggle.setOnClickListener {
          // 展开后隐藏按钮而不是换成「收起」：代码块在消息流里，收起与否不影响
          // 上下文，留一个收起按钮只会多占一行。要看全文才展开，展开后就是终态。
          AssistantCodeHighlighter.highlightInto(textView, language, code)
          toggle.visibility = View.GONE
        }
      } else {
        toggle.visibility = View.GONE
      }
    }
  }

  /** 工具调用卡片：折叠显示摘要，展开显示完整输入输出。 */
  class ToolCallVH(private val binding: ItemToolCallBinding, private val adapter: AssistantMessageAdapter) :
      RecyclerView.ViewHolder(binding.root) {

    fun bind(call: ToolCall) {
      val context = binding.root.context
      binding.toolName.text = call.toolName
      binding.toolSummary.text = call.summary

      binding.toolStatus.setText(
          when (call.status) {
            ToolStatus.RUNNING -> string.ai_assistant_tool_running
            ToolStatus.DONE -> string.ai_assistant_tool_done
            ToolStatus.FAILED -> string.ai_assistant_tool_failed_short
          }
      )
      // 失败用错误色，其余用次级色：用户需要一眼在长列表里找到出错的那一步。
      //
      // 用**应用命名空间**的 R.attr.colorError，不用 android.R.attr.colorError：
      // 后者解析到框架兜底的 #FF5722，而 ACS 主题定义的是 #ffb3261e——用户换主题时
      // 失败文本会是唯一不变色的元素。应用侧 attr 存在（R.txt:383），主题也定义了它，
      // 回退到次级色只是防御主题被改坏。
      val normalColor =
          MaterialColors.getColor(
              binding.root,
              com.google.android.material.R.attr.colorOnSurfaceVariant,
          )
      val errorColor = MaterialColors.getColor(binding.root, R.attr.colorError, 0)
      binding.toolStatus.setTextColor(
          if (call.status == ToolStatus.FAILED && errorColor != 0) errorColor else normalColor
      )

      // 按工具类型着色。一个长任务会产生几十张卡片，全是同一个灰盒子时用户无法快速
      // 扫出「哪几步改了文件、哪几步只是读」。分类色提供了一条视觉索引。
      val category =
          adapter.categoryResolver?.categoryOf(call.toolName)
              ?: ToolDisplayCategory.fallbackDisplayCategory(call.toolName)
      val accent = ToolCategoryStyle.accentColor(category)
      binding.toolIcon.setImageResource(ToolCategoryStyle.iconRes(category))
      binding.toolIcon.setColorFilter(accent)
      // 工具名也用分类色：只染图标的话，图标只有 16dp，在长列表里几乎看不出差别。
      binding.toolName.setTextColor(accent)
      // 描边压到半透明：不透明描边在深色主题下过于抢眼，反而盖过正文。
      binding.toolCard.strokeColor =
          androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x66)

      binding.toolDetails.visibility = if (call.expanded) View.VISIBLE else View.GONE
      binding.toolChevron.text =
          context.getString(
              if (call.expanded) string.ai_assistant_tool_collapse
              else string.ai_assistant_tool_expand
          )

      // 折叠态进度预览（参考 cc-haha 的 AgentProgressLine）：有步骤时显示
      // 「N 步 · 最后一步」——子 agent 折叠后与普通工具的区别必须可见，
      // 否则用户不知道它跑了多少步、最后在做什么。
      if (call.steps.isNotEmpty()) {
        binding.toolStepsHint.visibility = View.VISIBLE
        binding.toolStepsHint.text =
            context.getString(string.ai_assistant_tool_step_count, call.steps.size) +
                " · " +
                call.steps.last()
      } else {
        binding.toolStepsHint.visibility = View.GONE
      }

      if (call.expanded) {
        // 步骤区：只有累积过步骤的调用才显示（子 agent 这类长任务）。
        // 普通工具没有步骤，整块隐藏——空标题只是噪音。
        val hasSteps = call.steps.isNotEmpty()
        binding.toolStepsSection.visibility = if (hasSteps) View.VISIBLE else View.GONE
        if (hasSteps) {
          binding.toolSteps.text = call.steps.joinToString("\n")
        }

        binding.toolInput.text = call.input.ifBlank { context.getString(string.ai_assistant_tool_empty) }
        binding.toolOutput.text =
            when {
              call.output.isNotBlank() -> call.output
              call.status == ToolStatus.RUNNING -> context.getString(string.ai_assistant_tool_running)
              else -> context.getString(string.ai_assistant_tool_empty)
            }
      }

      // ---- 外观：字号 / 卡片大小 ----
      // 工具卡片是「可扫描的过程索引」：用户靠它快速定位「哪一步读了文件、哪一步改了代码」。
      // 各控件按各自的基准字号缩放，保持原有层级（工具名 > 摘要 > 输入输出）。
      adapter.applyTextScale(binding.toolName, AssistantUiStyleStore.BaseSp.LABEL_MEDIUM)
      adapter.applyTextScale(binding.toolStatus, AssistantUiStyleStore.BaseSp.LABEL_SMALL)
      adapter.applyTextScale(binding.toolChevron, AssistantUiStyleStore.BaseSp.LABEL_SMALL)
      adapter.applyTextScale(binding.toolSummary, AssistantUiStyleStore.BaseSp.BODY_SMALL)
      adapter.applyTextScale(binding.toolStepsHint, AssistantUiStyleStore.BaseSp.LABEL_SMALL)
      adapter.applyTextScale(binding.toolSteps, AssistantUiStyleStore.BaseSp.BODY_SMALL)
      adapter.applyTextScale(binding.toolInput, AssistantUiStyleStore.BaseSp.BODY_SMALL)
      adapter.applyTextScale(binding.toolOutput, AssistantUiStyleStore.BaseSp.BODY_SMALL)
      applyCardScale(binding.toolCard, adapter.cardScale(), cornerBaseDp = 10f)

      bindImage(call)

      binding.toolCard.setOnClickListener { adapter.toggleExpanded(call.id) }
    }

    /**
     * 绑定工具结果图片。
     *
     * <p><b>为什么解码放后台</b>：几 MB 的 base64 解码是 CPU 密集操作，在主线程做就是 ANR。
     * 而 `bind()` 本身在滚动中会被高频调用，更不能让它承担解码。
     *
     * <p><b>为什么必须清 Glide</b>：ViewHolder 会被复用，上一张卡片若不清，
     * 它的截图会残留在这一张上——本文件早先就因同类竞态踩过坑（见头像处的注释）。
     * 无图分支必须显式 `clear`。
     *
     * <p><b>为什么校验失败只给提示不显示破图</b>：base64 来自工具甚至第三方 MCP server，
     * 畸形数据是常态。显示一个破图占位比不显示更糟——用户会以为界面坏了。
     */
    private fun bindImage(call: ToolCall) {
      val base64 = call.imageBase64
      val card = binding.toolImageCard
      val image = binding.toolImage
      val notice = binding.toolImageNotice

      if (base64.isNullOrEmpty()) {
        card.visibility = View.GONE
        notice.visibility = View.GONE
        // 复用清理：不 clear 的话上一张卡片的截图会留在这里。
        com.bumptech.glide.Glide.with(image).clear(image)
        image.setImageDrawable(null)
        return
      }

      val context = binding.root.context
      val key = ToolResultImageSupport.cacheKey(base64)
      val viewId = call.id
      // 没有作用域时退化为「直接解码」：适配器可能被单测或调试代码直接驱动，
      // 那种场景下没有生命周期，而宁可在主线程解一次也不要什么都不显示。
      val scope = adapter.imageScope

      val work: suspend () -> Unit = {
        when (val decoded = ToolResultImageSupport.decode(call.imageMimeType, base64)) {
          is ToolResultImageSupport.Decoded.Rejected -> {
            withContext(Dispatchers.Main) {
              // 再次确认这张卡片还在显示同一个 item：后台解码期间列表可能已滚动，
              // ViewHolder 被复用到别的工具调用上，此时写进去就是串图。
              if (bindingAdapterPositionMatches(viewId)) {
                card.visibility = View.GONE
                com.bumptech.glide.Glide.with(image).clear(image)
                notice.visibility = View.VISIBLE
                notice.text =
                    context.getString(
                        if (decoded.reason == ToolResultImageSupport.Reason.TOO_LARGE)
                            string.ai_assistant_image_too_large
                        else string.ai_assistant_image_unavailable)
              }
            }
          }
          is ToolResultImageSupport.Decoded.Ok -> {
            withContext(Dispatchers.Main) {
              if (bindingAdapterPositionMatches(viewId)) {
                notice.visibility = View.GONE
                card.visibility = View.VISIBLE
                // signature 用内容摘要：load(byte[]) 的默认缓存键按数组**引用**比较，
                // 每次新建的数组都是新键，缓存永不命中、滚动回看反复重解码。
                com.bumptech.glide.Glide.with(image)
                    .load(decoded.bytes)
                    .signature(com.bumptech.glide.signature.ObjectKey(key))
                    .into(image)
                card.setOnClickListener {
                  ToolResultImageSupport.showLightbox(context, decoded.bytes, key)
                }
              }
            }
          }
        }
      }

      if (scope == null) {
        // 无作用域：同步解一次。失败也不抛——工具图片显示不出来不该影响对话。
        runCatching {
          val decoded = ToolResultImageSupport.decode(call.imageMimeType, base64)
          if (decoded is ToolResultImageSupport.Decoded.Ok) {
            card.visibility = View.VISIBLE
            com.bumptech.glide.Glide.with(image)
                .load(decoded.bytes)
                .signature(com.bumptech.glide.signature.ObjectKey(key))
                .into(image)
            card.setOnClickListener {
              ToolResultImageSupport.showLightbox(context, decoded.bytes, key)
            }
          }
        }
      } else {
        scope.launch(Dispatchers.Default) { work() }
      }
    }

    /**
     * 当前 ViewHolder 是否仍绑定着 [expectedId]。
     *
     * <p>后台任务回到主线程时必须复查：解码期间用户可能已经滚动，
     * 这个 ViewHolder 被复用到别的工具调用上。不复查就会把 A 的截图画到 B 的卡片里。
     */
    private fun bindingAdapterPositionMatches(expectedId: Long): Boolean {
      val position = bindingAdapterPosition
      if (position == RecyclerView.NO_POSITION) {
        return false
      }
      // **必须穿透分组**：工具可能已被并入 ToolGroup，那时顶层位置上是 ToolGroup 而不是
      // ToolCall。早先直接 `as? ToolCall` 的写法在分组后会恒为 false，
      // 后果是**被分组的工具图片永远不显示**——静默、不报错，很难归因。
      return when (val top = adapter.snapshot().getOrNull(position)) {
        is ToolCall -> top.id == expectedId
        is ToolGroup -> top.children.any { it.id == expectedId }
        else -> false
      }
    }

    /**
     * 按缩放系数重设卡片的**内边距与圆角**。
     *
     * <p><b>padding 必须设在内层容器上，不是卡片上</b>：XML 里的 padding 写在卡片内的
     * 第一层 LinearLayout 上（见 item_tool_call.xml:31-34），而 CardView 的
     * `setContentPadding` 会与之**叠加**（CardView 把它并入自身 padding 再传给子视图）。
     * 早先直接对卡片设 contentPadding，导致默认值下内边距翻倍（12+12=24dp）——
     * 不装任何设置就能看出卡片变胖。
     *
     * <p>padding 用**基准值 × 系数**而不是直接乘当前 padding：ViewHolder 会被复用，
     * 累乘会让卡片在滚动中越变越大。基准值与 XML 里的初始值一致（12/8dp）。
     *
     * @param cornerBaseDp 圆角基准值——**每张卡片不同**，必须由调用方传入（工具卡 10dp、
     *     思考卡 12dp，见各自 XML 的 cardCornerRadius）。写死一个值会让另一类卡片的
     *     默认圆角被悄悄改掉。
     */
    private fun applyCardScale(
        card: com.google.android.material.card.MaterialCardView,
        scale: Float,
        cornerBaseDp: Float,
    ) {
      val density = card.resources.displayMetrics.density
      fun dp(value: Float): Int = (value * density).toInt()
      // 内层容器承载 padding（见 KDoc）。取不到时跳过而不是崩溃——布局被改坏时
      // 卡片仍应能显示，只是内边距不随设置缩放。
      (card.getChildAt(0) as? View)?.setPadding(
          dp(12f * scale),
          dp(8f * scale),
          dp(12f * scale),
          dp(8f * scale),
      )
      // radius 是 Float（MaterialCardView 的半径以 px 为单位但类型是 Float），
      // dp() 返回 Int —— 必须显式转换，否则 Assignment type mismatch。
      card.radius = dp(cornerBaseDp * scale).toFloat()
    }
  }

  /**
   * 组内子卡片的绑定。
   *
   * <p><b>为什么不复制一份绑定逻辑</b>：子卡片用的就是 `item_tool_call.xml`，
   * 展示规则（状态色、展开、图片、步骤）必须与顶层卡片**逐字一致**。
   * 复制一份意味着以后改一处漏一处，两处慢慢分叉。
   * 因此这里直接复用 [ToolCallVH]——它是个普通类，可以脱离 RecyclerView 使用，
   * 只是不要调它的 `itemView` 相关能力。
   */
  object ToolCallRowBinder {
    fun bind(row: View, call: ToolCall, adapter: AssistantMessageAdapter) {
      val binding = ItemToolCallBinding.bind(row)
      ToolCallVH(binding, adapter).bind(call)
    }
  }

  /**
   * 组内推理块的绑定。
   *
   * <p>推理块被收进工具组后，在展开态需要与工具卡片并列显示。它用**独立布局**
   * （`item_assistant_thinking.xml`），因为展示规则完全不同：工具卡片是「名字+状态+输入输出」，
   * 推理块是「可折叠的长文本」。
   */
  object ThinkingRowBinder {
    fun bind(row: View, thinking: Thinking, adapter: AssistantMessageAdapter) {
      // 复用 ThinkingVH 的绑定逻辑，避免两处展示规则分叉（同 ToolCallRowBinder 的理由）。
      val binding = ItemAssistantThinkingBinding.bind(row)
      ThinkingVH(binding, adapter).bind(thinking)
    }
  }

  /**
   * 把一次工具调用转成摘要输入。
   *
   * <p>分类来自 [categoryResolver]（与工具卡片的配色同源），
   * 不在这里重新做「工具名 → 分类」的映射——那会与 `ToolRegistry` 的映射漂移。
   *
   * <p>定义在 companion 里而不是作为成员扩展函数：嵌套类 [ToolGroupVH] 看不到
   * 外部类的成员扩展（Kotlin 的成员扩展只对**本类实例内部**可见），
   * 放 companion 才能被它调用。
   */
  internal fun toolCallToSummaryEntry(call: ToolCall, resolver: CategoryResolver?): AssistantToolGroupSummary.Entry =
      AssistantToolGroupSummary.Entry(
          toolName = call.toolName,
          category = resolver?.categoryOf(call.toolName)?.name,
          target = call.summary,
          failed = call.status == ToolStatus.FAILED,
      )

  /** 切换工具组的展开状态；把用户意图写进 [ToolGroup.pinnedExpanded]。 */
  fun toggleGroupExpanded(id: Long, currentlyCollapsed: Boolean) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? ToolGroup ?: return
    // 记「展开与否」而不是「折叠与否」：pinnedExpanded 的语义是展开意图，
    // 直接存 `!currentlyCollapsed` 就是用户想要的最终状态。
    items[index] = old.copy(pinnedExpanded = currentlyCollapsed)
    notifyItemChanged(index)
  }

  /**
   * 工具组：折叠态一行活动摘要，展开态逐行列出组内卡片。
   *
   * <p>折叠策略：`pinnedExpanded`（用户手动）优先，否则「运行中且位于列表末尾」才展开。
   * 这条规则让长任务自动收敛——跑完一轮，十几张工具卡片收成一行，
   * 只有正在干活的那一组保持展开。
   */
  class ToolGroupVH(
      private val binding: ItemToolGroupBinding,
      private val adapter: AssistantMessageAdapter,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(group: ToolGroup, isLive: Boolean) {
      val context = binding.root.context
      val collapsed = group.pinnedExpanded?.let { !it } ?: !isLive

      val summary =
          AssistantToolGroupSummary.summarize(
              group.toolCalls.map { adapter.toolCallToSummaryEntry(it, adapter.categoryResolver) },
              // 思考块数一并计入摘要。它们被收在 children 里但不在 toolCalls 中
              // （见 ToolGroup.toolCalls 的定义），因此必须单独数。
              thinkingCount = group.children.count { it is Thinking },
          )
      binding.groupSummary.text =
          if (summary.isEmpty()) context.getString(string.ai_assistant_tool_group_fallback)
          else summary

      val failures = group.toolCalls.count { it.status == ToolStatus.FAILED }
      if (failures > 0) {
        binding.groupFailures.visibility = View.VISIBLE
        binding.groupFailures.text =
            context.getString(string.ai_assistant_tool_group_failures, failures)
        binding.groupFailures.setTextColor(
            MaterialColors.getColor(binding.root, R.attr.colorError))
      } else {
        binding.groupFailures.visibility = View.GONE
      }

      binding.groupChevron.text =
          context.getString(
              if (collapsed) string.ai_assistant_tool_expand
              else string.ai_assistant_tool_collapse)

      binding.groupChildren.visibility = if (collapsed) View.GONE else View.VISIBLE
      if (!collapsed) {
        // 逐行 inflate 组内卡片。这里不用嵌套 RecyclerView：
        // 组通常只有几项，而嵌套 RecyclerView 会带来滚动嵌套与高度测量问题
        // （item_assistant_code.xml 里记过同类陷阱）。
        binding.groupChildren.removeAllViews()
        for (child in group.children) {
          val layout =
              when (child) {
                is ToolCall -> R.layout.item_tool_call
                is Thinking -> R.layout.item_assistant_thinking
                else -> continue
              }
          val row = LayoutInflater.from(context).inflate(layout, binding.groupChildren, false)
          binding.groupChildren.addView(row)
          when (child) {
            is ToolCall -> ToolCallRowBinder.bind(row, child, adapter)
            is Thinking -> ThinkingRowBinder.bind(row, child, adapter)
            else -> {}
          }
        }
      }

      // 整行可点：只让箭头可点会让折叠区很难命中（触控目标太小）。
      binding.groupHeader.setOnClickListener {
        adapter.toggleGroupExpanded(group.id, collapsed)
      }

      adapter.applyTextScale(binding.groupSummary, AssistantUiStyleStore.BaseSp.BODY_SMALL)
      adapter.applyTextScale(binding.groupChevron, AssistantUiStyleStore.BaseSp.LABEL_SMALL)
      adapter.applyTextScale(binding.groupFailures, AssistantUiStyleStore.BaseSp.LABEL_SMALL)
      val density = binding.root.resources.displayMetrics.density
      binding.groupCard.radius = (10f * adapter.cardScale() * density)
    }
  }

  /**
   * 思维链折叠块。
   *
   * <p>推理文本量大且通常只在等待期间有参考价值，因此默认折叠、限高 220dp。
   * 标题随 [Thinking.streaming] 切换文案——用户在等待时需要确认模型确实在工作，
   * 一个始终不变的标题会让人怀疑是不是卡住了。
   */
  class ThinkingVH(
      private val binding: ItemAssistantThinkingBinding,
      private val adapter: AssistantMessageAdapter,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(thinking: Thinking) {
      val context = binding.root.context
      binding.thinkingLabel.setText(
          if (thinking.streaming) string.ai_assistant_thinking_running
          else string.ai_assistant_thinking_done
      )
      binding.thinkingProgress.visibility =
          if (thinking.streaming) View.VISIBLE else View.GONE

      // 折叠态不写文本：推理可能几千字，即使不可见也会参与测量。
      binding.thinkingText.text = if (thinking.expanded) thinking.text else ""
      binding.thinkingScroll.visibility = if (thinking.expanded) View.VISIBLE else View.GONE
      binding.thinkingDivider.visibility = if (thinking.expanded) View.VISIBLE else View.GONE
      binding.thinkingChevron.text =
          context.getString(
              if (thinking.expanded) string.ai_assistant_tool_collapse
              else string.ai_assistant_tool_expand
          )

      // ---- 外观：字号 / 卡片大小 ----
      adapter.applyTextScale(binding.thinkingLabel, AssistantUiStyleStore.BaseSp.LABEL_MEDIUM)
      adapter.applyTextScale(binding.thinkingChevron, AssistantUiStyleStore.BaseSp.LABEL_SMALL)
      adapter.applyTextScale(binding.thinkingText, AssistantUiStyleStore.BaseSp.BODY_SMALL)
      applyCardScale(binding.thinkingCard, adapter.cardScale())

      // 整行可点：只有箭头可点会让折叠区很难命中。
      binding.thinkingHeader.setOnClickListener { adapter.toggleThinkingExpanded(thinking.id) }
    }

    /**
     * 按缩放系数重设思考卡片的**内边距与圆角**。
     *
     * <p><b>padding 分两处，都要设</b>：思考卡的内层是「标题行 + 分隔线 + 内容滚动区」，
     * 标题行与内容区各自带 padding（见 item_assistant_thinking.xml:42-45、102-105），
     * 外层容器没有 padding。因此这里遍历内层容器，对其中的每个子 View 设 padding——
     * 只设第一个会把内容区的内边距漏掉（展开态文字贴边）。
     *
     * <p>基准值与 XML 初始值一致：标题行 12/10、内容区 12/8，圆角 12dp。
     * 用基准值乘系数而不是乘当前值：ViewHolder 复用下累乘会越滚越大。
     */
    private fun applyCardScale(card: com.google.android.material.card.MaterialCardView, scale: Float) {
      val density = card.resources.displayMetrics.density
      fun dp(value: Float): Int = (value * density).toInt()
      val container = card.getChildAt(0) as? android.view.ViewGroup
      if (container != null) {
        // 索引 0 = 标题行（12/10dp），索引 1 = 分隔线（无 padding），索引 2 = 内容区（12/8dp）。
        // 按索引取而不是遍历全部：分隔线是 1dp 的 View，给它设 padding 会让线变粗。
        (container.getChildAt(0) as? View)?.setPadding(
            dp(12f * scale), dp(10f * scale), dp(12f * scale), dp(10f * scale))
        (container.getChildAt(2) as? View)?.setPadding(
            dp(12f * scale), dp(8f * scale), dp(12f * scale), dp(10f * scale))
      }
      card.radius = dp(12f * scale).toFloat()
    }
  }

  /**
   * 一张文件改动 diff 卡片的绑定逻辑。
   *
   * <p><b>为什么独立成一个类</b>（而不是只放在 [DiffVH] 里）：改动汇总卡片 [DiffGroupVH]
   * 要把**同一张卡片**作为组内行复用，两者必须共用同一份渲染，否则「展开单文件」
   * 「复制」「撤销」这些行为会在两处慢慢分叉。
   *
   * <p>整块 diff 拼进**一个** TextView，靠 Spannable 表达行号、增删标记与词级高亮。
   * 逐行建控件在千行级 diff 上会产生大量 View 与测量开销，而这些东西本来都是
   * 文字属性（底色、前景色），不需要真实控件。
   *
   * <p>配色不新增主题 attr：增删底色由 `colorSuccess`/`colorError` 加透明度派生，
   * 前景色沿用 `colorOnSurface`。新增 attr 要改 4 个主题文件 × 2 种模式，
   * 而这两个语义色主题里已经有了，且天然跟随深浅色切换。
   */
  class DiffRowBinder(
      private val binding: ItemAssistantDiffBinding,
      private val adapter: AssistantMessageAdapter,
  ) {

    fun bind(item: Diff) {
      val context = binding.root.context
      val result = item.result

      // 文件名：只取末段路径。完整路径在小屏上会被省略成「/storage/emulated…」，
      // 用户反而看不出改的是哪个文件；完整路径仍可从展开后的内容或工具卡片看到。
      val name = item.filePath.substringAfterLast('/').ifEmpty { item.filePath }
      binding.diffFileName.text = name

      if (result == null) {
        // 「还在算」与「记录已过期」都表现为 result==null，但含义与应对完全不同：
        // 前者等一下就有，后者永远不会来了（FileDiffStore 会裁剪旧记录，
        // 默认每文件 20 条 / 总量 8MB）。文案混用会让用户对真正过期的记录干等。
        binding.diffStat.text =
            context.getString(
                if (item.computing) string.ai_assistant_diff_computing
                else string.ai_assistant_diff_expired)
        binding.diffText.text = ""
        binding.diffToggle.visibility = View.GONE
      } else {
        bindStat(result)
        bindBody(item.id, result, item.expanded)
      }

      bindCopy(item, result)
      bindRevert(item)
      applyCardScale(binding.diffCard)
    }

    /** 增删统计：`+n` 用成功色、`-n` 用错误色，两个数字分开染色。 */
    private fun bindStat(result: DiffResult) {
      val context = binding.root.context
      val added = "+${result.added}"
      val removed = "-${result.removed}"
      val text =
          if (result.truncated) {
            // 降级路径：对齐是「全删 + 全增」，逐行渲染对用户已无意义，
            // 但数字仍准确，因此保留统计并额外说明一句。
            context.getString(string.ai_assistant_diff_truncated, added, removed)
          } else {
            "$added $removed"
          }
      val span = SpannableString(text)
      val success = MaterialColors.getColor(binding.root, R.attr.colorSuccess)
      val error = MaterialColors.getColor(binding.root, R.attr.colorError)
      val addStart = text.indexOf(added)
      if (addStart >= 0) {
        span.setSpan(
            ForegroundColorSpan(success), addStart, addStart + added.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      val delStart = text.indexOf(removed)
      if (delStart >= 0) {
        span.setSpan(
            ForegroundColorSpan(error), delStart, delStart + removed.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      binding.diffStat.text = span
    }

    /**
     * 渲染 diff 正文。
     *
     * <p>折叠沿用代码块的**截断字符串**策略（只把前 N 行交给 TextView），
     * 而不是给控件设 maxHeight + 内部纵向滚动：后者会形成
     * 「RecyclerView → 横向滚动 → 纵向滚动」三层嵌套，触摸仲裁在实机上很容易出问题。
     */
    private fun bindBody(itemId: Long, result: DiffResult, expanded: Boolean) {
      val context = binding.root.context
      val total = result.lines.size
      val collapsed = !expanded && total > DIFF_COLLAPSE_THRESHOLD
      val limit = if (collapsed) DIFF_COLLAPSE_THRESHOLD else total

      val lines = AssistantDiffRenderer.render(result, limit)
      val span = SpannableString(lines.joinToString("\n") { it.text })

      // 行底色：整行铺一层低透明度色。透明度而不是实色，是因为 diff 里
      // 大片实色会盖住代码本身的语法感，而且深色主题下实色底 + 深色字会糊成一片。
      val base = MaterialColors.getColor(binding.root, R.attr.colorOnSurface)
      val success = MaterialColors.getColor(binding.root, R.attr.colorSuccess)
      val error = MaterialColors.getColor(binding.root, R.attr.colorError)
      val addedBg = ColorUtils.setAlphaComponent(success, DIFF_LINE_BG_ALPHA)
      val removedBg = ColorUtils.setAlphaComponent(error, DIFF_LINE_BG_ALPHA)
      // 词级高亮再叠一层更重的底色，使「这一行改了哪几个词」在行底色之上仍可分辨。
      val addedTokenBg = ColorUtils.setAlphaComponent(success, DIFF_TOKEN_BG_ALPHA)
      val removedTokenBg = ColorUtils.setAlphaComponent(error, DIFF_TOKEN_BG_ALPHA)

      var offset = 0
      for (line in lines) {
        val end = offset + line.text.length
        val lineBg =
            when (line.type) {
              DiffLineType.INSERT -> addedBg
              DiffLineType.DELETE -> removedBg
              DiffLineType.EQUAL -> Color.TRANSPARENT
            }
        if (lineBg != Color.TRANSPARENT) {
          span.setSpan(
              BackgroundColorSpan(lineBg), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          // 增删行的正文用主题前景色；不加这一层的话，深色主题里
          // 低透明度底色上的默认文字色对比度不足。
          span.setSpan(
              ForegroundColorSpan(base), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val tokenBg = if (line.type == DiffLineType.INSERT) addedTokenBg else removedTokenBg
        for (range in line.changedRanges) {
          val s = offset + range.first
          val e = offset + range.last + 1
          if (s in offset..end && e <= end && s < e) {
            span.setSpan(
                BackgroundColorSpan(tokenBg), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          }
        }
        offset = end + 1 // +1 是 joinToString 插入的换行
      }

      binding.diffText.text = span
      binding.diffText.setHorizontallyScrolling(true)

      if (collapsed) {
        binding.diffToggle.visibility = View.VISIBLE
        binding.diffToggle.text =
            context.getString(string.ai_assistant_code_expand, total)
        binding.diffToggle.setOnClickListener { adapter.toggleDiffExpanded(itemId) }
      } else {
        // 展开后隐藏按钮而不是换成「收起」：与代码块同一取舍——
        // 卡片在消息流里，收起与否不影响上下文，留一个收起按钮只会多占一行。
        binding.diffToggle.visibility = View.GONE
      }
    }

    /** 复制：走通用 diff 记法（`+`/`-` 前缀），而不是屏幕上的行号排版。 */
    private fun bindCopy(item: Diff, result: DiffResult?) {
      val context = binding.root.context
      binding.diffCopy.isEnabled = result != null
      binding.diffCopy.setOnClickListener {
        if (result == null) {
          return@setOnClickListener
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(item.filePath, AssistantDiffRenderer.toPlainText(result)))
        binding.diffCopy.setText(string.ai_assistant_code_copied)
        binding.diffCopy.postDelayed(
            { binding.diffCopy.setText(string.ai_assistant_code_copy) },
            CODE_COPIED_RESET_MS)
      }
    }

    /** 撤销：与消息卡片共用同一个回调，语义一致（回滚一次文件改动）。 */
    private fun bindRevert(item: Diff) {
      val diffId = item.diffId
      if (diffId.isNullOrEmpty()) {
        binding.diffRevert.visibility = View.GONE
        return
      }
      binding.diffRevert.visibility = View.VISIBLE
      binding.diffRevert.isEnabled = !item.reverted
      binding.diffRevert.setText(
          if (item.reverted) string.ai_assistant_revert_done else string.ai_assistant_revert)
      binding.diffRevert.setOnClickListener { adapter.onRevert?.invoke(item.id, diffId) }
    }

    private fun applyCardScale(card: com.google.android.material.card.MaterialCardView) {
      val density = card.resources.displayMetrics.density
      val scale = adapter.cardScale()
      fun dp(value: Float): Int = (value * density).toInt()
      card.radius = dp(12f * scale).toFloat()
    }
  }

  /** 单张 diff 卡片（顶层条目）。渲染逻辑全在 [DiffRowBinder]。 */
  class DiffVH(
      private val binding: ItemAssistantDiffBinding,
      private val adapter: AssistantMessageAdapter,
  ) : RecyclerView.ViewHolder(binding.root) {

    private val binder = DiffRowBinder(binding, adapter)

    fun bind(item: Diff) = binder.bind(item)
  }

  /**
   * 本轮改动汇总卡片：折叠态一行「本轮改动 N 个文件 · +X -Y ▾」，
   * 展开后逐文件列出（每个文件是一张完整的 diff 卡片）。
   *
   * <p>为什么组内行直接 inflate 而不是嵌一层 RecyclerView：与 [ToolGroupVH] 同一取舍
   * ——组通常只有几个文件，嵌套 RecyclerView 会带来滚动嵌套与高度测量问题。
   */
  class DiffGroupVH(
      private val binding: ItemDiffGroupBinding,
      private val adapter: AssistantMessageAdapter,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(group: DiffGroup) {
      val context = binding.root.context
      val collapsed = group.pinnedExpanded?.let { !it } ?: true

      binding.diffGroupTitle.text =
          context.getString(string.ai_assistant_diff_group_title, group.fileCount)

      // 汇总增删：结果还没算出来时（刚插入那一刻）总和是 0，
      // 显示「+0 -0」比隐藏更稳——隐藏会让这一行在结果回填时跳一下宽度。
      val added = "+${group.totalAdded}"
      val removed = "-${group.totalRemoved}"
      val text = "$added $removed"
      val span = SpannableString(text)
      val success = MaterialColors.getColor(binding.root, R.attr.colorSuccess)
      val error = MaterialColors.getColor(binding.root, R.attr.colorError)
      val addStart = text.indexOf(added)
      if (addStart >= 0) {
        span.setSpan(
            ForegroundColorSpan(success), addStart, addStart + added.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      val delStart = text.indexOf(removed)
      if (delStart >= 0) {
        span.setSpan(
            ForegroundColorSpan(error), delStart, delStart + removed.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      binding.diffGroupStat.text = span

      binding.diffGroupChevron.text =
          context.getString(
              if (collapsed) string.ai_assistant_tool_expand
              else string.ai_assistant_tool_collapse)

      binding.diffGroupChildren.visibility = if (collapsed) View.GONE else View.VISIBLE
      if (!collapsed) {
        binding.diffGroupChildren.removeAllViews()
        for (child in group.children) {
          val row =
              LayoutInflater.from(context)
                  .inflate(R.layout.item_assistant_diff, binding.diffGroupChildren, false)
          binding.diffGroupChildren.addView(row)
          // 组内行的 id 仍指向子项本身：展开单文件、复制、撤销都按这个 id 回写，
          // 因此它们必须能穿透组找到（见 replaceDiff）。
          DiffRowBinder(ItemAssistantDiffBinding.bind(row), adapter).bind(child)
        }
      }

      binding.diffGroupHeader.setOnClickListener {
        adapter.toggleDiffGroupExpanded(group.id, collapsed)
      }

      val density = binding.root.resources.displayMetrics.density
      binding.diffGroupCard.radius = (10f * adapter.cardScale() * density)
    }
  }

  companion object {
    private const val TYPE_MESSAGE = 0
    private const val TYPE_TOOL_CALL = 1
    private const val TYPE_THINKING = 2
    private const val TYPE_DIFF = 3
    private const val TYPE_TOOL_GROUP = 4
    private const val TYPE_DIFF_GROUP = 5

    /**
     * diff 折叠阈值（行）。
     *
     * <p>比代码块的 20 行更小（取 14）：diff 一行只承载一条信息，
     * 而改动往往集中在少数几行，前 14 行通常已能看清改了什么。
     */
    private const val DIFF_COLLAPSE_THRESHOLD = 14

    /** 增删行的整行底色透明度（0-255）。低到能透出主题底色，又能看出行的归属。 */
    private const val DIFF_LINE_BG_ALPHA = 28

    /** 词级高亮底色透明度。比整行底色重，才能在行底色之上分辨出改动的词。 */
    private const val DIFF_TOKEN_BG_ALPHA = 72

    /**
     * 用户气泡的宽度上限（占面板可用宽度的比例）。
     *
     * 取自参考项目的 0.90。不限宽的话长消息会顶满面板，与助手消息在视觉上分不开；
     * 限太窄（如 0.75）则中文长句会频繁换行，读起来费劲。
     */
    private const val USER_BUBBLE_MAX_WIDTH_RATIO = 0.90f

    /**
     * 代码块折叠阈值（行）。
     *
     * <p>取 20 与参考项目一致。低于这个数不折叠——折叠本身要占一行按钮，
     * 一个 8 行的代码块折叠后只省下几行，反而多了一次点击。
     */
    private const val CODE_COLLAPSE_THRESHOLD = 20

    /** 复制按钮反馈的持续时间。1.5s：短到不干扰阅读，长到能看清。 */
    private const val CODE_COPIED_RESET_MS = 1500L
  }
}
