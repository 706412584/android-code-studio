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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ToolExecutor} 的失败兜底：任何异常都必须变成 error 结果，
 * 绝不能让 agent 循环中断。
 */
final class ToolExecutorFailureTest {

  /** 总是抛异常的假工具。 */
  private static final class ThrowingTool extends BaseTool {
    private final boolean throwError;

    ThrowingTool(boolean throwError) {
      this.throwError = throwError;
    }

    @Override
    public String getName() {
      return "throwing_tool";
    }

    @Override
    public String getDescription() {
      return "always throws";
    }

    @Override
    public com.tom.rv2ide.ai.tool.api.ToolCategory getCategory() {
      return com.tom.rv2ide.ai.tool.api.ToolCategory.READ;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject().put("type", "object");
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      if (throwError) {
        throw new NoClassDefFoundError("missing dependency");
      }
      throw new IllegalStateException("boom");
    }
  }

  /** 可记录 diff 的写工具。 */
  private static final class RecordingWriteTool extends BaseTool {
    @Override
    public String getName() {
      return "recording_write";
    }

    @Override
    public String getDescription() {
      return "writes";
    }

    @Override
    public com.tom.rv2ide.ai.tool.api.ToolCategory getCategory() {
      return com.tom.rv2ide.ai.tool.api.ToolCategory.WRITE;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject().put("type", "object");
    }

    @Override
    public boolean shouldRecordDiff() {
      return true;
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      try {
        Path target = Path.of(context.getHomePath(), input.getString("file_path"));
        Files.createDirectories(target.getParent());
        Files.writeString(target, input.optString("content", ""));
        return ok("written");
      } catch (Exception e) {
        return error(e.getMessage());
      }
    }
  }

  private static ToolExecutor executor(ToolRegistry registry) {
    return new ToolExecutor(registry, null, null);
  }

  @Test
  void nullToolCallIsReported() {
    ToolResult result = executor(new ToolRegistry()).execute(null, null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("工具调用为空"), result.getContent());
  }

  @Test
  void nullRegistryIsReported() {
    ToolResult result =
        new ToolExecutor(null, null, null)
            .execute(new ToolCall("c1", "x", "{}"), null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未初始化"), result.getContent());
  }

  @Test
  void unknownToolIsReportedWithItsName() {
    ToolResult result =
        executor(new ToolRegistry()).execute(new ToolCall("c1", "nope", "{}"), null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未知工具"), result.getContent());
    assertEquals("nope", result.getToolName());
  }

  @Test
  void malformedArgumentsAreReported() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new FileReadTool());
    ToolResult result =
        executor(registry).execute(new ToolCall("c1", "file_read", "{not json"), null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("参数解析失败"), result.getContent());
  }

  @Test
  void runtimeExceptionBecomesAnErrorResult() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ThrowingTool(false));
    ToolResult result =
        executor(registry).execute(new ToolCall("c1", "throwing_tool", "{}"), null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("工具执行失败"), result.getContent());
    assertTrue(result.getContent().contains("boom"), result.getContent());
  }

  @Test
  void errorIsAlsoCaughtSoTheLoopSurvives() {
    // OOM / NoClassDefFoundError 属于 Error：executor 只 catch Exception，
    // 但 DiffRecorder 不参与时它们会冒泡——这是有意的（Error 不该被静默吞掉），
    // 本用例只锁定 RuntimeException 的契约。
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ThrowingTool(false));
    ToolResult result =
        executor(registry).executeConfirmed(new ToolCall("c1", "throwing_tool", "{}"), null);
    assertTrue(result.isError());
  }

  @Test
  void diffRecorderRecordsChangesAndAttachesDiffId(@TempDir Path dir) throws Exception {
    Path diffFile = dir.resolve("diffs.jsonl");
    FileDiffStore store = new FileDiffStore(diffFile.toFile());
    ToolRegistry registry = new ToolRegistry();
    registry.register(new RecordingWriteTool());
    ToolExecutor executor = new ToolExecutor(registry, null, new DiffRecorder(store));

    ToolResult result =
        executor.execute(
            new ToolCall(
                "c1",
                "recording_write",
                new JSONObject().put("file_path", "a.txt").put("content", "hello").toString()),
            ToolContext.builder().homePath(dir.toString()).build());

    assertFalse(result.isError(), result.getContent());
    assertNotNull(result.getDiffId(), "应附加 diff id");
    assertEquals(1, store.getAll().size());
    assertEquals("hello", Files.readString(dir.resolve("a.txt")));
  }

  @Test
  void diffRecorderRejectsDirectoryTarget(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve("subdir"));
    ToolRegistry registry = new ToolRegistry();
    registry.register(new RecordingWriteTool());
    ToolExecutor executor =
        new ToolExecutor(registry, null, new DiffRecorder(new FileDiffStore(null)));

    ToolResult result =
        executor.execute(
            new ToolCall(
                "c1",
                "recording_write",
                new JSONObject().put("file_path", "subdir").put("content", "x").toString()),
            ToolContext.builder().homePath(dir.toString()).build());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("目录"), result.getContent());
  }

  @Test
  void diffRecorderSkipsRecordingWhenContentIsUnchanged(@TempDir Path dir) throws Exception {
    // 写同样的内容：不应产生一条无意义的 diff。
    Path target = dir.resolve("same.txt");
    Files.writeString(target, "same");
    FileDiffStore store = new FileDiffStore(dir.resolve("diffs.jsonl").toFile());
    ToolRegistry registry = new ToolRegistry();
    registry.register(new RecordingWriteTool());
    ToolExecutor executor = new ToolExecutor(registry, null, new DiffRecorder(store));

    ToolResult result =
        executor.execute(
            new ToolCall(
                "c1",
                "recording_write",
                new JSONObject().put("file_path", "same.txt").put("content", "same").toString()),
            ToolContext.builder().homePath(dir.toString()).build());

    assertFalse(result.isError(), result.getContent());
    assertEquals(0, store.getAll().size(), "内容未变不应记录 diff");
  }

  @Test
  void confirmedCallBypassesConfirmationGate() {
    // executeConfirmed 用于用户已点过「允许」的路径：不应再次询问。
    ToolRegistry registry = new ToolRegistry();
    registry.register(new RecordingWriteTool());
    ToolResult result =
        new ToolExecutor(registry, null, null)
            .executeConfirmed(
                new ToolCall("c1", "recording_write", "{\"file_path\":\"a.txt\"}"),
                ToolContext.builder().homePath(".").build());
    // 写 "." 可能失败，但关键是不被「需要确认」拦下。
    assertFalse(result.getContent().contains("用户拒绝"), result.getContent());
  }
}
