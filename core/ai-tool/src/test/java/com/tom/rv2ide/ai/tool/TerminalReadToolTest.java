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
 * 终端读取工具的行为。
 *
 * <p><b>为什么需要这组测试</b>：终端读取的价值在于「AI 能看到用户手动跑的东西」。
 * 若端口未接入时静默返回空列表，模型会把它误读成「终端是空的」——而真相是「读不了」。
 * 这里钉住：未接入必须报错，无会话才返回「没有会话」，有会话时能按序号取内容。
 */
final class TerminalReadToolTest {

  private static final class FakePort implements TerminalPort {
    final List<Session> list = new ArrayList<>();
    boolean available = true;

    @Override
    public List<Session> sessions() {
      return list;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return available ? "" : "测试用：不可用";
    }
  }

  private static ToolContext ctx(TerminalPort port) {
    return ToolContext.builder()
        .homePath(System.getProperty("java.io.tmpdir"))
        .terminal(port)
        .build();
  }

  @Test
  void missingPortFailsLoudlyInsteadOfReturningEmpty() {
    // 端口未接入时若返回「没有会话」，模型会把「读不了」误读成「终端是空的」。
    ToolResult result =
        new TerminalReadTool().execute(new JSONObject(), ctx(null));
    assertTrue(result.isError(), "端口缺失应报错");
    assertTrue(result.getContent().contains("未接入"), result.getContent());
  }

  @Test
  void listsSessionsWhenNoIndexGiven() {
    FakePort port = new FakePort();
    port.list.add(new TerminalPort.Session(0, "bash", true, "/data/home", "$ ls\nfile.txt\n"));
    port.list.add(new TerminalPort.Session(1, "python", false, "", ""));

    ToolResult result = new TerminalReadTool().execute(new JSONObject(), ctx(port));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("[0]"), result.getContent());
    assertTrue(result.getContent().contains("bash"), result.getContent());
    assertTrue(result.getContent().contains("运行中"), result.getContent());
    assertTrue(result.getContent().contains("[1]"), result.getContent());
    // 只列清单时不应把屏幕内容也倒出来。
    assertFalse(result.getContent().contains("file.txt"), result.getContent());
  }

  @Test
  void readsTranscriptByIndex() {
    FakePort port = new FakePort();
    port.list.add(new TerminalPort.Session(0, "bash", true, "/home", "$ gradle build\nBUILD FAILED\n"));

    ToolResult result =
        new TerminalReadTool().execute(new JSONObject().put("session", 0), ctx(port));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("BUILD FAILED"), result.getContent());
  }

  @Test
  void unknownIndexIsAnError() {
    FakePort port = new FakePort();
    port.list.add(new TerminalPort.Session(0, "bash", true, "", "x"));

    ToolResult result =
        new TerminalReadTool().execute(new JSONObject().put("session", 9), ctx(port));

    assertTrue(result.isError(), "未知序号应报错");
    assertTrue(result.getContent().contains("没有序号"), result.getContent());
  }

  @Test
  void noSessionsIsNotAnError() {
    ToolResult result = new TerminalReadTool().execute(new JSONObject(), ctx(new FakePort()));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("没有终端会话"), result.getContent());
  }

  @Test
  void transcriptIsTruncatedInTheMiddle() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 40_000; i++) {
      big.append('x');
    }
    String truncated = TerminalReadTool.truncate(big.toString());
    assertTrue(truncated.length() < big.length(), "超长应被截断");
    assertTrue(truncated.contains("省略"), truncated.substring(0, 200));
  }

  // ---- 此前未覆盖的分支 ----

  @Test
  void unavailablePortExplainsReason() {
    FakePort port = new FakePort();
    port.available = false;
    ToolResult result = new TerminalReadTool().execute(new JSONObject(), ctx(port));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("测试用：不可用"), result.getContent());
  }

  @Test
  void emptyTranscriptIsStatedExplicitly() {
    FakePort port = new FakePort();
    port.list.add(new TerminalPort.Session(0, "bash", true, "", "   "));
    ToolResult result =
        new TerminalReadTool().execute(new JSONObject().put("session", 0), ctx(port));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("屏幕为空"), result.getContent());
  }

  @Test
  void nullSessionParamListsSessions() {
    // session 显式传 null（JSON null）与省略等价——只列清单。
    FakePort port = new FakePort();
    port.list.add(new TerminalPort.Session(0, "bash", true, "", "content"));
    ToolResult result = new TerminalReadTool()
        .execute(new JSONObject().put("session", JSONObject.NULL), ctx(port));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("[0]"), result.getContent());
    assertFalse(result.getContent().contains("content"), "不应倒出屏幕内容");
  }

  @Test
  void unnamedSessionShowsPlaceholder() {
    FakePort port = new FakePort();
    port.list.add(new TerminalPort.Session(0, "", true, "", ""));
    ToolResult result = new TerminalReadTool().execute(new JSONObject(), ctx(port));
    assertTrue(result.getContent().contains("(未命名)"), result.getContent());
  }

  @Test
  void metadataAndConcurrency() {
    TerminalReadTool tool = new TerminalReadTool();
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.SHELL, tool.getDisplayCategory());
    assertTrue(tool.isConcurrencySafe(), "读终端是只读操作，可并发");
  }
}
