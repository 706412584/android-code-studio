/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证 {@link ToolCallTextParser} 对各类模型输出格式的解析能力。
 *
 * <p>测试用例原样来自 LineCode Pro，用于保证搬运后的解析行为与上游一致。
 */
public final class ToolCallTextParserTest {

  @Test
  public void parsesToolCallsTagAndRemovesItFromText() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse(
            "先读取文件\n<tool_calls>[{\"name\":\"file_read\",\"arguments\":{\"file_path\":\"app/build.gradle.kts\"}}]</tool_calls>");

    assertEquals("先读取文件", result.getText());
    assertEquals(1, result.getToolCalls().size());
    assertTrue(result.hasToolMarkup());
    ToolCall call = result.getToolCalls().get(0);
    assertEquals("file_read", call.getName());
    assertTrue(call.getArguments().contains("app/build.gradle.kts"));
  }

  @Test
  public void parsesSingleToolCallWithFunctionShape() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse(
            "<tool_call>{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"glob\",\"arguments\":\"{\\\"pattern\\\":\\\"**/*.java\\\"}\"}}</tool_call>");

    assertEquals("", result.getText());
    assertEquals(1, result.getToolCalls().size());
    assertEquals("call_1", result.getToolCalls().get(0).getId());
    assertEquals("glob", result.getToolCalls().get(0).getName());
    assertEquals("{\"pattern\":\"**/*.java\"}", result.getToolCalls().get(0).getArguments());
  }

  @Test
  public void parsesXmlStyleToolCallArgumentsAndNormalizesName() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse(
            "准备写入\n<tool_calls>\n"
                + "<tool_call name=\"filewrite\">\n"
                + "<argument name=\"file_path\">xxxxxx</argument>\n"
                + "<argument name=\"content\">&lt;hello&gt;</argument>\n"
                + "</tool_call>\n"
                + "</tool_calls>");

    assertEquals("准备写入", result.getText());
    assertEquals(1, result.getToolCalls().size());
    ToolCall call = result.getToolCalls().get(0);
    assertEquals("file_write", call.getName());
    assertTrue(call.getArguments().contains("\"file_path\":\"xxxxxx\""));
    assertTrue(call.getArguments().contains("\"content\":\"<hello>\""));
  }

  @Test
  public void streamingPreviewParsesToolTypeBeforeXmlCompletes() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parseStreamingPreview(
            "准备读取\n<tool_calls>\n<tool_call name=\"fileread\">");

    assertEquals("准备读取", result.getText());
    assertEquals(1, result.getToolCalls().size());
    ToolCall call = result.getToolCalls().get(0);
    assertEquals("file_read", call.getName());
    assertEquals("text_tool_xml_0", call.getId());
    assertEquals("{}", call.getArguments());
  }

  @Test
  public void finalParserDoesNotExecuteIncompleteXmlPreview() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("准备读取\n<tool_calls>\n<tool_call name=\"fileread\">");

    assertEquals("准备读取", result.getText());
    assertEquals(0, result.getToolCalls().size());
  }
}
