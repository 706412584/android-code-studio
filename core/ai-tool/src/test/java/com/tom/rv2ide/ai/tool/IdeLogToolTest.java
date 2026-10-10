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

import com.tom.rv2ide.ai.tool.IdeLogSource.Entry;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * {@link IdeLogTool} 的行为：级别/关键字过滤、降级、堆栈缩进与截断。
 */
class IdeLogToolTest {

  /** 按过滤条件回放的假日志源；记录收到的参数。 */
  private static final class FakeSource implements IdeLogSource {
    final List<IdeLogSource.Entry> entries = new ArrayList<>();
    boolean available = true;
    String reason = "";
    int lastLimit;
    int lastMinLevel;
    String lastLogger;
    String lastText;

    @Override
    public List<Entry> read(int limit, int minLevel, String loggerKeyword, String textKeyword) {
      lastLimit = limit;
      lastMinLevel = minLevel;
      lastLogger = loggerKeyword;
      lastText = textKeyword;
      List<Entry> result = new ArrayList<>();
      String lf = loggerKeyword == null ? "" : loggerKeyword.toLowerCase(Locale.US);
      String tf = textKeyword == null ? "" : textKeyword.toLowerCase(Locale.US);
      for (Entry e : entries) {
        if (e.getLevel() > minLevel) {
          continue;
        }
        if (!lf.isEmpty() && !e.getLogger().toLowerCase(Locale.US).contains(lf)) {
          continue;
        }
        if (!tf.isEmpty() && !e.getMessage().toLowerCase(Locale.US).contains(tf)) {
          continue;
        }
        result.add(e);
      }
      return result;
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

  private static Entry entry(int level, String logger, String message) {
    return new Entry(1_700_000_000_000L, level, logger, message, "");
  }

  private static ToolContext contextWith(IdeLogSource source) {
    return ToolContext.builder().homePath(".").ideLog(source).build();
  }

  @Test
  void reportsExplicitErrorWhenSourceMissing() throws Exception {
    ToolResult result =
        new IdeLogTool().execute(new JSONObject(), ToolContext.builder().homePath(".").build());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("日志源"));
  }

  @Test
  void reportsUnavailableReason() throws Exception {
    FakeSource source = new FakeSource();
    source.available = false;
    source.reason = "日志缓冲尚未初始化";
    ToolResult result = new IdeLogTool().execute(new JSONObject(), contextWith(source));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("尚未初始化"));
  }

  @Test
  void formatsEntriesWithLevelAndLogger() throws Exception {
    FakeSource source = new FakeSource();
    source.entries.add(entry(IdeLogSource.LEVEL_ERROR, "com.tom.rv2ide.ai.Protocol", "boom"));

    ToolResult result = new IdeLogTool().execute(new JSONObject(), contextWith(source));

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("ERROR"), result.getContent());
    assertTrue(result.getContent().contains("com.tom.rv2ide.ai.Protocol"), result.getContent());
    assertTrue(result.getContent().contains("boom"), result.getContent());
  }

  @Test
  void defaultMinLevelIsWarnAndPassedThrough() throws Exception {
    FakeSource source = new FakeSource();
    source.entries.add(entry(IdeLogSource.LEVEL_INFO, "ai", "noise"));

    ToolResult result = new IdeLogTool().execute(new JSONObject(), contextWith(source));

    assertEquals(IdeLogSource.LEVEL_WARN, source.lastMinLevel);
    // INFO 低于默认 warn，被过滤掉
    assertFalse(result.getContent().contains("noise"));
    assertTrue(result.getContent().contains("没有匹配的日志"), result.getContent());
  }

  @Test
  void levelInfoIncludesInfoEntries() throws Exception {
    FakeSource source = new FakeSource();
    source.entries.add(entry(IdeLogSource.LEVEL_INFO, "ai", "hello"));

    ToolResult result =
        new IdeLogTool().execute(new JSONObject().put("level", "info"), contextWith(source));

    assertEquals(IdeLogSource.LEVEL_INFO, source.lastMinLevel);
    assertTrue(result.getContent().contains("hello"), result.getContent());
  }

  @Test
  void passesLoggerAndTextFiltersThrough() throws Exception {
    FakeSource source = new FakeSource();
    new IdeLogTool()
        .execute(
            new JSONObject().put("logger", "Protocol").put("filter", "timeout"),
            contextWith(source));
    assertEquals("Protocol", source.lastLogger);
    assertEquals("timeout", source.lastText);
  }

  @Test
  void clampsLimitToMax() throws Exception {
    FakeSource source = new FakeSource();
    new IdeLogTool().execute(new JSONObject().put("lines", 99999), contextWith(source));
    assertEquals(IdeLogTool.MAX_LIMIT, source.lastLimit);
  }

  @Test
  void includesIndentedThrowable() throws Exception {
    FakeSource source = new FakeSource();
    source.entries.add(
        new Entry(
            1_700_000_000_000L,
            IdeLogSource.LEVEL_ERROR,
            "ai",
            "failed",
            "java.lang.IllegalStateException: bad\n\tat a.B.c(B.java:1)"));

    ToolResult result = new IdeLogTool().execute(new JSONObject(), contextWith(source));

    assertTrue(result.getContent().contains("    java.lang.IllegalStateException"), result.getContent());
    assertTrue(result.getContent().contains("    \tat a.B.c"), result.getContent());
  }

  @Test
  void throwableIsTruncatedWhenHuge() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      sb.append("frame ").append(i).append('\n');
    }
    String out = IdeLogTool.indentThrowable(sb.toString());
    assertTrue(out.contains("堆栈已截断"), out);
  }

  @Test
  void parsesLevelNamesCaseInsensitively() {
    assertEquals(IdeLogSource.LEVEL_ERROR, IdeLogTool.parseLevel("Error"));
    assertEquals(IdeLogSource.LEVEL_ERROR, IdeLogTool.parseLevel(" e "));
    assertEquals(IdeLogSource.LEVEL_WARN, IdeLogTool.parseLevel("warn"));
    assertEquals(IdeLogSource.LEVEL_INFO, IdeLogTool.parseLevel("INFO"));
    assertEquals(IdeLogSource.LEVEL_DEBUG, IdeLogTool.parseLevel("debug"));
    assertEquals(IdeLogSource.LEVEL_TRACE, IdeLogTool.parseLevel("trace"));
    assertEquals(IdeLogSource.LEVEL_WARN, IdeLogTool.parseLevel("garbage"));
  }

  @Test
  void loggerFilterMatchesSubstringIgnoringCase() throws Exception {
    FakeSource source = new FakeSource();
    source.entries.addAll(
        Arrays.asList(
            entry(IdeLogSource.LEVEL_ERROR, "com.tom.rv2ide.ai.Protocol", "p"),
            entry(IdeLogSource.LEVEL_ERROR, "com.tom.rv2ide.ui.Editor", "e")));

    ToolResult result =
        new IdeLogTool()
            .execute(new JSONObject().put("logger", "protocol"), contextWith(source));

    assertTrue(result.getContent().contains("Protocol"), result.getContent());
    assertFalse(result.getContent().contains("Editor"), result.getContent());
  }
}
