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
 * 锁定 {@link CodeGraphTool}：命令拼装、参数校验、错误诊断。
 *
 * <p>这个工具的存在意义就是让助手不必靠 {@code shell_execute} 手敲命令，所以测试重点在
 * 「拼出的命令行对不对」与「失败时给的是可操作提示而不是裸退出码」。
 */
final class CodeGraphToolTest {

  /** 记录收到的命令，返回预设结果。 */
  private static final class FakeBackend implements ShellBackend {
    final List<ShellRequest> requests = new ArrayList<>();
    private final ShellRequest.ShellResult result;

    FakeBackend(ShellRequest.ShellResult result) {
      this.result = result;
    }

    @Override
    public String id() {
      return "fake";
    }

    @Override
    public String displayName() {
      return "假后端";
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
    public ShellRequest.ShellResult execute(ShellRequest request, ShellRequest.ShellOutputSink sink) {
      requests.add(request);
      return result;
    }
  }

  private static ShellRequest.ShellResult ok(String out) {
    return ShellRequest.ShellResult.success(out);
  }

  /** 构造一个非零退出的结果（exitCode + stderr）。 */
  private static ShellRequest.ShellResult fail(int exitCode, String stderr) {
    return new ShellRequest.ShellResult(exitCode, "", stderr, false, false);
  }

  private static ToolContext context(String home) {
    return ToolContext.builder().homePath(home).build();
  }

  private static ToolResult run(
      FakeBackend backend, ToolContext ctx, JSONObject args) {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(backend);
    // 注入 wrapper 绝对路径（真机上 shell 后端不设 PATH，裸名会 command not found）。
    CodeGraphTool.setAvailabilityProbe(
        new CodeGraphTool.AvailabilityProbe() {
          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public String wrapperPath() {
            return "/data/data/pkg/files/usr/bin/acs-codegraph";
          }
        });
    return new CodeGraphTool(registry).execute(args, ctx);
  }

  private static JSONObject args(String action, String query) throws Exception {
    JSONObject o = new JSONObject();
    if (action != null) o.put("action", action);
    if (query != null) o.put("query", query);
    return o;
  }

  @Test
  void buildsQueryCommandWithActionAndSymbol() throws Exception {
    FakeBackend backend = new FakeBackend(ok("Foo.java:12 class Foo"));
    ToolResult r =
        run(backend, context("/proj"), args("query", "Foo"));

    assertFalse(r.isError(), "应成功: " + r.getContent());
    assertEquals(1, backend.requests.size());
    ShellRequest req = backend.requests.get(0);
    assertEquals("'/data/data/pkg/files/usr/bin/acs-codegraph' query 'Foo'", req.getCommand());
    assertEquals("/proj", req.getCwd(), "工作目录应为项目根，codegraph 由此向上找 .codegraph/");
  }

  @Test
  void defaultsToExploreWhenActionMissing() throws Exception {
    FakeBackend backend = new FakeBackend(ok("..."));
    run(backend, context("/proj"), args(null, "用户登录流程"));

    assertEquals(
        "'/data/data/pkg/files/usr/bin/acs-codegraph' explore '用户登录流程'",
        backend.requests.get(0).getCommand(),
        "explore 是最常用的 action，缺省应选它");
  }

  @Test
  void statusAndFilesNeedNoQuery() throws Exception {
    FakeBackend backend = new FakeBackend(ok("indexed: 1234 nodes"));
    ToolResult r = run(backend, context("/proj"), args("status", null));

    assertFalse(r.isError(), "status 不需要 query: " + r.getContent());
    assertEquals("'/data/data/pkg/files/usr/bin/acs-codegraph' status", backend.requests.get(0).getCommand());
  }

  @Test
  void rejectsUnknownAction() throws Exception {
    FakeBackend backend = new FakeBackend(ok(""));
    ToolResult r = run(backend, context("/proj"), args("rm -rf", "x"));

    assertTrue(r.isError(), "未知 action 应被拒绝");
    assertEquals(0, backend.requests.size(), "非法 action 不应真的执行命令");
  }

  @Test
  void rejectsMissingQueryForQueryActions() throws Exception {
    FakeBackend backend = new FakeBackend(ok(""));
    ToolResult r = run(backend, context("/proj"), args("query", ""));

    assertTrue(r.isError(), "query 缺 query 参数应被拒绝");
    assertEquals(0, backend.requests.size());
  }

  @Test
  void singleQuotesInQueryAreEscaped() throws Exception {
    FakeBackend backend = new FakeBackend(ok("..."));
    run(backend, context("/proj"), args("query", "it's"));

    // 单引号必须被转义，否则会提前闭合引号、把后半段当成新命令（注入）。
    assertEquals(
        "'/data/data/pkg/files/usr/bin/acs-codegraph' query 'it'\\''s'",
        backend.requests.get(0).getCommand());
  }

  @Test
  void notInstalledErrorPointsToSettings() throws Exception {
    FakeBackend backend = new FakeBackend(fail(127, "acs-codegraph: not found"));
    ToolResult r = run(backend, context("/proj"), args("query", "Foo"));

    assertTrue(r.isError());
    assertTrue(
        r.getContent().contains("设置"),
        "找不到命令时应指向设置页，而不是只丢退出码: " + r.getContent());
  }

  @Test
  void missingIndexErrorSuggestsInitializing() throws Exception {
    FakeBackend backend = new FakeBackend(fail(1, "CodeGraph not initialized: no .codegraph"));
    ToolResult r = run(backend, context("/proj"), args("query", "Foo"));

    assertTrue(r.isError());
    assertTrue(
        r.getContent().contains("初始化索引"),
        "索引缺失时应提示初始化: " + r.getContent());
  }

  @Test
  void emptyResultIsReportedClearly() throws Exception {
    FakeBackend backend = new FakeBackend(ok("   \n"));
    ToolResult r = run(backend, context("/proj"), args("query", "NoSuchSymbol"));

    assertFalse(r.isError(), "空结果不是错误");
    assertTrue(r.getContent().contains("无结果"), "应说明无结果: " + r.getContent());
  }

  @Test
  void toolIsReadOnlyAndConcurrencySafe() {
    CodeGraphTool tool = new CodeGraphTool(new ShellBackendRegistry());
    assertTrue(tool.isAllowedInReadonlyMode(), "只读查询应允许在只读模式使用");
    assertTrue(tool.isConcurrencySafe(), "多个查询互不依赖，可并发");
    assertFalse(tool.needsConfirmation(), "只读查询无需逐次确认");
    assertEquals("codegraph", tool.getName());
  }
}
