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

package com.tom.rv2ide.cloudbuild;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 日志提取的回归测试。
 *
 * <p>构造真实的 zip（GitHub 的日志就是 zip），验证错误行识别与上下文合并——
 * 这是「看到失败原因」功能的核心逻辑，值得用真实文件而不是 mock 验证。
 */
public class RunLogExtractorTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  /** 构造一个日志 zip：条目名与 GitHub 的形态一致（job 名/步骤.txt）。 */
  private File buildLogZip(String... lines) throws Exception {
    File zip = tempFolder.newFile("logs.zip");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
      out.putNextEntry(new ZipEntry("build/1_Build.txt"));
      StringBuilder content = new StringBuilder();
      for (String line : lines) {
        content.append(line).append('\n');
      }
      out.write(content.toString().getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return zip;
  }

  @Test
  public void extractsErrorLineWithContext() throws Exception {
    File zip = buildLogZip(
        "line before 1",
        "line before 2",
        "line before 3",
        "FAILURE: Build failed with an exception.",
        "line after 1",
        "line after 2",
        "line after 3",
        "line after 4");
    RunLogExtractor.ExtractResult result = RunLogExtractor.extract(zip);
    assertTrue("应找到错误行", result.foundError);
    assertTrue("应包含错误行本身", result.text.contains("FAILURE: Build failed"));
    assertTrue("应包含上文", result.text.contains("line before 1"));
    assertTrue("应包含下文", result.text.contains("line after 1"));
  }

  @Test
  public void mergesOverlappingContextRanges() throws Exception {
    // 两个错误行相隔 2 行（上下文各 3 行会重叠）——应合并为一段而不是切碎。
    File zip = buildLogZip(
        "ERROR: first",
        "middle 1",
        "middle 2",
        "BUILD FAILED",
        "after 1",
        "after 2",
        "after 3");
    RunLogExtractor.ExtractResult result = RunLogExtractor.extract(zip);
    assertTrue(result.foundError);
    assertTrue(result.text.contains("ERROR: first"));
    assertTrue(result.text.contains("BUILD FAILED"));
    assertTrue("重叠区间不应出现省略号", !result.text.contains("…"));
  }

  @Test
  public void reportsNoErrorWhenClean() throws Exception {
    File zip = buildLogZip("Step 1 done", "Step 2 done", "All good");
    RunLogExtractor.ExtractResult result = RunLogExtractor.extract(zip);
    assertFalse("干净日志不应报错误", result.foundError);
  }

  @Test
  public void handlesMissingFile() {
    RunLogExtractor.ExtractResult result = RunLogExtractor.extract(new File("/nonexistent.zip"));
    assertFalse(result.foundError);
    assertTrue("应给出说明而不是抛异常", result.text.contains("不存在"));
  }

  @Test
  public void recognizesCommonErrorPatterns() {
    assertTrue(RunLogExtractor.isErrorLine("##[error]Process completed with exit code 1."));
    assertTrue(RunLogExtractor.isErrorLine("FAILURE: Build failed with an exception."));
    assertTrue(RunLogExtractor.isErrorLine("> Task :app:compileDebugKotlin FAILED"));
    assertTrue(RunLogExtractor.isErrorLine("e: file.kt:12:3 Unresolved reference"));
    assertTrue(RunLogExtractor.isErrorLine("java.lang.NullPointerException"));
    assertTrue(RunLogExtractor.isErrorLine("Caused by: java.io.IOException"));
    assertTrue(RunLogExtractor.isErrorLine("npm ERR! code ELIFECYCLE"));
    assertTrue(RunLogExtractor.isErrorLine("ERROR: something"));
    assertTrue(RunLogExtractor.isErrorLine("Error: something"));

    assertFalse(RunLogExtractor.isErrorLine("Successfully built"));
    assertFalse(RunLogExtractor.isErrorLine(""));
    assertFalse(RunLogExtractor.isErrorLine(null));
    // 普通文本里出现 error 这个词不算错误行（避免误报把日志淹没）。
    assertFalse(RunLogExtractor.isErrorLine("Handling the error case gracefully"));
  }
}
