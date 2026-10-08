/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.artificial.agent

/**
 * 把「一组连续的工具调用」压成折叠态的一行活动摘要。
 *
 * <p>目标形态：`读取 5 个文件 · 执行 2 条命令 · 1 项失败`。
 *
 * <p><b>为什么单独抽出来</b>：这段逻辑（分类计数 + 去重 + 拼句）全是纯函数，
 * 却天然长在视图层——工具卡片是「一次调用一张卡」，连续 10 次 `file_read` 会堆 10 张卡
 * 把正文顶出屏幕，于是需要在视图里合并它们。而合并后的那一行是用户扫列表时唯一读的东西，
 * 数错了（把 3 次读算成 1 次）或顺序乱了都很难在真机上察觉。放在视图类里就一条测试都写不了
 * （同 [AssistantActionText] 的道理：需要 Context 与 ViewBinding 才能构造的类无法单测）。
 * 本类不依赖任何 `android.*`，可在 JVM 单测里直接跑。
 *
 * <p><b>为什么复用 [com.tom.rv2ide.ai.tool.api.ToolDisplayCategory] 而不是自造分类</b>：
 * 卡片上的分类色与图标（`ToolCategoryStyle`）已经由这个枚举决定。若这里另立一套分类，
 * 两处对「什么算搜索、什么算读取」的判断迟早会分叉，表现为「卡片是读的蓝色、
 * 摘要却说执行了命令」。因此本类只**接收已解析好的分类**，映射（工具名 → 分类）仍归
 * `ToolRegistry` / `CategoryResolver` 所有，不在这里重复实现。
 */
object AssistantToolGroupSummary {

  /**
   * 摘要各段之间的分隔符。
   *
   * <p>对外暴露是因为视图层还要在末尾追加耗时——它必须用同一个分隔符，
   * 否则会拼出「读取 5 个文件, 0.8s」这种两套标点混用的文案。
   */
  const val SEPARATOR = " · "

  // 分类名。用字符串常量而非枚举类型，是为了**向前兼容**：
  // Kotlin 对枚举的 `when` 要求穷尽，`ToolDisplayCategory` 一旦新增取值，
  // 本文件就会编译失败；而分类计数漏掉一个新分类不该阻断整个 app 的构建。
  // 未识别的分类统一并入 GENERIC，见 [canonicalCategory]。
  private const val CATEGORY_READ = "READ"
  private const val CATEGORY_WRITE = "WRITE"
  private const val CATEGORY_DELETE = "DELETE"
  private const val CATEGORY_SHELL = "SHELL"
  private const val CATEGORY_AGENT = "AGENT"
  private const val CATEGORY_AGENT_PIPELINE = "AGENT_PIPELINE"
  private const val CATEGORY_TODO = "TODO"
  private const val CATEGORY_IMAGE_GENERATION = "IMAGE_GENERATION"
  private const val CATEGORY_PHONE_CONTROL = "PHONE_CONTROL"
  private const val CATEGORY_GENERIC = "GENERIC"

  /** [ToolDisplayCategory] 当前的全部取值（外加兜底 GENERIC）。 */
  private val KNOWN_CATEGORIES =
      setOf(
          CATEGORY_READ,
          CATEGORY_WRITE,
          CATEGORY_DELETE,
          CATEGORY_SHELL,
          CATEGORY_AGENT,
          CATEGORY_AGENT_PIPELINE,
          CATEGORY_TODO,
          CATEGORY_IMAGE_GENERATION,
          CATEGORY_PHONE_CONTROL,
          CATEGORY_GENERIC,
      )

  /**
   * 单个工具调用的摘要输入。
   *
   * @param toolName 工具名。本类只用它做去重分组之外的调试用途，不参与分类判断
   *     （分类已由 [category] 给出），因此可以留空。
   * @param category 已解析好的展示分类，取 `ToolDisplayCategory.name()`；
   *     空值或未识别的值按 GENERIC 处理。
   * @param target 可选的目标（文件路径等）。只有 WRITE 用它去重。
   * @param failed 该次调用是否失败。
   * @param durationMs 耗时。本类不消费它（摘要不含耗时），保留字段是为了让调用方
   *     在构造 Entry 时一次填齐，不必为了拼耗时再造一个平行结构。
   */
  data class Entry(
      val toolName: String,
      // 声明为可空而非依赖 Kotlin 的非空断言：本类会被 Java 调用（适配器是 Java 友好层），
      // 而 Java 传 null 不会在编译期被拦下——若这里是非空，NPE 会在**构造 Entry 时**抛出，
      // 位置离「摘要文案」十万八千里，排查成本极高。可空 + 并入 GENERIC 才是真正的兜底。
      val category: String? = null,
      val target: String? = null,
      val failed: Boolean = false,
      val durationMs: Long = 0L,
  )

