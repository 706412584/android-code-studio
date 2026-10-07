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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
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
 * shell 工具的参数归一化。
 *
 * <p><b>为什么需要这组测试</b>：实测设备会话里 15 次 {@code shell_execute} 有 5 次失败，
 * 全部是模型把 {@code timeoutMs} 当成「秒」来填——写 {@code 10} / {@code 30} 表示
 * 「10 秒 / 30 秒」，而字段单位是毫秒。10 毫秒的命令连 fork 都来不及，一律被 kill，
 * 模型看到超时后重试同一条命令，如此往复。
 *
 * <p>这不是「模型笨」，而是字段单位与模型先验不一致时的必然结果。修法是给下限兜底：
 * 荒谬的小值被夹到 1 秒，命令正常跑完，模型从结果里看到成功就会继续。
 */
final class ShellExecuteToolTest {

  /** 记录收到的超时值，其余行为固定返回成功。 */
  private static final class RecordingBackend implements ShellBackend {
    final List<Long> timeouts = new ArrayList<>();
    private final String id;
    private final boolean available;

    RecordingBackend() {
      this("recording", true);
    }

    RecordingBackend(String id, boolean available) {
      this.id = id;
      this.available = available;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String displayName() {
      return "记录用后端";
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return available ? "" : "测试用：不可用";
    }

    @Override
    public ShellRequest.ShellResult execute(
        ShellRequest request, ShellRequest.ShellOutputSink sink) {
      timeouts.add(request.getTimeoutMs());
      return ShellRequest.ShellResult.success("ok\n");
    }
  }

  /** 固定返回一条失败输出，用于验证「找不到可执行文件」的提示。 */
  private static final class FailingBackend implements ShellBackend {
    private final String id;
    private final String stderr;
    private final boolean cliToolchain;

