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
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 子 agent 工具的回归测试。
 *
 * <p><b>为什么需要它</b>：子 agent 的失败模式代价很高——无限递归会耗尽额度，
 * 失败被当成成功会让主 agent 以为任务已完成，空结论会让它停止工作。这些在真机上
 * 都只表现为「AI 说做完了但什么都没发生」。
 */
final class AgentToolTest {

  /** 记录收到的请求并返回预设结果的假 runner。 */
  private static final class FakeRunner implements SubAgentRunner {
    private final Result result;
    private final Exception failure;
    final List<Request> requests = new ArrayList<>();

    FakeRunner(Result result) {
      this.result = result;
      this.failure = null;
    }

    FakeRunner(Exception failure) {
      this.result = null;
      this.failure = failure;
    }

    @Override
    public Result run(Request request) throws Exception {
      requests.add(request);
      if (failure != null) {
        throw failure;
      }
      return result;
    }
  }

  private static JSONObject task(String text) throws Exception {
    return new JSONObject().put("task", text);
  }

  // ---- 参数校验 ----

  @Test
  void rejectsBlankTask() throws Exception {
    // 子 agent 看不到主对话，空任务描述等于让它猜。
    ToolResult result =
        new AgentTool(new FakeRunner(new SubAgentRunner.Result(true, "x", 1, 1)))
            .execute(new JSONObject(), null);

    assertTrue(result.isError());
  }

  @Test
  void rejectsOverlongTask() throws Exception {
    // 过长的任务描述通常意味着模型把整段对话粘了进来，而那正是子 agent 想避免的。
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < AgentTool.MAX_TASK_CHARS + 10; i++) {
      big.append('x');
    }

    ToolResult result =
        new AgentTool(new FakeRunner(new SubAgentRunner.Result(true, "x", 1, 1)))
            .execute(task(big.toString()), null);

