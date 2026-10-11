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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.WorkspaceSymbolPort.Result;
import com.tom.rv2ide.ai.tool.WorkspaceSymbolPort.Symbol;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceSymbolTool}：四态文案、参数校验与钳制、kind 透传、1 基换算、格式化。
 *
 * <p>最要紧的是**「查到但没有结果」不能被说成「查不了」**——否则模型会以为工具坏了而反复重试。
 */
class WorkspaceSymbolToolTest {

  /** 记录收到参数的假端口。 */
  private static final class FakePort implements WorkspaceSymbolPort {
    Result result = Result.of(Collections.emptyList());
    String reason = "";
    String lastQuery;
    Set<Kind> lastKinds;
    int lastLimit;
    long lastTimeout;

    @Override
    public Result search(String query, Set<Kind> kinds, int limit, long timeoutMs) {
      lastQuery = query;
      lastKinds = kinds;
      lastLimit = limit;
      lastTimeout = timeoutMs;
      return result;
    }

    @Override
    public boolean isAvailable() {
      return reason.isEmpty();
    }

    @Override
    public String unavailableReason() {
      return reason;
    }
  }

  private static ToolContext ctx(FakePort port, String home) {
    return ToolContext.builder().homePath(home).workspaceSymbol(port).build();
  }

  private static JSONObject args(String query) throws Exception {
    return new JSONObject().put("query", query);
  }

  // ---- 状态分离 ----

