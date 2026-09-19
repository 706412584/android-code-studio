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

  /** 动作对象名的最大长度；超出截断。 */
  const val TARGET_MAX = 28

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
   * <p>命令只取第一个 token：`cd foo && ./gradlew build` 里用户关心的是「在跑 gradlew」，
   * 整条命令会占满整行并被省略成看不懂的样子。
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
          raw.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        } else {
          raw.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() }.takeLast(2)
              .joinToString("/")
        }
    if (compact.isEmpty()) {
      return "…"
    }
    return if (compact.length <= TARGET_MAX) compact
    else compact.substring(0, TARGET_MAX) + "…"
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
