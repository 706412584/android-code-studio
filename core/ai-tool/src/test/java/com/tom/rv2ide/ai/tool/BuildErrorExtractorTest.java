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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 构建错误提取的回归测试。
 *
 * <p>这个启发式一旦静默退化（如 Gradle 改标记导致总是走兜底分支），模型侧的表现就是
 * 「又只能盲猜了」——很难从行为上察觉。因此把分支锁定在测试里。
 */
final class BuildErrorExtractorTest {

  @Test
  void emptyInputYieldsEmptyString() {
    assertEquals("", BuildErrorExtractor.extract(null));
    assertEquals("", BuildErrorExtractor.extract(new ArrayList<>()));
  }

  @Test
  void startsFromLastFailureMarker() {
    List<String> log =
        List.of(
            "> Task :app:preBuild",
            "FAILURE: Build failed with an exception.",
            "> Task :app:compileDebugJavaWithJavac",
            "MainActivity.java:23: error: cannot find symbol",
            "  symbol: class SomeUndefinedType",
            "* Try: Run with --stacktrace",
            "BUILD FAILED in 34s");

    String out = BuildErrorExtractor.extract(log);

    // 必须从 FAILURE: 起，且包含真正的编译错误行
    assertTrue(out.contains("FAILURE: Build failed"), out);
    assertTrue(out.contains("MainActivity.java:23: error: cannot find symbol"), out);
    assertTrue(out.contains("symbol: class SomeUndefinedType"), out);
    // FAILURE: 之前的任务进度不应出现（那正是要被裁掉的部分）
    assertFalse(out.contains("> Task :app:preBuild"), out);
  }

  @Test
  void usesLastFailureMarkerWhenSeveralExist() {
    List<String> log =
        List.of(
            "FAILURE: old failure from previous build",
            "stale error line",
            "> Task :app:assembleDebug",
            "FAILURE: Build failed with an exception.",
            "current error line");

    String out = BuildErrorExtractor.extract(log);

    assertTrue(out.contains("current error line"), out);
    assertFalse(out.contains("stale error line"), "应取最后一次 FAILURE:，而非历史失败");
  }

  @Test
  void fallsBackToTailWhenNoFailureMarker() {
    List<String> log = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      log.add("line-" + i);
    }

    String out = BuildErrorExtractor.extract(log, 0, 10);

    assertTrue(out.contains("末尾 10 行"), out);
    assertTrue(out.contains("line-199"), out);
    assertFalse(out.contains("line-150"), "兜底时只应保留尾部若干行");
  }

  @Test
  void truncatesOverlongOutput() {
    List<String> log = new ArrayList<>();
    log.add("FAILURE: Build failed with an exception.");
    for (int i = 0; i < 500; i++) {
      log.add("very long error line number " + i + " " + "x".repeat(50));
    }

    String out = BuildErrorExtractor.extract(log, 500, 0);

    assertTrue(out.contains("已截断"), "超长输出必须截断，避免撑爆上下文");
    assertTrue(out.length() < 2000, "截断后长度应受控，实际 " + out.length());
  }

  @Test
  void toleratesNullLines() {
    List<String> log = new ArrayList<>();
    log.add(null);
    log.add("FAILURE: Build failed with an exception.");
    log.add(null);
    log.add("real error");

    String out = BuildErrorExtractor.extract(log);

    assertTrue(out.contains("real error"), out);
  }
}
