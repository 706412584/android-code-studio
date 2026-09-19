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

    @Override
    public String id() {
      return "recording";
    }

    @Override
    public String displayName() {
      return "记录用后端";
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
    public ShellRequest.ShellResult execute(
        ShellRequest request, ShellRequest.ShellOutputSink sink) {
      timeouts.add(request.getTimeoutMs());
      return ShellRequest.ShellResult.success("ok\n");
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
}
