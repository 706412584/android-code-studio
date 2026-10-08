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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 提问工具的输入校验、答案渲染与「用户没答」的处理。
 *
 * <p><b>为什么需要这组测试</b>：本工具与其它工具的根本差异是——它的结果是**用户给的**，
 * 而用户可能取消、可能给自定义答案。这两条路径写错了不会报错，只会让模型以为
 * 「用户默认同意」，然后擅自继续。必须钉住。
 */
final class AskUserQuestionToolTest {

  /** 记录收到的题目，并按预设答案返回。 */
  private static final class RecordingSettings implements ToolSettingsPort {
    List<ToolSettingsPort.Question> received;
    private final List<String> answer;
    private final boolean cancelled;

    RecordingSettings(List<String> answer, boolean cancelled) {
      this.answer = answer;
      this.cancelled = cancelled;
    }

    @Override
    public String getPermissionMode() {
      return PERMISSION_AUTO;
    }

    @Override
    public List<String> askUserQuestion(List<ToolSettingsPort.Question> questions) {
      this.received = questions;
      return cancelled ? null : answer;
    }
  }

  private static JSONObject option(String label) {
    return new JSONObject().put("label", label);
  }

  private static JSONObject question(String text, String... labels) {
    JSONArray opts = new JSONArray();
    for (String label : labels) {
      opts.put(option(label));
    }
    return new JSONObject().put("question", text).put("options", opts);
  }

  private static JSONObject input(JSONObject... questions) {
    JSONArray arr = new JSONArray();
    for (JSONObject q : questions) {
      arr.put(q);
    }
    return new JSONObject().put("questions", arr);
  }

  private static ToolResult run(ToolSettingsPort settings, JSONObject args) {
    ToolContext context = ToolContext.builder().settings(settings).build();
    return new AskUserQuestionTool().execute(args, context);
  }

  // ---- 正常路径 ----