    FailingBackend(String id, String stderr, boolean cliToolchain) {
      this.id = id;
      this.stderr = stderr;
      this.cliToolchain = cliToolchain;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String displayName() {
      return "失败后端 " + id;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public String unavailableReason() {
      return "";
    }

    @Override
    public boolean hasCliToolchain() {
      return cliToolchain;
    }

    @Override
    public String capabilitySummary() {
      return "能力说明-" + id;
    }

    @Override
    public ShellRequest.ShellResult execute(
        ShellRequest request, ShellRequest.ShellOutputSink sink) {
      return new ShellRequest.ShellResult(127, "", stderr, false, false);
    }
  }

  private static ToolContext context() {
    return ToolContext.builder().homePath(System.getProperty("java.io.tmpdir")).build();
  }

  private static long runWithTimeout(Object timeoutMs) {
    RecordingBackend backend = new RecordingBackend();
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(backend);
    JSONObject args = new JSONObject().put("command", "true");
    if (timeoutMs != null) {
      args.put("timeoutMs", timeoutMs);
    }
    ToolResult result = new ShellExecuteTool(registry).execute(args, context());
    assertFalse(result.isError(), "命令应成功: " + result.getContent());
    assertEquals(1, backend.timeouts.size(), "后端应被调用一次");
    return backend.timeouts.get(0);
  }

  @Test
  void absurdlySmallTimeoutIsClampedUpSoTheCommandCanActuallyRun() {
    // 实测值：模型填 10 与 30（本意是秒）。夹到 1 秒后命令能跑完，
    // 而不再必然超时。
    assertEquals(1_000L, runWithTimeout(10), "10 毫秒应被夹到 1 秒");
    assertEquals(1_000L, runWithTimeout(30), "30 毫秒应被夹到 1 秒");
  }

  @Test
  void timeoutAtOrAboveTheFloorIsLeftAlone() {
    assertEquals(1_000L, runWithTimeout(1_000), "正好等于下限时不改动");
    assertEquals(30_000L, runWithTimeout(30_000), "正常的 30 秒应原样传递");
    assertEquals(600_000L, runWithTimeout(600_000), "正好等于上限时不改动");
  }

  @Test
  void oversizedTimeoutIsClampedToTenMinutes() {
    // 一次调用独占 agent 循环十分钟已是容忍上限，再长用户会以为卡死。
    assertEquals(600_000L, runWithTimeout(3_600_000), "超过 10 分钟应被夹到上限");
  }

  @Test
  void missingTimeoutFallsBackToTheDefault() {
    assertEquals(ShellRequest.DEFAULT_TIMEOUT_MS, runWithTimeout(null), "缺省时用默认超时");
  }

  @Test
  void nonPositiveTimeoutFallsBackToTheDefault() {
    // 0 与负数都是「没填」的等价表达，不能当成「立刻超时」。
    assertEquals(ShellRequest.DEFAULT_TIMEOUT_MS, runWithTimeout(0));
    assertEquals(ShellRequest.DEFAULT_TIMEOUT_MS, runWithTimeout(-1));
  }

  @Test
  void parameterDescriptionStatesTheUnitAndTheBounds() throws Exception {
    // 单位说明是给模型看的提示词，是这道防线的另一半：光夹住小值只是兜底，
    // 让模型知道「填 30000 才是 30 秒」才能从源头减少误填。
    String description =
        new ShellExecuteTool(null)
            .getParameters()
            .getJSONObject("properties")
            .getJSONObject("timeoutMs")
            .getString("description");
    assertTrue(description.contains("毫秒"), "必须写明单位: " + description);
    assertTrue(description.contains("30000"), "必须给出秒→毫秒的换算示例: " + description);
    assertTrue(description.contains("600000"), "必须说明构建类命令的建议值: " + description);
  }

  @Test
  void emptyCommandIsRejectedWithoutTouchingTheBackend() {
    RecordingBackend backend = new RecordingBackend();
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(backend);

    ToolResult result =
        new ShellExecuteTool(registry).execute(new JSONObject().put("command", "   "), context());

    assertTrue(result.isError(), "空命令应被拒绝");
    assertTrue(backend.timeouts.isEmpty(), "空命令不应触发后端执行");
  }

  // ---- 临时切换后端 ----

  private static String backendOf(ShellBackendRegistry registry, String requested) {
    JSONObject args = new JSONObject().put("command", "true");
    if (requested != null) {
      args.put("backend", requested);
    }
    ToolResult result = new ShellExecuteTool(registry).execute(args, context());
    return result.getContent();
  }

  @Test
  void omittingBackendUsesTheUsersConfiguredBackend() {
    // 默认行为不能变：不填 backend 就走用户设置的后端。
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new RecordingBackend("termux", true));
    registry.register(new RecordingBackend("shizuku", true));
    registry.setActiveId("shizuku");

    String out = backendOf(registry, null);
    assertTrue(out.contains("记录用后端"), "应使用 active 后端: " + out);
    assertFalse(out.contains("已回退"), "默认路径不应报告回退: " + out);
  }

  @Test
  void explicitBackendOverridesTheConfiguredOneForThisCallOnly() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new RecordingBackend("termux", true));
    registry.register(new RecordingBackend("shizuku", true));
    registry.setActiveId("shizuku");

    backendOf(registry, "termux");

    // 只作用于本次调用：active 后端不被改动，下一次省略 backend 仍走用户设置。
    assertEquals("shizuku", registry.activeId(), "指定 backend 不得改动用户设置");
  }

