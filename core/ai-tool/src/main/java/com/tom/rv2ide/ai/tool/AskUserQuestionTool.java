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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 向用户提选择题。
 *
 * <p><b>为什么需要它</b>：此前模型遇到歧义只有两条路——猜，或把问题写在回复里等用户
 * 下一轮再说。前者会做错（而且用户要等到结果出来才发现），后者白白浪费一整轮往返。
 * 有了本工具，模型可以在**执行过程中**停下来问清楚再继续。
 *
 * <p><b>为什么是选择题而不是自由问答</b>：对齐参考项目 cc-haha 的 AskUserQuestion。
 * 移动端的输入成本高，让用户打字描述意图远比点一下选项费劲；而模型提问时通常心里
 * 已有几个候选方案，把它们列出来让用户选，既省力又比自由文本更明确。
 *
 * <p><b>与权限确认的区别</b>：{@code confirmDangerousTool} 问的是「准不准」，
 * 本工具问的是「要哪个」——前者是否决权，后者是决策权。两者共用阻塞式询问机制，
 * 但语义不同，因此不合并。
 */
public final class AskUserQuestionTool extends BaseTool {

  /** 一次最多问几道题。超过会让弹窗变成表单，用户容易看漏。 */
  static final int MAX_QUESTIONS = 4;

  /** 每道题的选项数上限。 */
  static final int MAX_OPTIONS = 4;

  /** 每道题的选项数下限——只有一个选项不构成「选择」。 */
  static final int MIN_OPTIONS = 2;

  /** 选项标签长度上限，保证弹窗上不折行。 */
  static final int MAX_LABEL_CHARS = 60;

  @Override
  public String getName() {
    return ToolNames.ASK_USER_QUESTION;
  }

