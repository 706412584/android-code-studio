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

import android.content.Context
import com.tom.rv2ide.ai.tool.ToolRegistry
import com.tom.rv2ide.ai.tool.api.ToolNames
import com.tom.rv2ide.resources.R.string
import org.json.JSONObject

/**
 * 把「一次工具调用」翻译成底部状态条上的一行实时播报。
 *
 * <p><b>为什么单独抽出来</b>：这段逻辑（工具名分派 + 参数提取 + 路径压缩）全是纯函数，
 * 却原先长在 [FloatingAssistantView] 里——那是个需要 Android Context 与 ViewBinding
 * 才能构造的类，于是这段逻辑一条测试都写不了。而它恰好是用户每天看到最多的一行字，
 * 拼错了（例如把整条 shell 命令塞进去）界面会很难看，值得钉住。
 *
 * <p>本类仍需要 Context（取字符串资源），但方法都是纯的：给定输入产出固定输出，
 * 不碰任何 View。
 */
object AssistantActionText {

  /**
   * 动作对象名的兜底截断长度。
   *
   * <p>取得比较宽（120）是因为命令要显示完整内容——用户明确要求看到完整命令。
   * 真正决定显示多少的是绘制层的可用宽度（WorkingStatusView 在末尾省略），
   * 这个常量只防「几 KB 的命令塞进 TextView」这种极端情况。
   */
  const val TARGET_MAX = 120

  /**
   * 工具调用 → 状态条文案。
   *
   * @param toolName 模型给出的工具名（可能带别名，内部会规范化）
   * @param arguments 原始参数 JSON，可为 null
   */
  fun describe(context: Context, toolName: String, arguments: String?): CharSequence {
    val canonical = ToolRegistry.canonicalName(toolName)
    val target = targetOf(canonical, arguments.orEmpty())
    return when (canonical) {
      ToolNames.FILE_READ -> context.getString(string.ai_assistant_work_reading, target)
      ToolNames.FILE_EDIT -> context.getString(string.ai_assistant_work_editing, target)
      ToolNames.FILE_WRITE -> context.getString(string.ai_assistant_work_writing, target)
      ToolNames.FILE_DELETE -> context.getString(string.ai_assistant_work_editing, target)
      ToolNames.LIST_DIR -> context.getString(string.ai_assistant_work_listing, target)
      ToolNames.GLOB -> context.getString(string.ai_assistant_work_searching, target)
      ToolNames.SHELL_EXECUTE -> context.getString(string.ai_assistant_work_running, target)
      ToolNames.TODO_UPDATE -> context.getString(string.ai_assistant_work_todo)
      // 写入 skill 是「AI 记下一条经验」，与读 skill（走兜底显示工具名）不同——
      // 这是用户会关心的一类动作，值得一个正经文案。
      ToolNames.SKILL_WRITE -> context.getString(string.ai_assistant_work_skill)
      ToolNames.AGENT -> context.getString(string.ai_assistant_work_agent, target)
      ToolNames.WEB_FETCH, ToolNames.WEB_SEARCH ->
          context.getString(string.ai_assistant_work_web, target)
      // 这三个是 app 层的工具，名字常量在各自类里（不在 ToolNames 中）。
      "gradle_build" -> context.getString(string.ai_assistant_work_building)
      "install_apk" -> context.getString(string.ai_assistant_work_installing)
      "launch_app" -> context.getString(string.ai_assistant_work_launching)
      else -> context.getString(string.ai_assistant_work_tool, canonical)
    }
  }