  /**
   * 生成摘要文案，例如 `读取 5 个文件 · 执行 2 条命令 · 1 项失败`。
   *
   * <p>各段的顺序是**分类首次出现的顺序**，不是枚举定义顺序。理由：这一行是下方卡片列表的
   * 浓缩，用户按从上到下的顺序读卡片，摘要也该按同一顺序讲。若按枚举序排，
   * 「先执行命令再读文件」会被讲成「先读文件再执行命令」，与实际发生的事相反。
   *
   * @return 空输入返回空串（而非 null 或异常）——由调用方决定是否隐藏这一行。
   */
  fun summarize(entries: List<Entry>): String {
    if (entries.isEmpty()) {
      return ""
    }

    // LinkedHashMap 的迭代顺序即插入顺序 = 分类首次出现顺序，
    // 省掉一个平行的 order 列表。
    val counts = LinkedHashMap<String, Int>()
    val writtenTargets = HashSet<String>()
    var failures = 0

    for (entry in entries) {
      val category = canonicalCategory(entry.category)
      val target = entry.target?.trim().orEmpty()

      // WRITE 按目标去重（照抄参考项目 cc-haha 的取舍：它只对 Edit 去重）：
      // 同一个文件改三次是一次逻辑改动，算「修改 3 个文件」会夸大工作量。
      // 目标缺失时无法证明是同一个文件，只能各算一次——否则三次无目标的写入
      // 会塌成「修改 1 个文件」，那是**少报**了真实改动，比多报更危险。
      val shouldCount =
          category != CATEGORY_WRITE || target.isEmpty() || writtenTargets.add(target)

      if (shouldCount) {
        counts[category] = (counts[category] ?: 0) + 1
      }

      if (entry.failed) {
        failures++
      }
    }

    // READ 刻意**不**去重：读同一个文件三次说明模型真的重复读了三次，
    // 这既是真实工作量，也往往是「它没找到想要的东西」的信号，值得如实呈现。
    val segments =
        counts.entries.map { (category, count) -> segmentOf(category, count) }.toMutableList()
    if (failures > 0) {
      // 失败数与分类正交（可能是读失败、也可能是命令失败），因此独立成段并置于末尾——
      // 用户扫这一行时最容易注意到句尾。
      segments.add(failureSegmentOf(failures))
    }
    return segments.joinToString(SEPARATOR)
  }

  /**
   * 归一化分类名：去空白、转大写，未识别的并入 GENERIC。
   *
   * <p>并入而非各自成段，是为了避免「两个未知分类各出一段 `调用 1 次工具`」这种
   * 看起来像 bug 的重复文案。
   */
  private fun canonicalCategory(raw: String?): String {
    val name = raw?.trim()?.uppercase().orEmpty()
    return if (name in KNOWN_CATEGORIES) name else CATEGORY_GENERIC
  }

  /**
   * 一个分类的文案段。计数单位随分类不同——「5 个文件」「2 条命令」「3 次搜索」
   * 各有各的量词，统一成「N 次」会让文案读起来像机翻。
   */
  private fun segmentOf(category: String, count: Int): String =
      when (category) {
        CATEGORY_READ -> "读取 $count 个文件"
        CATEGORY_WRITE -> "修改 $count 个文件"
        CATEGORY_DELETE -> "删除 $count 个文件"
        CATEGORY_SHELL -> "执行 $count 条命令"
        CATEGORY_AGENT -> "委派 $count 个子代理"
        CATEGORY_AGENT_PIPELINE -> "运行 $count 条代理流水线"
        CATEGORY_TODO -> "更新 $count 次待办"
        CATEGORY_IMAGE_GENERATION -> "生成 $count 张图片"
        CATEGORY_PHONE_CONTROL -> "操作手机 $count 次"
        // GENERIC 与所有未知分类：不把英文枚举名拼进中文句子里
        // （「调用 3 次 BROWSER」在中英混排时很像渲染错误），只报数量。
        else -> "调用 $count 次工具"
      }

  /** 失败段的文案。独立成段而非并入各分类，见 [summarize] 里的说明。 */
  private fun failureSegmentOf(count: Int): String = "$count 项失败"
}