  @Override
  public String getDescription() {
    return "在执行过程中向用户提选择题，用于澄清歧义、确认偏好、或在多个方案间做决定。"
        + "适合「用户才知道答案」的事（用哪个库、按哪种交互、先做哪块），"
        + "不适合能自己查证的事（那就去读代码或搜索）。"
        + "用户可以选择「其它」自行输入；建议某项时把它排在第一个并在标签末尾标注（推荐）。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    // 提问不改动任何东西，只读模式也该允许——恰恰是只读调查阶段最需要澄清需求。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 会弹窗等待用户，并发调用会让多个弹窗互相抢占；串行更可预期。
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject option =
        new JSONObject()
            .put("type", "object")
            .put(
                "properties",
                new JSONObject()
                    .put(
                        "label",
                        new JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "用户看到的选项文本，简短（1-5 个词）且明确描述这个选择。"))
                    .put(
                        "description",
                        new JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "该选项的含义或后果，用于说明取舍。"))
            )
            .put("required", new JSONArray().put("label"));

    JSONObject question =
        new JSONObject()
            .put("type", "object")
            .put(
                "properties",
                new JSONObject()
                    .put(
                        "question",
                        new JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "完整的问题，以问号结尾。多选时措辞相应调整"
                                    + "（如「要启用哪些功能？」）。"))
                    .put(
                        "header",
                        new JSONObject()
                            .put("type", "string")
                            .put("description", "极短标签（不超过 12 字），如「认证方式」「实现方案」。"))
                    .put(
                        "options",
                        new JSONObject()
                            .put("type", "array")
                            .put("items", option)
                            .put(
                                "description",
                                "可选项，"
                                    + MIN_OPTIONS
                                    + "-"
                                    + MAX_OPTIONS
                                    + " 个。各项应互斥（多选时除外）。"
                                    + "**不要**自己加「其它」选项，界面会自动提供。"))
                    .put(
                        "multiSelect",
                        new JSONObject()
                            .put("type", "boolean")
                            .put("description", "是否允许多选。默认 false。选项不互斥时设为 true。")))
            .put("required", new JSONArray().put("question").put("options"));

    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "questions",
                    new JSONObject()
                        .put("type", "array")
                        .put("items", question)
                        .put(
                            "description",
                            "要问的问题，1-"
                                + MAX_QUESTIONS
                                + " 道。相关的问题一次问完，比来回多轮更省时间。")))
        .put("required", new JSONArray().put("questions"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    JSONArray raw = input.optJSONArray("questions");
    if (raw == null || raw.length() == 0) {
      return error("questions 不能为空");
    }
    if (raw.length() > MAX_QUESTIONS) {
      return error("一次最多问 " + MAX_QUESTIONS + " 道题（收到 " + raw.length() + " 道）。");
    }

    List<ToolSettingsPort.Question> questions = new ArrayList<>();
    for (int i = 0; i < raw.length(); i++) {
      JSONObject q = raw.optJSONObject(i);
      if (q == null) {
        return error("第 " + (i + 1) + " 道题格式不正确。");
      }
      String text = q.optString("question", "").trim();
      if (text.isEmpty()) {
        return error("第 " + (i + 1) + " 道题缺少 question 文本。");
      }
      JSONArray opts = q.optJSONArray("options");
      if (opts == null || opts.length() < MIN_OPTIONS || opts.length() > MAX_OPTIONS) {
        return error(
            "第 "
                + (i + 1)
                + " 道题需要 "
                + MIN_OPTIONS
                + "-"
                + MAX_OPTIONS
                + " 个选项（收到 "
                + (opts == null ? 0 : opts.length())
                + " 个）。");
      }

      List<String> labels = new ArrayList<>();
      List<String> descriptions = new ArrayList<>();
      for (int j = 0; j < opts.length(); j++) {
        JSONObject opt = opts.optJSONObject(j);
        if (opt == null) {
          return error("第 " + (i + 1) + " 道题的第 " + (j + 1) + " 个选项格式不正确。");
        }
        String label = opt.optString("label", "").trim();
        if (label.isEmpty()) {
          return error("第 " + (i + 1) + " 道题的第 " + (j + 1) + " 个选项缺少 label。");
        }
        if (label.length() > MAX_LABEL_CHARS) {
          // 截断而不是报错：模型偶尔会把说明写进 label，为此让整次提问失败不值得。
          label = label.substring(0, MAX_LABEL_CHARS);
        }
        labels.add(label);
        descriptions.add(opt.optString("description", "").trim());
      }

      questions.add(
          new ToolSettingsPort.Question(
              text, q.optString("header", "").trim(), labels, descriptions, q.optBoolean("multiSelect", false)));
    }

    ToolSettingsPort settings = context == null ? null : context.getSettings();
    if (settings == null) {
      return error("无法提问：当前环境没有可用的用户交互通道。请直接把问题写在回复里。");
    }

    List<String> answers;
    try {
      answers = settings.askUserQuestion(questions);
    } catch (RuntimeException e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("提问失败: " + ExceptionUtils.describeException(e));
    }

    if (answers == null) {
      // 如实报告「用户没答」：伪造空答案会让模型以为用户默认同意，进而擅自继续。
      return ok("用户没有回答（已取消或当前没有可用的交互界面）。请不要擅自假定答案，"
          + "可以换个方式提问，或把问题写在回复里让用户下一轮回复。");
    }

    return ok(renderAnswers(questions, answers));
  }

  /** 把问答对渲染成给模型看的文本。 */
  static String renderAnswers(List<ToolSettingsPort.Question> questions, List<String> answers) {
    StringBuilder sb = new StringBuilder();
    sb.append("用户已回答：\n");
    boolean allAnswered = true;
    for (int i = 0; i < questions.size(); i++) {
      ToolSettingsPort.Question q = questions.get(i);
      String answer = i < answers.size() ? answers.get(i) : null;
      sb.append("- \"").append(q.question).append("\" → ");
      if (answer == null || answer.trim().isEmpty()) {
        sb.append("（未作答）");
        allAnswered = false;
      } else {
        sb.append(answer.trim());
      }
      sb.append('\n');
    }
    // 追问引导对齐 cc-haha：答案里若含自由文本（不在给定选项里），说明用户的意图可能
    // 超出模型预设的范围，值得再想一层再动手。
    boolean allFromOptions = true;
    for (int i = 0; i < questions.size() && i < answers.size(); i++) {
      String answer = answers.get(i);
      if (answer == null) {
        continue;
      }
      for (String part : answer.split(",")) {
        String trimmed = part.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        if (!questions.get(i).options.contains(trimmed)) {
          allFromOptions = false;
          break;
        }
      }
    }
    if (!allAnswered) {
      sb.append("部分问题未作答——继续前请先确认这些点，不要默认它们已有答案。");
    } else if (allFromOptions) {
      sb.append("可以按用户的答案继续了。");
    } else {
      sb.append("用户给出了自定义答案，动手前请先想清楚它的含义。");
    }
    return sb.toString();
  }
}
