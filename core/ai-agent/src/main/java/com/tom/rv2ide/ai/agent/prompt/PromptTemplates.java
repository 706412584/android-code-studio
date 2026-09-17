/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
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

package com.tom.rv2ide.ai.agent.prompt;

/**
 * 默认提示词模板。
 *
 * <p>模板是**数据**而不是代码：把它放在这里、由 {@link PromptTemplateStore} 读取与覆盖，
 * 使用户改提示词不必重新编译。默认值即此前硬编码在 {@code AgentPromptBuilder} 里的文本，
 * 行为不变。
 *
 * <p>模板 ID 是稳定契约（偏好里存的就是它），改名会让用户的既有自定义失效。
 */
public final class PromptTemplates {

  /** 系统提示词主模板。 */
  public static final String SYSTEM_PROMPT = "systemPrompt";

  /** 工作区说明段落。 */
  public static final String WORKSPACE_CONTEXT = "workspaceContext";

  /** 工具清单段落。 */
  public static final String TOOLS_CONTEXT = "toolsContext";

  /** 文本形态的工具调用格式说明（仅在不支持原生工具调用时注入）。 */
  public static final String TOOL_CALL_FORMAT = "toolCallFormat";

  /** 待办段落。 */
  public static final String TODO_SECTION = "todoSection";

  /** 各模式的行为约束段落。ID 形如 {@code chatModeAgent}。 */
  public static final String CHAT_MODE_PREFIX = "chatMode";

  /** 收尾注意事项段落。 */
  public static final String NOTES = "notes";

  private PromptTemplates() {}

  /**
   * 默认系统提示词。
   *
   * <p>段落之间刻意保留空行：{@link PromptRenderer#tidy} 只在渲染后整理多余空行，
   * 而模板本身的可读性对用户编辑很重要——一行到底的模板没人愿意改。
   */
  public static String defaultSystemPrompt() {
    return "你是 {{MODEL_IDENTITY}}，一个 Android 项目的编码助手。\n"
        + "\n"
        + "{{ROLE_PROMPT}}\n"
        + "{{TONE_CONTEXT}}\n"
        + "{{CHAT_MODE_CONTEXT}}\n"
        + "{{WORKSPACE_CONTEXT}}\n"
        + "{{TODO_SECTION}}\n"
        + "{{TOOLS_CONTEXT}}\n"
        + "{{TOOL_CALL_FORMAT}}\n"
        + "{{NOTES}}";
  }

  /** 默认工作区说明。 */
  public static String defaultWorkspaceContext() {
    return "[ 工作区 ]\n"
        + "根目录: {{HOME_PATH}}\n"
        + "工具路径参数请使用相对于根目录的路径（如 src/main/App.kt）。\n"
        + "工具只允许访问根目录内的文件；越界路径会被拒绝。";
  }

  /** 默认工具清单段落。 */
  public static String defaultToolsContext() {
    return "[ 可用工具 ]\n"
        + "{{TOOL_LIST}}";
  }

  /** 默认文本工具调用格式说明。 */
  public static String defaultToolCallFormat() {
    return "[ 工具调用格式 ]\n"
        + "用下面的 XML 形式表达工具调用（可以一次多个）：\n"
        + "<tool_calls>\n"
        + "<tool_call name=\"file_read\">\n"
        + "<argument name=\"file_path\">app/build.gradle.kts</argument>\n"
        + "</tool_call>\n"
        + "</tool_calls>\n"
        + "工具调用之外可以写文字说明；两者可以同时出现在一次回复里。";
  }

  /** 默认待办段落。 */
  public static String defaultTodoSection() {
    return "[ 当前待办 ]\n"
        + "{{TODO_LIST}}\n"
        + "继续推进未完成的项；每完成一项就用 todo_update 更新状态。";
  }

  /** 默认收尾注意事项。 */
  public static String defaultNotes() {
    return "[ 注意 ]\n"
        + "- 修改文件前先读取其当前内容，不要凭猜测覆盖。\n"
        + "- 工具返回错误时，阅读错误信息并调整做法，不要重复同样的调用。\n"
        + "- 任务完成后用简洁的文字说明你做了什么。";
  }

  /**
   * 各模式的默认行为约束。
   *
   * <p>CHAT 模式下的措辞尤其关键：模型很容易「顺手」去调用工具，因此必须明确说
   * 「不要调用任何工具」，否则纯对话模式形同虚设。
   */
  public static String defaultChatModeContext(ChatMode mode) {
    switch (mode) {
      case CHAT:
        return "[ 模式：对话 ]\n"
            + "本次只做交流：回答问题、解释概念、讨论方案。\n"
            + "不要调用任何工具，也不要声称自己修改了文件。\n"
            + "如果用户的要求需要改动代码，说明应该怎么做，并提示他切到执行模式。";
      case PLAN:
        return "[ 模式：计划 ]\n"
            + "本次只做调查与规划：可以读取文件、搜索代码、查阅文档。\n"
            + "不要修改任何文件，也不要执行有副作用的命令。\n"
            + "最后给出一份可执行的计划：分步骤、每步说明改哪个文件、为什么这样改。";
      case AGENT:
        return "[ 模式：执行 ]\n"
            + "请通过调用工具完成任务，不要只在回复里描述你打算怎么做。\n"
            + "一次可以请求多个工具调用；系统会执行它们并把结果回传给你，"
            + "然后你可以继续下一步，直到任务完成。";
      case CONTROL:
      default:
        return "[ 模式：受控执行 ]\n"
            + "通过调用工具完成任务。\n"
            + "涉及删除文件、安装应用、启动应用、执行无法撤销的命令时，"
            + "先说明你要做什么以及为什么，等待用户确认后再继续。";
    }
  }

  /** 按 ID 取默认模板；未知 ID 返回空串。 */
  public static String defaultFor(String templateId) {
    if (templateId == null) {
      return "";
    }
    switch (templateId) {
      case SYSTEM_PROMPT:
        return defaultSystemPrompt();
      case WORKSPACE_CONTEXT:
        return defaultWorkspaceContext();
      case TOOLS_CONTEXT:
        return defaultToolsContext();
      case TOOL_CALL_FORMAT:
        return defaultToolCallFormat();
      case TODO_SECTION:
        return defaultTodoSection();
      case NOTES:
        return defaultNotes();
      default:
        if (templateId.startsWith(CHAT_MODE_PREFIX)) {
          String modeId = templateId.substring(CHAT_MODE_PREFIX.length());
          for (ChatMode mode : ChatMode.values()) {
            if (mode.getId().equalsIgnoreCase(modeId)) {
              return defaultChatModeContext(mode);
            }
          }
        }
        return "";
    }
  }

  /** 模式对应的模板 ID，例如 {@code chatModeAgent}。 */
  public static String chatModeTemplateId(ChatMode mode) {
    ChatMode resolved = mode == null ? ChatMode.DEFAULT : mode;
    String id = resolved.getId();
    return CHAT_MODE_PREFIX + Character.toUpperCase(id.charAt(0)) + id.substring(1);
  }
}
