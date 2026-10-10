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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.CodeFormatPort.Result;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FormatCodeTool}：降级、类型过滤、以及「无需改动」必须算成功。 */
class FormatCodeToolTest {

  private static final class FakePort implements CodeFormatPort {
    Result result = Result.done(true, 10);
    boolean available = true;
    String reason = "";

    @Override
    public Result format(String absolutePath, long timeoutMs) {
      return result;
    }

    @Override
    public boolean isSupported(String filePath) {
      return filePath.endsWith(".java") || filePath.endsWith(".kt");
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return reason;
    }
  }

  private static ToolContext ctx(CodeFormatPort port, String home) {
    return ToolContext.builder().homePath(home).codeFormat(port).build();
  }

  private static File javaFile(File dir) throws Exception {
    File f = new File(dir, "A.java");
    Files.write(f.toPath(), "class A{}".getBytes(StandardCharsets.UTF_8));
    return f;
  }

  private static JSONObject args(String path) throws Exception {
    return new JSONObject().put("file_path", path);
  }

  @Test
  void reportsErrorWhenPortMissing() throws Exception {
    ToolResult r =
        new FormatCodeTool()
            .execute(new JSONObject().put("file_path", "A.java"),
                ToolContext.builder().homePath(".").build());
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("未接入"), r.getContent());
  }

  @Test
  void reportsUnavailable(@TempDir File dir) throws Exception {
    javaFile(dir);
    FakePort port = new FakePort();
    port.available = false;
    port.reason = "当前没有打开的项目";
    ToolResult r = new FormatCodeTool().execute(args("A.java"), ctx(port, dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("没有打开的项目"), r.getContent());
  }

  @Test
  void noChangeIsSuccessNotFailure(@TempDir File dir) throws Exception {
    javaFile(dir);
    FakePort port = new FakePort();
    port.result = Result.done(false, 0);
    ToolResult r = new FormatCodeTool().execute(args("A.java"), ctx(port, dir.getAbsolutePath()));
    assertFalse(r.isError(), "已符合规范不是错误");
    assertTrue(r.getContent().contains("无需改动"), r.getContent());
  }

  @Test
  void reportsChangedChars(@TempDir File dir) throws Exception {
    javaFile(dir);
    FakePort port = new FakePort();
    port.result = Result.done(true, 42);
    ToolResult r = new FormatCodeTool().execute(args("A.java"), ctx(port, dir.getAbsolutePath()));
    assertFalse(r.isError());
    assertTrue(r.getContent().contains("42"), r.getContent());
  }

  @Test
  void timeoutMentionsRetry(@TempDir File dir) throws Exception {
    javaFile(dir);
    FakePort port = new FakePort();
    port.result = Result.timedOut("格式化超时（超过 20 秒）");
    ToolResult r = new FormatCodeTool().execute(args("A.java"), ctx(port, dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("稍后重试"), r.getContent());
  }

  @Test
  void rejectsUnsupportedExtension(@TempDir File dir) throws Exception {
    File xml = new File(dir, "x.xml");
    Files.write(xml.toPath(), "<x/>".getBytes(StandardCharsets.UTF_8));
    ToolResult r = new FormatCodeTool().execute(args("x.xml"), ctx(new FakePort(), dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("没有格式化器"), r.getContent());
  }
}
