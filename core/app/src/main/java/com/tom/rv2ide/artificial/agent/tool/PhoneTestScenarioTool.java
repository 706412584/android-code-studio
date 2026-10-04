/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import android.content.Context;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolInvoker;
import com.tom.rv2ide.ai.tool.ToolInvokerAware;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多步测试场景：把「启动 → 等界面 → 截图 → 点击 → 连拍 → 查崩」编成一次可重放的验证。
 *
 * <p>每一步都是一次工具调用（经 {@link ToolRegistry} 派发），并可选声明期望
 * （{@code expect}）。返回<b>每步通过/失败</b>，失败处附带证据（该步的工具输出 +
 * 未满足的期望），让模型不必自己逐条比对。
 *
 * <p><b>为什么要有它</b>：单次工具调用只回答「这一步做成了没有」，回答不了
 * 「整个流程是否仍然工作」。回归测试的价值在于把一串动作固定下来、反复执行。
 * 本工具就是那个可重放的载体——把步骤写进参数，之后每次跑同一份参数即可。
 *
 * <p><b>期望语法</b>（{@code expect}）：
 * <ul>
 *   <li>{@code notError}（默认 true）— 该步工具结果不得为错误
 *   <li>{@code field} — 在结果文本中按 {@code field: value} / {@code field=value} 取值
 *   <li>{@code equals} / {@code contains} / {@code notContains} / {@code matches}
 *   <li>{@code lte} / {@code gte} — 数值比较（值可带 % 或单位后缀）
 * </ul>
 * 不写 {@code field} 时，{@code contains} 等直接在整段结果文本上匹配。
 */
public final class PhoneTestScenarioTool extends BaseTool implements ToolInvokerAware {

  private static final Logger log = LoggerFactory.getLogger(PhoneTestScenarioTool.class);

  /** 单个步骤结果的证据保留上限（字符）。 */
  private static final int EVIDENCE_MAX_CHARS = 2000;

  /** 场景最大步数，防止一个参数把 agent 循环拖住。 */
  private static final int MAX_STEPS = 30;

  private final Context appContext;

  /**
   * 子工具调用入口。装配方在构建执行器后注入（见 {@link ToolInvokerAware}）。
   * 为 null 时步骤会明确失败，而不是退回直接 {@code execute} 绕过权限。
   */
  private volatile ToolInvoker toolInvoker;

  public PhoneTestScenarioTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public void setToolInvoker(ToolInvoker invoker) {
    this.toolInvoker = invoker;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_TEST_SCENARIO;
  }

