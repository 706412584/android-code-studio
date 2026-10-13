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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 工具 JSON Schema 形态的静态守卫。
 *
 * <p><b>为什么需要这个测试</b>：{@code CodeActionsTool} 曾把 {@code required} 嵌进
 * {@code properties} 内部（而不是与它同级）。这在多数 provider 上无害（它们不校验
 * schema），但在严格校验的 provider（实测 agnes）上会以 400 拒绝**整个 tools 数组**
 * ——不是那一个工具不可用，而是**所有工具**全部失效，agent 直接退化为纯文本对话。
 *
 * <p>这类 bug 的代价与发现难度完全不成比例：一行缩进错误，表现为「AI 完全不能
 * 用工具」，而且只有特定 provider 才暴露。因此用静态扫描把规则钉死——扫描源码
 * 而不是实例化工具，是因为本模块的工具只是全量的一部分（另有 app 层的工具），
 * 而这条规则对**任何**工具都成立。
 *
 * <p>判定规则：{@code .put("required", ...)} 的缩进不得深于同一链上最近的
 * {@code "properties"}——深了就说明它被写进了 properties 对象内部。
 */
class ToolSchemaShapeTest {

  /** 从测试运行时的工作目录向上找到仓库根（含 core/ 目录的那一级）。 */
  private static File repoRoot() {
    File dir = new File(".").getAbsoluteFile();
    while (dir != null) {
      if (new File(dir, "core/ai-tool").isDirectory()) {
        return dir;
      }
      dir = dir.getParentFile();
    }
    return new File(".").getAbsoluteFile();
  }

  @Test
  public void requiredIsNeverNestedInsideProperties() throws IOException {
    File root = repoRoot();
    List<File> sources = new ArrayList<>();
    collect(new File(root, "core/ai-tool/src/main/java"), sources);
    collect(new File(root, "core/app/src/main/java"), sources);

    assertFalse(sources.isEmpty(), "未找到任何工具源码，测试的搜索路径可能失效");

    List<String> offenders = new ArrayList<>();
    int checked = 0;
    for (File file : sources) {
      String src = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      if (!src.contains("\"required\"")) {
        continue;
      }
      checked++;
      Matcher matcher = Pattern.compile("\\.put\\(\\s*\"required\"").matcher(src);
      while (matcher.find()) {
        int lineStart = src.lastIndexOf('\n', matcher.start()) + 1;
        int indent = matcher.start() - lineStart;
        int properties = src.lastIndexOf("\"properties\"", matcher.start());
        if (properties < 0) {
          continue;
        }
        int propLineStart = src.lastIndexOf('\n', properties) + 1;
        int propIndent = properties - propLineStart;
        if (indent > propIndent + 2) {
          offenders.add(file.getPath() + ":" + (countLines(src, matcher.start()) + 1)
              + "（required 缩进 " + indent + " > properties 缩进 " + propIndent + "）");
        }
      }
    }

    assertTrue(checked > 0, "没有扫描到任何带 required 的工具 schema，测试可能失效");
    assertTrue(
        offenders.isEmpty(),
        "发现 required 被嵌进 properties 内部（会让严格 provider 拒绝整个 tools 数组）："
            + offenders);
  }

  private static int countLines(String src, int upTo) {
    int count = 0;
    for (int i = 0; i < upTo; i++) {
      if (src.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
  }

  private static void collect(File dir, List<File> out) {
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File file : files) {
      if (file.isDirectory()) {
        collect(file, out);
      } else if (file.getName().endsWith(".java") || file.getName().endsWith(".kt")) {
        out.add(file);
      }
    }
  }
}