    assertTrue(result.isError());
  }

  @Test
  void reportsWhenNoRunnerAvailable() throws Exception {
    ToolResult result = new AgentTool(null).execute(task("调查一下"), null);
    assertTrue(result.isError());
  }

  // ---- 深度限制 ----

  @Test
  void allowsDelegationUpToMaxDepth() throws Exception {
    FakeRunner runner = new FakeRunner(new SubAgentRunner.Result(true, "结论", 2, 3));
    AgentTool tool = new AgentTool(runner, AgentTool.MAX_DEPTH - 1);

    assertFalse(tool.execute(task("调查"), null).isError());
    assertEquals(1, runner.requests.size());
  }

  @Test
  void refusesDelegationBeyondMaxDepth() throws Exception {
    // 无限递归会耗尽额度。超过上限时必须明确拒绝，而不是静默失败。
    FakeRunner runner = new FakeRunner(new SubAgentRunner.Result(true, "x", 1, 1));
    AgentTool tool = new AgentTool(runner, AgentTool.MAX_DEPTH);

    ToolResult result = tool.execute(task("再派一个"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("嵌套上限"), result.getContent());
    // 不得真的发起调用
    assertTrue(runner.requests.isEmpty());
  }

  @Test
  void depthIsPassedToRunner() throws Exception {
    FakeRunner runner = new FakeRunner(new SubAgentRunner.Result(true, "x", 1, 1));
    new AgentTool(runner, 1).execute(task("调查"), null);

    assertEquals(1, runner.requests.get(0).getDepth());
  }

  @Test
  void mainAgentStartsAtDepthZero() {
    assertEquals(0, new AgentTool(new FakeRunner(new SubAgentRunner.Result(true, "x", 0, 0))).getDepth());
  }

  // ---- 模式 ----

  @Test
  void defaultsToExploreMode() throws Exception {
    // 默认只读：委派出去的子 agent 若默认能改文件，一个「帮我看看」的请求可能顺手改代码。
    FakeRunner runner = new FakeRunner(new SubAgentRunner.Result(true, "x", 1, 1));
    new AgentTool(runner).execute(task("调查"), null);

    assertEquals(SubAgentRunner.Mode.EXPLORE, runner.requests.get(0).getMode());
  }

  @Test
  void parsesCodeModeAndItsAliases() throws Exception {
    FakeRunner runner = new FakeRunner(new SubAgentRunner.Result(true, "x", 1, 1));
    AgentTool tool = new AgentTool(runner);

    tool.execute(new JSONObject().put("task", "t").put("mode", "code"), null);
    assertEquals(SubAgentRunner.Mode.CODE, runner.requests.get(0).getMode());

    tool.execute(new JSONObject().put("task", "t").put("mode", "sub-coding"), null);
    assertEquals(SubAgentRunner.Mode.CODE, runner.requests.get(1).getMode());

    // 未知模式回退到只读，而不是报错或放行写权限
    tool.execute(new JSONObject().put("task", "t").put("mode", "nonsense"), null);
    assertEquals(SubAgentRunner.Mode.EXPLORE, runner.requests.get(2).getMode());
  }

  @Test
  void modeNamesRoundTrip() {
    assertEquals(SubAgentRunner.Mode.EXPLORE, SubAgentRunner.Mode.fromName("explore"));
    assertEquals(SubAgentRunner.Mode.CODE, SubAgentRunner.Mode.fromName("code"));
    assertEquals(SubAgentRunner.Mode.EXPLORE, SubAgentRunner.Mode.fromName(null));
    assertEquals("explore", SubAgentRunner.Mode.EXPLORE.wireName());
    assertEquals("code", SubAgentRunner.Mode.CODE.wireName());
  }

  // ---- 结果处理 ----

  @Test
  void returnsSubAgentConclusion() throws Exception {
    FakeRunner runner =
        new FakeRunner(new SubAgentRunner.Result(true, "发现 3 处调用点：A.kt:12", 4, 7));
    ToolResult result = new AgentTool(runner).execute(task("找出调用点"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("发现 3 处调用点"));
    // 附带执行统计，便于用户判断这次委派花了多少
    assertTrue(result.getContent().contains("轮次 4"), result.getContent());
    assertTrue(result.getContent().contains("工具调用 7"), result.getContent());
  }

  @Test
  void failedSubAgentBecomesErrorResultWithItsExplanation() throws Exception {
    // 失败要回灌给模型（让它判断重试还是自己做），而不是抛异常中断循环。
    FakeRunner runner =
        new FakeRunner(new SubAgentRunner.Result(false, "找不到该模块", 1, 2));
    ToolResult result = new AgentTool(runner).execute(task("调查"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("找不到该模块"));
  }

  @Test
  void emptyConclusionIsTreatedAsFailure() throws Exception {
    // 空结论等于没做。当成成功会让主 agent 以为任务已完成而停止工作。
    FakeRunner runner = new FakeRunner(new SubAgentRunner.Result(true, "   ", 3, 5));
    ToolResult result = new AgentTool(runner).execute(task("调查"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("没有给出结论"), result.getContent());
  }

  @Test
  void nullResultIsReportedAsError() throws Exception {
    FakeRunner runner = new FakeRunner((SubAgentRunner.Result) null);
    assertTrue(new AgentTool(runner).execute(task("调查"), null).isError());
  }

  @Test
  void runnerExceptionBecomesErrorResult() throws Exception {
    FakeRunner runner = new FakeRunner(new java.io.IOException("network down"));
    ToolResult result = new AgentTool(runner).execute(task("调查"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("network down"));
  }

  @Test
  void failedSubAgentWithoutDetailStillExplains() throws Exception {
    FakeRunner runner = new FakeRunner(new SubAgentRunner.Result(false, "", 0, 0));
    ToolResult result = new AgentTool(runner).execute(task("调查"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("没有更多信息"));
  }

  // ---- 元数据 ----

  @Test
  void nameAndCategoryAreConservative() {
    AgentTool tool = new AgentTool(new FakeRunner(new SubAgentRunner.Result(true, "x", 0, 0)));

    assertEquals("agent", tool.getName());
    // code 模式可写文件，因此归类为 WRITE：只读模式下不放行。
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolCategory.WRITE, tool.getCategory());
    assertFalse(tool.isAllowedInReadonlyMode());
  }

  @Test
  void descriptionExplainsContextIsolationAndSelfContainment() {
    // 说明直接影响模型的使用质量：不说清「子 agent 看不到对话」，
    // 模型会给出「按之前说的做」这种子 agent 无法执行的描述。
    String description =
        new AgentTool(new FakeRunner(new SubAgentRunner.Result(true, "x", 0, 0)))
            .getDescription();

    assertTrue(description.contains("own context"), description);
    assertTrue(description.contains("cannot see"), description);
    assertTrue(description.contains("self-contained"), description);
  }

  @Test
  void schemaRequiresTask() throws Exception {
    JSONObject schema =
        new AgentTool(new FakeRunner(new SubAgentRunner.Result(true, "x", 0, 0))).getParameters();

    assertEquals("object", schema.optString("type"));
    assertEquals("task", schema.getJSONArray("required").optString(0));
  }

  @Test
  void reportsProgressWhenContextProvided() throws Exception {
    FakeRunner runner = new FakeRunner(new SubAgentRunner.Result(true, "x", 1, 1));
    List<String> progress = new ArrayList<>();
    ToolContext context =
        ToolContext.builder().homePath("/w").progressListener(progress::add).build();

    new AgentTool(runner).execute(task("调查依赖关系"), context);

    assertFalse(progress.isEmpty());
    assertTrue(progress.get(0).contains("explore"));
  }
}