  @Override
  public String getDescription() {
    return "多步测试场景：按顺序执行一串设备工具调用（如 启动→等待→截图→点击→连拍→查崩），"
        + "每步可声明期望（expect），返回每步通过/失败与失败处证据。"
        + "用于把一次真机验证固化成可重放的回归流程。"
        + "步骤形如 {name, tool, args, expect}；expect 支持 notError/field/equals/contains/"
        + "notContains/matches/lte/gte。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean needsConfirmation() {
    // 场景可包含点击、清数据等有副作用的动作，确认模式下应经用户同意。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "name",
                    new JSONObject().put("type", "string").put("description", "场景名，用于报告"))
                .put(
                    "steps",
                    new JSONObject()
                        .put("type", "array")
                        .put(
                            "description",
                            "步骤数组。每项: {name, tool, args, expect}。"
                                + "tool 为已注册工具名；args 为其参数对象；expect 见工具说明。")
                        .put("items", new JSONObject().put("type", "object")))
                .put(
                    "stopOnFailure",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "遇到首个失败即停止，默认 true")))
        .put("required", new org.json.JSONArray().put("steps"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    JSONArray steps = input.optJSONArray("steps");
    if (steps == null || steps.length() == 0) {
      return error("steps 不能为空");
    }
    if (steps.length() > MAX_STEPS) {
      return error("步数过多（" + steps.length() + "），最多 " + MAX_STEPS + " 步");
    }
    boolean stopOnFailure = input.optBoolean("stopOnFailure", true);
    String scenarioName = input.optString("name", "scenario").trim();

    StringBuilder report = new StringBuilder();
    report.append("多步场景: ").append(scenarioName).append('\n');
    report.append("步数: ").append(steps.length()).append('\n');

    List<StepOutcome> outcomes = new ArrayList<>();
    int passed = 0;
    int failedIndex = -1;
    long scenarioStart = System.nanoTime();

    for (int i = 0; i < steps.length(); i++) {
      JSONObject step = steps.optJSONObject(i);
      if (step == null) {
        outcomes.add(new StepOutcome(i, "(invalid)", false, "步骤不是对象", ""));
        failedIndex = i;
        if (stopOnFailure) {
          break;
        }
        continue;
      }
      String stepName = step.optString("name", "step" + (i + 1));
      String toolName = step.optString("tool", "").trim();
      JSONObject args = step.optJSONObject("args");
      JSONObject expect = step.optJSONObject("expect");

      if (context != null) {
        context.reportProgress("场景步骤 " + (i + 1) + "/" + steps.length() + ": " + stepName);
      }

      long stepStart = System.nanoTime();
      StepOutcome outcome = runStep(i, stepName, toolName, args, expect, context);
      outcome.durationMs = (System.nanoTime() - stepStart) / 1_000_000L;
      outcomes.add(outcome);

      if (outcome.passed) {
        passed++;
      } else {
        failedIndex = i;
        if (stopOnFailure) {
          break;
        }
      }
    }

    long totalMs = (System.nanoTime() - scenarioStart) / 1_000_000L;

    for (StepOutcome outcome : outcomes) {
      report.append('[')
          .append(outcome.index + 1)
          .append('/')
          .append(steps.length())
          .append("] ")
          .append(outcome.name)
          .append(" (")
          .append(outcome.tool)
          .append(") ... ")
          .append(outcome.passed ? "PASS" : "FAIL")
          .append(" (")
          .append(outcome.durationMs)
          .append("ms)\n");
      if (!outcome.passed) {
        report.append("  失败原因: ").append(outcome.reason).append('\n');
        if (!outcome.evidence.isEmpty()) {
          report.append("  证据:\n");
          for (String line : outcome.evidence.split("\\r?\\n")) {
            report.append("    ").append(line).append('\n');
          }
        }
      }
    }

    report.append("结果: 通过 ").append(passed).append('/').append(outcomes.size());
    if (failedIndex >= 0) {
      report.append("，失败于第 ").append(failedIndex + 1).append(" 步");
    }
    report.append("，耗时 ").append(totalMs).append("ms\n");

    boolean allPassed = failedIndex < 0 && outcomes.size() == steps.length();
    report.append(allPassed ? "结论: 场景通过。" : "结论: 场景失败，请依据上面的证据定位。");

    if (allPassed) {
      return ok(report.toString());
    }
    // 失败作为 error 返回，让 agent 明确知道验证未通过（错误可回灌给模型）
    return error(report.toString());
  }

  /** 单步执行结果。 */
  private static final class StepOutcome {
    final int index;
    final String name;
    final String tool;
    final boolean passed;
    final String reason;
    final String evidence;
    long durationMs;

    StepOutcome(int index, String name, boolean passed, String reason, String evidence) {
      this(index, name, "", passed, reason, evidence);
    }

    StepOutcome(
        int index, String name, String tool, boolean passed, String reason, String evidence) {
      this.index = index;
      this.name = name == null ? "" : name;
      this.tool = tool == null ? "" : tool;
      this.passed = passed;
      this.reason = reason == null ? "" : reason;
      this.evidence = evidence == null ? "" : evidence;
    }
  }

  private StepOutcome runStep(
      int index,
      String name,
      String toolName,
      JSONObject args,
      JSONObject expect,
      ToolContext context) {
    if (toolName.isEmpty()) {
      return new StepOutcome(index, name, toolName, false, "未指定 tool", "");
    }
    ToolInvoker invoker = toolInvoker;
    if (invoker == null) {
      // 不退回直接 execute：那会绕过权限判定，与本次修复的目的相悖。
      return new StepOutcome(
          index, name, toolName, false, "工具调用入口不可用（装配方未注入 ToolInvoker）", "");
    }

    ToolResult result;
    try {
      result = invoker.invoke(toolName, args, context);
    } catch (Exception e) {
      log.warn("场景步骤执行异常: {}", toolName, e);
      return new StepOutcome(index, name, toolName, false, "执行异常: " + e.getMessage(), "");
    }
    if (result == null) {
      return new StepOutcome(index, name, toolName, false, "工具返回为空: " + toolName, "");
    }

    String content = result.getContent();
    String evidence = truncate(content);

    // 期望判定
    if (expect == null || expect.length() == 0) {
      // 无期望：非错误即通过
      return new StepOutcome(
          index, name, toolName, !result.isError(),
          result.isError() ? "工具返回错误" : "",
          result.isError() ? evidence : "");
    }

    boolean notError = expect.optBoolean("notError", true);
    if (notError && result.isError()) {
      return new StepOutcome(index, name, toolName, false, "工具返回错误（期望不报错）", evidence);
    }

    String field = expect.optString("field", "").trim();
    String value = field.isEmpty() ? content : extractField(content, field);
    if (!field.isEmpty() && value == null) {
      return new StepOutcome(
          index, name, toolName, false, "结果中找不到字段: " + field, evidence);
    }

    String failure = evaluate(expect, value, field);
    if (failure != null) {
      return new StepOutcome(index, name, toolName, false, failure, evidence);
    }
    return new StepOutcome(index, name, toolName, true, "", "");
  }

  /** 按期望逐项校验；返回 null 表示全部满足，否则返回失败说明。 */
  private static String evaluate(JSONObject expect, String value, String field) {
    String actual = value == null ? "" : value;

    if (expect.has("equals")) {
      String expected = expect.optString("equals", "");
      if (!actual.equals(expected)) {
        return describe(field, "equals", expected, actual);
      }
    }
    if (expect.has("contains")) {
      String expected = expect.optString("contains", "");
      if (!actual.contains(expected)) {
        return describe(field, "contains", expected, actual);
      }
    }
    if (expect.has("notContains")) {
      String expected = expect.optString("notContains", "");
      if (actual.contains(expected)) {
        return describe(field, "notContains", expected, actual);
      }
    }
    if (expect.has("matches")) {
      String pattern = expect.optString("matches", "");
      try {
        if (!actual.matches(pattern)) {
          return describe(field, "matches", pattern, actual);
        }
      } catch (RuntimeException e) {
        return "matches 正则非法: " + pattern;
      }
    }
    if (expect.has("lte") || expect.has("gte")) {
      Double number = firstNumber(actual);
      if (number == null) {
        return describe(field, "numeric", "数字", actual);
      }
      if (expect.has("lte")) {
        double bound = expect.optDouble("lte", Double.NaN);
        if (!(number <= bound)) {
          return describe(field, "lte", String.valueOf(bound), actual);
        }
      }
      if (expect.has("gte")) {
        double bound = expect.optDouble("gte", Double.NaN);
        if (!(number >= bound)) {
          return describe(field, "gte", String.valueOf(bound), actual);
        }
      }
    }
    return null;
  }

  private static String describe(String field, String op, String expected, String actual) {
    String target = field == null || field.isEmpty() ? "结果" : "字段 " + field;
    return target + " " + op + " " + expected + " 未满足，实际为: " + actual;
  }

  /**
   * 从结果文本中按 {@code field: value} / {@code field=value} 取值。
   * 行内首次出现即返回，忽略大小写。
   */
  static String extractField(String content, String field) {
    if (content == null || field == null || field.isEmpty()) {
      return null;
    }
    String lowerField = field.toLowerCase(Locale.ROOT);
    for (String line : content.split("\\r?\\n")) {
      String trimmed = line.trim();
      String lower = trimmed.toLowerCase(Locale.ROOT);
      int idx = lower.indexOf(lowerField);
      if (idx < 0) {
        continue;
      }
      int after = idx + lowerField.length();
      // 字段名后应紧跟分隔符
      while (after < trimmed.length() && (trimmed.charAt(after) == ' ' || trimmed.charAt(after) == '\t')) {
        after++;
      }
      if (after < trimmed.length() && (trimmed.charAt(after) == ':' || trimmed.charAt(after) == '=')) {
        String value = trimmed.substring(after + 1).trim();
        if (!value.isEmpty()) {
          return value;
        }
      }
    }
    return null;
  }

  /** 取文本中的第一个数字（忽略 % 等后缀）。 */
  static Double firstNumber(String text) {
    if (text == null) {
      return null;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text);
    if (matcher.find()) {
      try {
        return Double.parseDouble(matcher.group());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String truncate(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= EVIDENCE_MAX_CHARS
        ? text
        : text.substring(0, EVIDENCE_MAX_CHARS) + "\n…（证据已截断）";
  }
}
