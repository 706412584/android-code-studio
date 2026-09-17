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

package com.tom.rv2ide.ai.agent;

import com.tom.rv2ide.ai.tool.api.ToolInfo;
import java.util.List;
import org.json.JSONException;

/**
 * 组装 agent 的系统提示词。
 *
 * <p>提示词承担两件事：
 * <ol>
 *   <li>说明可用工具及其调用格式——这是<b>不支持原生工具调用的模型</b>唯一的工具信息来源。
 *       支持原生调用的模型也能从 tools 字段拿到定义，两处一致不冲突。
 *   <li>给出工作区约定（路径规则、只读/写入范围），减少模型臆造路径。
 * </ol>
 *
 * <p><b>与上游的差异</b>：LineCode Pro 从 assets 目录加载 {@code prompts/*.txt} 模板并支持
 * 用户在设置里覆盖。本移植先采用内置模板，去掉模板加载与覆盖机制——那需要 Android 的
 * assets 访问。若后续需要可定制，把模板文本作为构造参数传入即可。
 */
public final class AgentPromptBuilder {

  private final String identity;

  public AgentPromptBuilder() {
    this("ACS AI Agent");
  }

  public AgentPromptBuilder(String identity) {
    this.identity = identity == null || identity.isEmpty() ? "ACS AI Agent" : identity;
  }

  /**
   * 生成系统提示词。
   *
   * @param homePath 工作区根目录，模型据此构造相对路径
   * @param tools 可用工具列表
   */
  public String build(String homePath, List<ToolInfo> tools) {
    return build(homePath, tools, false);
  }

  /**
   * 生成系统提示词。
   *
   * <p><b>为什么需要 {@code nativeTools} 开关</b>：实测发现，对支持原生工具调用的端点
   * 也注入 XML 兜底格式说明，会显著诱导模型放弃原生 tool_calls 去写 XML——而免费/小型
   * 模型写出的 XML 常常是畸形的（如 {@code </parameter name="content">}、标签不闭合），
   * 结果原生与文本两条路径都解析不出调用，表现为「模型什么都没做」。
   * 同一提示词去掉该段落后，原生 tool_calls 成功率从 1/6 升到 8/8。
   *
   * <p>因此 XML 说明只在模型确实拿不到 tools 字段时才给出。
   *
   * @param homePath 工作区根目录
   * @param tools 可用工具列表
   * @param nativeTools 协议是否支持原生工具调用；true 时不注入 XML 兜底格式说明
   */
  public String build(String homePath, List<ToolInfo> tools, boolean nativeTools) {
    return build(homePath, tools, nativeTools, null);
  }

  /**
   * 生成系统提示词（含待办状态）。
   *
   * <p><b>为什么待办要进提示词</b>：{@code todo_update} 把计划外化成列表，但模型只有在
   * 提示词里看到它，才能在几十轮调用之后仍知道「我做到哪一步、还剩什么」。
   * 只存不读等于没记。
   *
   * @param todoState 已渲染的待办文本；null 或空表示无待办，此时不注入该段落
   */
  public String build(
      String homePath, List<ToolInfo> tools, boolean nativeTools, String todoState) {
    StringBuilder sb = new StringBuilder();

    sb.append("你是 ").append(identity).append("，一个 Android 项目的编码助手。\n\n");

    sb.append("[ 工作方式 ]\n");
    sb.append("你可以调用工具来读写文件、执行命令。请通过调用工具完成任务，");
    sb.append("不要只在回复里描述你打算怎么做。\n");
    sb.append("一次可以请求多个工具调用；系统会执行它们并把结果回传给你，");
    sb.append("然后你可以继续下一步，直到任务完成。\n\n");

    sb.append("[ 工作区 ]\n");
    if (homePath != null && !homePath.isEmpty()) {
      sb.append("根目录: ").append(homePath).append('\n');
      sb.append("工具路径参数请使用相对于根目录的路径（如 src/main/App.kt）。\n");
      sb.append("工具只允许访问根目录内的文件；越界路径会被拒绝。\n");
    }
    sb.append('\n');

    // 待办段落刻意放在工具列表之前：模型先看到「当前进度」，再看到可用手段。
    // 无待办时不输出空标题，避免提示词里出现一段没有内容的段落。
    if (todoState != null && !todoState.trim().isEmpty()) {
      sb.append("[ 当前待办 ]\n");
      sb.append(todoState.trim()).append('\n');
      sb.append("继续推进未完成的项；每完成一项就用 todo_update 更新状态。\n\n");
    }

    sb.append("[ 可用工具 ]\n");
    if (tools == null || tools.isEmpty()) {
      sb.append("(当前没有可用工具)\n");
    } else {
      for (ToolInfo tool : tools) {
        sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append('\n');
        String supplement = safePromptSupplement(tool);
        if (supplement != null && !supplement.isEmpty()) {
          sb.append("  ").append(supplement.replace("\n", "\n  ")).append('\n');
        }
      }
      sb.append('\n');
      // 仅对拿不到 tools 字段的模型给出文本调用格式；对原生支持的端点注入这段会
      // 诱导模型改用 XML，反而降低成功率（见 build 方法的说明）。
      if (!nativeTools) {
        sb.append("[ 工具调用格式 ]\n");
        sb.append("用下面的 XML 形式表达工具调用（可以一次多个）：\n");
        sb.append("<tool_calls>\n");
        sb.append("<tool_call name=\"file_read\">\n");
        sb.append("<argument name=\"file_path\">app/build.gradle.kts</argument>\n");
        sb.append("</tool_call>\n");
        sb.append("</tool_calls>\n");
        sb.append("工具调用之外可以写文字说明；两者可以同时出现在一次回复里。\n\n");
      }
    }

    sb.append("[ 注意 ]\n");
    sb.append("- 修改文件前先读取其当前内容，不要凭猜测覆盖。\n");
    sb.append("- 工具返回错误时，阅读错误信息并调整做法，不要重复同样的调用。\n");
    sb.append("- 任务完成后用简洁的文字说明你做了什么。\n");

    return sb.toString();
  }

  private static String safePromptSupplement(ToolInfo tool) {
    try {
      return tool.promptSupplement("");
    } catch (RuntimeException e) {
      return null;
    }
  }
}