  /**
   * 指定一个不可用的后端时回退并说明原因，而不是直接失败。
   *
   * <p>直接失败会让模型卡在「我选对了后端却执行不了」——它往往并不知道那台设备
   * 没装/没授权该后端。回退 + 报告原因，模型既能拿到结果，也知道拿到的不是它要的权限。
   */
  @Test
  void unavailableRequestedBackendFallsBackAndExplains() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new RecordingBackend("termux", true));
    registry.register(new RecordingBackend("shizuku", false));
    registry.setActiveId("termux");

    String out = backendOf(registry, "shizuku");
    assertTrue(out.contains("已回退"), "应报告回退: " + out);
    assertTrue(out.contains("不可用"), "应说明回退原因: " + out);
  }

  @Test
  void unknownBackendIdFallsBackInsteadOfCrashing() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new RecordingBackend("termux", true));

    String out = backendOf(registry, "不存在的后端");
    assertFalse(out.isEmpty(), "未知后端 id 不应导致空结果");
    assertTrue(out.contains("已回退") || out.contains("记录用后端"), "应回退到可用后端: " + out);
  }

  @Test
  void blankBackendIsTreatedAsOmitted() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new RecordingBackend("termux", true));
    registry.setActiveId("termux");

    String out = backendOf(registry, "   ");
    assertFalse(out.contains("已回退"), "空白 backend 等同省略，不应回退: " + out);
  }

  // ---- 失败时的换后端提示 ----

  private static String runFailing(ShellBackendRegistry registry, String stderr) {
    return new ShellExecuteTool(registry)
        .execute(new JSONObject().put("command", "git log"), context())
        .getContent();
  }

  @Test
  void executableMissingSuggestsTheOtherBackendWithItsCapabilities() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FailingBackend("shizuku", "sh: git: inaccessible or not found", false));
    registry.register(new FailingBackend("termux", "", true));
    registry.setActiveId("shizuku");

    String out = runFailing(registry, "sh: git: inaccessible or not found");
    assertTrue(out.contains("提示"), "应给出换后端提示: " + out);
    assertTrue(out.contains("termux"), "应列出可换的后端: " + out);
    assertTrue(out.contains("能力说明-termux"), "应带上该后端的能力说明: " + out);
  }

  /** 提示不是「换后端万能」：当前后端本就有工具链时，应说明可能是真没装。 */
  @Test
  void missingOnToolchainBackendDoesNotPromiseAnotherBackendWillHelp() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FailingBackend("termux", "git: command not found", true));
    registry.setActiveId("termux");

    String out = runFailing(registry, "git: command not found");
    assertTrue(out.contains("确实没安装"), "应说明可能真的没装: " + out);
  }

  /** 成功结果不该带提示——否则每一条正常命令后面都挂一段噪音。 */
  @Test
  void successfulRunHasNoBackendHint() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new RecordingBackend("termux", true));
    registry.setActiveId("termux");

    String out = backendOf(registry, null);
    assertFalse(out.contains("[提示"), "成功时不应出现换后端提示: " + out);
  }

  // ---- 提示词 ----

  @Test
  void promptSupplementListsBackendsWithTheirCapabilitiesAndTheDefault() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FailingBackend("shizuku", "", false));
    registry.register(new FailingBackend("termux", "", true));
    registry.setActiveId("shizuku");

    String supplement = new ShellExecuteTool(registry).promptSupplement("agent");
    assertTrue(supplement != null && !supplement.isEmpty(), "应生成补充说明");
    assertTrue(supplement.contains("shizuku"), "应列出后端 id: " + supplement);
    assertTrue(supplement.contains("termux"), "应列出后端 id: " + supplement);
    assertTrue(supplement.contains("能力说明-shizuku"), "应带上能力说明: " + supplement);
    assertTrue(supplement.contains("当前默认"), "应标出默认后端: " + supplement);
    assertTrue(supplement.contains("backend"), "应说明可用 backend 参数指定: " + supplement);
  }

  /** 只有一个后端时不啰嗦——没有可选项，说多了只会让模型纠结。 */
  @Test
  void promptSupplementOmitsChoiceGuidanceWhenOnlyOneBackend() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FailingBackend("termux", "", true));

    String supplement = new ShellExecuteTool(registry).promptSupplement("agent");
    assertTrue(supplement != null && supplement.contains("termux"), "仍应说明该后端能力");
    assertFalse(supplement.contains("当前默认"), "单后端无需标默认: " + supplement);
  }

  @Test
  void promptSupplementIsNullWithoutRegistryOrBackends() {
    assertEquals(null, new ShellExecuteTool(null).promptSupplement("agent"));
    assertEquals(
        null, new ShellExecuteTool(new ShellBackendRegistry()).promptSupplement("agent"));
  }
}