  @Test
  void answersAreRenderedBackToTheModel() {
    RecordingSettings settings = new RecordingSettings(Arrays.asList("Kotlin"), false);
    ToolResult result = run(settings, input(question("用哪个语言？", "Kotlin", "Java")));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("Kotlin"), result.getContent());
    assertTrue(result.getContent().contains("用哪个语言？"), result.getContent());
    assertTrue(result.getContent().contains("可以按用户的答案继续了"), result.getContent());
  }

  @Test
  void questionReachesTheHostWithOptionsAndDescriptions() {
    RecordingSettings settings = new RecordingSettings(Arrays.asList("A"), false);
    JSONObject q =
        new JSONObject()
            .put("question", "选哪个？")
            .put("header", "方案")
            .put(
                "options",
                new JSONArray()
                    .put(new JSONObject().put("label", "A").put("description", "第一个方案"))
                    .put(new JSONObject().put("label", "B").put("description", "第二个方案")));

    run(settings, input(q));

    assertEquals(1, settings.received.size());
    ToolSettingsPort.Question passed = settings.received.get(0);
    assertEquals("选哪个？", passed.question);
    assertEquals("方案", passed.header);
    assertEquals(Arrays.asList("A", "B"), passed.options);
    assertEquals(Arrays.asList("第一个方案", "第二个方案"), passed.optionDescriptions);
    assertFalse(passed.multiSelect);
  }

  @Test
  void multiSelectFlagIsPassedThrough() {
    RecordingSettings settings = new RecordingSettings(Arrays.asList("A,B"), false);
    JSONObject q = question("要哪些？", "A", "B").put("multiSelect", true);

    ToolResult result = run(settings, input(q));

    assertTrue(settings.received.get(0).multiSelect, "multiSelect 应传下去");
    assertTrue(result.getContent().contains("A,B"), result.getContent());
  }

  @Test
  void multipleQuestionsAreAnsweredInOrder() {
    RecordingSettings settings = new RecordingSettings(Arrays.asList("Kotlin", "Room"), false);
    ToolResult result =
        run(
            settings,
            input(question("语言？", "Kotlin", "Java"), question("存储？", "Room", "SQLite")));

    String content = result.getContent();
    assertTrue(content.contains("Kotlin"), content);
    assertTrue(content.contains("Room"), content);
    assertTrue(content.indexOf("语言？") < content.indexOf("存储？"), "应按提问顺序渲染");
  }

  // ---- 「用户没答」必须如实报告 ----

  /**
   * 用户取消时不得伪造答案。
   *
   * <p>若返回一个空的成功结果，模型会以为用户默认同意，进而擅自继续——这是本工具
   * 最危险的失效模式，因此必须明确告诉它「没答」并劝阻臆断。
   */
  @Test
  void cancellationIsReportedHonestlyAndDiscouragesAssumption() {
    RecordingSettings settings = new RecordingSettings(null, true);
    ToolResult result = run(settings, input(question("用哪个？", "A", "B")));

    assertFalse(result.isError(), "取消不是错误，是有效结果");
    assertTrue(result.getContent().contains("没有回答"), result.getContent());
    assertTrue(result.getContent().contains("不要擅自假定"), result.getContent());
  }

  /** 没有 UI 可问（后台运行）时同样如实报告，而不是静默成功。 */
  @Test
  void missingInteractionChannelIsReported() {
    ToolResult result =
        run(ToolSettingsPort.defaults(), input(question("用哪个？", "A", "B")));

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("没有回答"), result.getContent());
  }

  @Test
  void partiallyAnsweredQuestionsAreFlagged() {
    RecordingSettings settings = new RecordingSettings(Arrays.asList("Kotlin", ""), false);
    ToolResult result =
        run(settings, input(question("语言？", "Kotlin", "Java"), question("存储？", "Room", "SQLite")));

    assertTrue(result.getContent().contains("未作答"), result.getContent());
    assertTrue(result.getContent().contains("不要默认"), result.getContent());
  }

  /** 自定义答案（不在选项里）应提示模型再想一层，而不是当作普通选项处理。 */
  @Test
  void customAnswerGetsDifferentGuidanceThanAPresetOption() {
    RecordingSettings settings = new RecordingSettings(Arrays.asList("用 Rust 重写"), false);
    ToolResult result = run(settings, input(question("语言？", "Kotlin", "Java")));

    assertTrue(result.getContent().contains("自定义答案"), result.getContent());
  }

  // ---- 输入校验 ----

  @Test
  void emptyQuestionsIsRejected() {
    ToolResult result = run(new RecordingSettings(Arrays.asList(), false), new JSONObject().put("questions", new JSONArray()));
    assertTrue(result.isError());
  }

  @Test
  void tooManyQuestionsIsRejected() {
    JSONObject[] qs = new JSONObject[5];
    for (int i = 0; i < 5; i++) {
      qs[i] = question("问题" + i + "？", "A", "B");
    }
    ToolResult result = run(new RecordingSettings(Arrays.asList(), false), input(qs));
    assertTrue(result.isError(), "超过 4 道应被拒绝");
    assertTrue(result.getContent().contains("4"), result.getContent());
  }

  /** 只有一个选项不构成选择——应被拒绝并说明原因。 */
  @Test
  void singleOptionIsRejected() {
    ToolResult result =
        run(new RecordingSettings(Arrays.asList(), false), input(question("只有一个？", "A")));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("2"), result.getContent());
  }

  @Test
  void tooManyOptionsIsRejected() {
    ToolResult result =
        run(
            new RecordingSettings(Arrays.asList(), false),
            input(question("太多？", "A", "B", "C", "D", "E")));
    assertTrue(result.isError());
  }

  @Test
  void missingQuestionTextIsRejected() {
    JSONObject q = new JSONObject().put("question", "  ").put("options", new JSONArray().put(option("A")).put(option("B")));
    ToolResult result = run(new RecordingSettings(Arrays.asList(), false), input(q));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("question"), result.getContent());
  }

  @Test
  void missingOptionLabelIsRejected() {
    JSONObject q =
        new JSONObject()
            .put("question", "问？")
            .put("options", new JSONArray().put(option("A")).put(new JSONObject()));
    ToolResult result = run(new RecordingSettings(Arrays.asList(), false), input(q));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("label"), result.getContent());
  }

  /** 超长 label 截断而非报错：模型偶尔把说明写进 label，不该因此让整次提问失败。 */
  @Test
  void overlongLabelIsTruncatedNotRejected() {
    StringBuilder longLabel = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      longLabel.append('x');
    }
    RecordingSettings settings = new RecordingSettings(Arrays.asList("A"), false);
    JSONObject q = question("问？", longLabel.toString(), "B");

    ToolResult result = run(settings, input(q));

    assertFalse(result.isError(), result.getContent());
    assertEquals(AskUserQuestionTool.MAX_LABEL_CHARS, settings.received.get(0).options.get(0).length());
  }

  @Test
  void settingsFailureIsReportedNotThrown() {
    ToolSettingsPort throwing =
        new ToolSettingsPort() {
          @Override
          public String getPermissionMode() {
            return PERMISSION_AUTO;
          }

          @Override
          public List<String> askUserQuestion(List<ToolSettingsPort.Question> questions) {
            throw new IllegalStateException("UI 崩了");
          }
        };

    ToolResult result = run(throwing, input(question("问？", "A", "B")));

    assertTrue(result.isError(), "宿主异常应转成工具错误而不是炸掉循环");
    assertTrue(result.getContent().contains("提问失败"), result.getContent());
  }

  // ---- 工具元数据 ----

  @Test
  void isReadOnlyAndSerial() {
    AskUserQuestionTool tool = new AskUserQuestionTool();
    assertEquals("ask_user_question", tool.getName());
    assertTrue(tool.isAllowedInReadonlyMode(), "只读模式也应能提问——调查阶段最需要澄清");
    assertFalse(tool.isConcurrencySafe(), "弹窗等待必须串行，否则多个弹窗互相抢占");
  }

  @Test
  void schemaDocumentsLimitsAndDiscouragesOtherOption() throws Exception {
    JSONObject params = new AskUserQuestionTool().getParameters();
    JSONObject q =
        params
            .getJSONObject("properties")
            .getJSONObject("questions")
            .getJSONObject("items")
            .getJSONObject("properties");

    String optionsDesc = q.getJSONObject("options").getString("description");
    assertTrue(optionsDesc.contains("2") && optionsDesc.contains("4"), optionsDesc);
    assertTrue(optionsDesc.contains("其它"), "应告诉模型不要自己加「其它」选项：" + optionsDesc);
    assertTrue(q.getJSONObject("multiSelect").getString("description").contains("默认"), q.toString());
  }
}