  @Test
  void reportsExplicitErrorWhenPortMissing() throws Exception {
    ToolResult r =
        new WorkspaceSymbolTool()
            .execute(args("MainActivity"), ToolContext.builder().homePath(".").build());
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("未接入"), r.getContent());
  }

  @Test
  void reportsUnavailableReasonWithGuidance(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.reason = "当前没有打开的项目";
    ToolResult r =
        new WorkspaceSymbolTool().execute(args("MainActivity"), ctx(port, dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("当前没有打开的项目"), r.getContent());
    assertTrue(r.getContent().contains("编辑器中打开"), r.getContent());
  }

  @Test
  void emptyResultIsSuccessNotFailure(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.result = Result.of(Collections.emptyList());
    ToolResult r =
        new WorkspaceSymbolTool().execute(args("zzzNotExist"), ctx(port, dir.getAbsolutePath()));
    assertFalse(r.isError(), "查到但没有结果不是错误");
    assertTrue(r.getContent().contains("没有找到"), r.getContent());
    // 排查线索：区分大小写 + 需完成同步
    assertTrue(r.getContent().contains("区分大小写"), r.getContent());
  }

  @Test
  void failedResultIsError(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.result = Result.failed("项目源码索引为空");
    ToolResult r =
        new WorkspaceSymbolTool().execute(args("MainActivity"), ctx(port, dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("项目源码索引为空"), r.getContent());
  }

  @Test
  void timeoutIsReportedWithRetryHint(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.result = Result.timedOut("搜索超时（超过 20 秒）");
    ToolResult r =
        new WorkspaceSymbolTool().execute(args("MainActivity"), ctx(port, dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("稍后重试"), r.getContent());
  }

  // ---- 参数校验 ----

  @Test
  void rejectsTooShortQuery(@TempDir File dir) throws Exception {
    ToolResult r = new WorkspaceSymbolTool().execute(args("a"), ctx(new FakePort(), dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("至少"), r.getContent());
  }

  @Test
  void rejectsBadKindAndListsLegalValues(@TempDir File dir) throws Exception {
    ToolResult r =
        new WorkspaceSymbolTool()
            .execute(
                args("MainActivity").put("kind", "variable"),
                ctx(new FakePort(), dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("class"), r.getContent());
    assertTrue(r.getContent().contains("field"), r.getContent());
  }

  @Test
  void clampsLimit(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    new WorkspaceSymbolTool()
        .execute(args("MainActivity").put("limit", 9999), ctx(port, dir.getAbsolutePath()));
    assertEquals(200, port.lastLimit, "超过上限应钳到 200");

    FakePort port2 = new FakePort();
    new WorkspaceSymbolTool()
        .execute(args("MainActivity").put("limit", 0), ctx(port2, dir.getAbsolutePath()));
    assertEquals(WorkspaceSymbolTool.DEFAULT_LIMIT, port2.lastLimit, "非正数应回落默认值");
  }

  @Test
  void passesQueryAndTimeoutThrough(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    new WorkspaceSymbolTool()
        .execute(args("parseConfig"), ctx(port, dir.getAbsolutePath()));
    assertEquals("parseConfig", port.lastQuery);
    assertEquals(WorkspaceSymbolTool.TIMEOUT_MS, port.lastTimeout);
    assertNull(port.lastKinds, "未指定 kind 时不过滤");
  }

  @Test
  void kindFilterIsPassedThrough(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    new WorkspaceSymbolTool()
        .execute(args("onCreate").put("kind", "method"), ctx(port, dir.getAbsolutePath()));
    assertNotNull(port.lastKinds);
    assertEquals(1, port.lastKinds.size());
    assertTrue(port.lastKinds.contains(WorkspaceSymbolPort.Kind.METHOD));
  }

  // ---- 格式化与截断 ----

  @Test
  void formatsResultsWithOneBasedPositions(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    // 端口给 0 基 (11, 4) → 展示 1 基 12:5
    port.result =
        Result.of(
            Collections.singletonList(
                new WorkspaceSymbolPort.Symbol(
                    "parseConfig",
                    "com.foo.Bar",
                    WorkspaceSymbolPort.Kind.METHOD,
                    new File(dir, "Bar.java").getAbsolutePath(),
                    11,
                    4)));

    ToolResult r =
        new WorkspaceSymbolTool().execute(args("parseConfig"), ctx(port, dir.getAbsolutePath()));

    assertFalse(r.isError());
    assertTrue(r.getContent().contains("找到 1 个符号"), r.getContent());
    assertTrue(r.getContent().contains("Bar.java:12:5"), r.getContent());
    assertTrue(r.getContent().contains("(方法, com.foo.Bar)"), r.getContent());
  }

  @Test
  void typeOwnSymbolHasNoOwnerSuffix(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.result =
        Result.of(
            Collections.singletonList(
                new WorkspaceSymbolPort.Symbol(
                    "MainActivity",
                    "",
                    WorkspaceSymbolPort.Kind.CLASS,
                    new File(dir, "MainActivity.java").getAbsolutePath(),
                    0,
                    0)));

    ToolResult r =
        new WorkspaceSymbolTool().execute(args("MainActivity"), ctx(port, dir.getAbsolutePath()));

    assertTrue(r.getContent().contains("MainActivity.java:1:1  MainActivity  (类)"), r.getContent());
  }

  @Test
  void truncationIsReported(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.result =
        Result.ofTruncated(
            Arrays.asList(
                new WorkspaceSymbolPort.Symbol(
                    "onCreate",
                    "com.foo.A",
                    WorkspaceSymbolPort.Kind.METHOD,
                    new File(dir, "A.java").getAbsolutePath(),
                    0,
                    0),
                new WorkspaceSymbolPort.Symbol(
                    "onCreate",
                    "com.foo.B",
                    WorkspaceSymbolPort.Kind.METHOD,
                    new File(dir, "B.java").getAbsolutePath(),
                    1,
                    0)),
            "已达 2 条上限，可能还有更多");

    ToolResult r =
        new WorkspaceSymbolTool().execute(args("onCreate"), ctx(port, dir.getAbsolutePath()));

    assertFalse(r.isError());
    assertTrue(r.getContent().contains("可能还有更多"), r.getContent());
    assertTrue(r.getContent().contains("缩小 kind"), r.getContent());
  }

  // ---- 元数据 ----

  @Test
  void metadataIsConsistent() throws Exception {
    WorkspaceSymbolTool tool = new WorkspaceSymbolTool();
    assertEquals(ToolNames.WORKSPACE_SYMBOL, tool.getName());
    assertFalse(tool.getDescription().isEmpty());
    assertTrue(tool.isAllowedInReadonlyMode(), "只读查询必须在只读模式放行");
    assertFalse(tool.isConcurrencySafe(), "静态 parse 缓存决定它不能并发");
    assertTrue(tool.getParameters().getJSONArray("required").toList().contains("query"));
  }
}
