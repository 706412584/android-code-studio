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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 提示词模板可用的占位符清单。
 *
 * <p><b>为什么要有这份清单</b>：模板是用户可编辑的文本，写错占位符（拼错、用了不存在的
 * 名字）不会报错——渲染时未识别的占位符会原样留在提示词里，模型看到的是
 * {@code {{TOOL_LIST}}} 这样的字面量，表现为「行为莫名其妙」。设置界面据此给出可用列表
 * 与校验。
 *
 * <p><b>渲染语义</b>：未提供的占位符会被替换为**空串**（见
 * {@code PromptRenderer}），而不是原样保留。这与 {@code StringTemplate} 的默认行为不同，
 * 后者保留原文。理由：用户删掉一段可选内容时，希望那一段整体消失，而不是留下一串
 * 看不懂的标记去干扰模型。
 */
public final class PromptPlaceholders {

  // ---- 身份与模型 ----
  public static final String MODEL_IDENTITY = "MODEL_IDENTITY";
  public static final String MODEL_PROVIDER = "MODEL_PROVIDER";
  public static final String MODEL_NAME = "MODEL_NAME";
  public static final String MODEL_PROTOCOL = "MODEL_PROTOCOL";

  // ---- 环境 ----
  public static final String HOME_PATH = "HOME_PATH";
  public static final String WORKSPACE_CONTEXT = "WORKSPACE_CONTEXT";
  public static final String CHAT_MODE = "CHAT_MODE";
  public static final String CHAT_MODE_CONTEXT = "CHAT_MODE_CONTEXT";

  // ---- 能力 ----
  public static final String TOOLS_CONTEXT = "TOOLS_CONTEXT";
  /** 工具清单正文（每行一个工具的 name: description）。 */
  public static final String TOOL_LIST = "TOOL_LIST";
  public static final String TOOL_CALL_FORMAT = "TOOL_CALL_FORMAT";

  /** 收尾注意事项段落。 */
  public static final String NOTES = "NOTES";

  // ---- 任务状态 ----
  /**
   * 待办清单**正文**（每行一项，如 {@code [x] 读取配置}）。
   *
   * <p>注意与 {@link #TODO_SECTION} 区分：前者是纯清单，后者是「标题 + 清单 + 提示」的
   * 完整段落。两者同名会导致段落模板里的 {@code {{TODO_LIST}}} 解析到段落自身，
   * 结果清单永远注入不进去。
   */
  public static final String TODO_LIST = "TODO_LIST";

  /** 待办完整段落（含标题与「完成后更新」的提示）。 */
  public static final String TODO_SECTION = "TODO_SECTION";

  public static final String TASK_DESCRIPTION = "TASK_DESCRIPTION";

  // ---- 历史 ----
  public static final String HISTORY_SECTION = "HISTORY_SECTION";
  public static final String SUMMARY = "SUMMARY";

  // ---- 语气与角色 ----
  public static final String ROLE_PROMPT = "ROLE_PROMPT";
  public static final String TONE_CONTEXT = "TONE_CONTEXT";

  /** 全部占位符，按逻辑分组顺序。 */
  private static final List<String> ALL =
      Collections.unmodifiableList(
          Arrays.asList(
              MODEL_IDENTITY,
              MODEL_PROVIDER,
              MODEL_NAME,
              MODEL_PROTOCOL,
              HOME_PATH,
              WORKSPACE_CONTEXT,
              CHAT_MODE,
              CHAT_MODE_CONTEXT,
              TOOLS_CONTEXT,
              TOOL_LIST,
              TOOL_CALL_FORMAT,
              TODO_LIST,
              TODO_SECTION,
              TASK_DESCRIPTION,
              HISTORY_SECTION,
              SUMMARY,
              ROLE_PROMPT,
              TONE_CONTEXT,
              NOTES));

  private PromptPlaceholders() {}

  /** 全部可用占位符名（不含花括号）。 */
  public static List<String> all() {
    return ALL;
  }

  /** 判断某个名字是否是已知占位符。 */
  public static boolean isKnown(String name) {
    return name != null && ALL.contains(name);
  }

  /** 供设置界面展示的说明：占位符名 → 含义。 */
  public static String describe(String name) {
    if (name == null) {
      return "";
    }
    switch (name) {
      case MODEL_IDENTITY:
        return "助手身份（例如「ACS AI Agent」）";
      case MODEL_PROVIDER:
        return "当前服务商标识";
      case MODEL_NAME:
        return "当前模型名";
      case MODEL_PROTOCOL:
        return "当前协议类型";
      case HOME_PATH:
        return "工作区根目录的绝对路径";
      case WORKSPACE_CONTEXT:
        return "工作区说明段落（含路径使用约定）";
      case CHAT_MODE:
        return "当前模式标识（chat/plan/agent/control）";
      case CHAT_MODE_CONTEXT:
        return "当前模式的行为约束段落";
      case TOOLS_CONTEXT:
        return "可用工具段落（含标题与清单）";
      case TOOL_LIST:
        return "工具清单正文（每行一个工具）";
      case TOOL_CALL_FORMAT:
        return "文本形态的工具调用格式说明（仅在不支持原生工具调用时非空）";
      case TODO_LIST:
        return "当前待办清单正文（每行一项）";
      case TODO_SECTION:
        return "待办完整段落（含标题与更新提示）";
      case TASK_DESCRIPTION:
        return "本次用户请求";
      case HISTORY_SECTION:
        return "历史对话摘要段落";
      case SUMMARY:
        return "历史压缩摘要";
      case ROLE_PROMPT:
        return "角色设定（自定义人设）";
      case TONE_CONTEXT:
        return "语气要求";
      case NOTES:
        return "收尾注意事项段落";
      default:
        return "";
    }
  }
}