  /**
   * 从工具参数里取出「给用户看的对象名」。
   *
   * <p>路径压到末两段：完整绝对路径在状态条里放不下，而用户靠文件名就能认出是哪个文件
   * （`cpp/Renderer.cpp` 比 `/data/data/com.tom.rv2ide/files/home/.../Renderer.cpp` 有效得多）。
   *
   * <p><b>命令保留完整内容</b>（不取首个 token）：用户明确要求「显示完整命令动作」。
   * `./gradlew clean --offline` 与 `./gradlew assembleDebug` 对用户是两件事，
   * 只显示 `./gradlew` 等于什么都没说。超出宽度由绘制层在**末尾**省略——
   * 命令的开头（可执行文件与子命令）正是最能区分它的一条信息，保留开头比保留中间有用。
   * 这里只做一层「极长时截断」的兜底，避免把几 KB 的命令塞进 TextView。
   *
   * <p>取不到任何对象时返回 `…` 而不是空串——空串会拼出「正在读取 」这种断句。
   */
  fun targetOf(toolName: String, arguments: String): String {
    val raw =
        when (toolName) {
          ToolNames.SHELL_EXECUTE -> opt(arguments, "command")
          ToolNames.GLOB -> opt(arguments, "pattern")
          ToolNames.AGENT -> opt(arguments, "task")
          ToolNames.WEB_SEARCH -> opt(arguments, "query")
          ToolNames.WEB_FETCH -> opt(arguments, "url")
          else -> opt(arguments, "file_path").ifEmpty { opt(arguments, "path") }
        }
    if (raw.isBlank()) {
      return "…"
    }
    val compact =
        if (toolName == ToolNames.SHELL_EXECUTE) {
          // 折掉换行与连续空白：命令常写成多行（`cd x &&\n ./gradlew`），
          // 直接放进单行状态条会把整行撑高。
          raw.trim().replace(Regex("\\s+"), " ")
        } else {
          raw.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() }.takeLast(2)
              .joinToString("/")
        }
    if (compact.isEmpty()) {
      return "…"
    }
    return truncateAtCodePointBoundary(compact, TARGET_MAX)
  }

  /**
   * 按**码点**边界截断，绝不切在代理对中间。
   *
   * <p>`substring` 按 UTF-16 单元计数，若截断点正好落在代理对中间，结果末尾会是一个
   * 孤立的高位代理（lone surrogate）。它既渲染成 `?`，也会让 `Canvas.drawText` 抛出
   * 或画出乱码——而且只在特定长度的中文/emoji 文件名上偶发，极难定位。
   *
   * <p>emoji 与 CJK 扩展 B 区汉字（如 𠀋）都是代理对，中文项目里完全可能出现。
   *
   * @param max 最大 UTF-16 单元数；实际结果可能少 1 个单元以避开半个代理对
   */
  private fun truncateAtCodePointBoundary(text: String, max: Int): String {
    if (text.length <= max) {
      return text
    }
    var end = max
    // 截断点落在「低半区」说明前一个单元是高位代理，把它一起退掉。
    if (Character.isLowSurrogate(text[end])) {
      end--
    }
    return text.substring(0, end) + "…"
  }

  /** 安全读一个字符串字段；JSON 非法或字段缺失都返回空串。 */
  private fun opt(arguments: String, key: String): String {
    if (arguments.isBlank()) {
      return ""
    }
    return try {
      JSONObject(arguments).optString(key, "")
    } catch (e: Exception) {
      // 参数可能不是合法 JSON（模型偶尔会漏引号）。这不是错误，只是取不到对象名。
      ""
    }
  }

  // ---- 重试卡片 ----

  /**
   * 重试卡片从第几次重试开始显示。
   *
   * <p>前几次不打扰用户：偶发的一两次重连是常态（切网、DNS 抖动），
   * 每次都弹一个红底卡片会让人以为出了大问题。与参考项目（cc-haha）
   * 的 `retryAttempt < 4 → return null` 同一个阈值。
   */
  const val RETRY_VISIBLE_FROM = 4

  /** 重试卡片上错误码的最大长度。 */
  const val SHORT_ERROR_MAX = 40

  /** 是否该显示重试卡片。 */
  fun shouldShowRetry(attempt: Int): Boolean = attempt >= RETRY_VISIBLE_FROM

  /**
   * 从错误描述里抽出「短错误码」。
   *
   * <p>模型的异常消息往往是一大段 JSON（`HTTP 503: {"error":{"message":"..."}}`），
   * 直接贴进卡片会把两行占满且全是噪音。用户真正需要的是状态码或错误类型，
   * 细节留给日志。抽不到就返回空串，卡片只显示重试信息。
   */
  fun shortErrorCode(reason: String): String {
    if (reason.isBlank()) {
      return ""
    }
    // 状态码优先：最短且信息量最大。
    Regex("HTTP\\s+(\\d{3})").find(reason)?.let {
      return "HTTP " + it.groupValues[1]
    }
    // 其次 SSE 流内错误的 type（rate_limit_error 这类比数字更能说明该怎么办）。
    Regex("\"type\"\\s*:\\s*\"([a-z_]+)\"").find(reason)?.let {
      return it.groupValues[1]
    }
    // 兜底：取首行并截断。异常消息的第一行通常就是「是什么错了」。
    val firstLine = reason.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (firstLine.length <= SHORT_ERROR_MAX) firstLine
    else firstLine.substring(0, SHORT_ERROR_MAX) + "…"
  }
}
