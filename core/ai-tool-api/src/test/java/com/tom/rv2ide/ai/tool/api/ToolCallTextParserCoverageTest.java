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

package com.tom.rv2ide.ai.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 补齐 {@link ToolCallTextParser} 的解析分支：XML 属性/参数形态、JSON 各种键名、
 * 流式预览下的「半个 tool_call」、实体解码、以及工具名别名归一化。
 * 这些是模型输出格式千奇百怪时最容易踩空的分支。
 */
public final class ToolCallTextParserCoverageTest {

  @Test
  public void nullInputYieldsEmptyResult() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(null);
    assertEquals("", result.getText());
    assertFalse(result.hasToolCalls());
    assertFalse(result.hasToolMarkup());
  }

  @Test
  public void textWithoutToolMarkupIsReturnedAsIs() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse("just a reply");
    assertEquals("just a reply", result.getText());
    assertFalse(result.hasToolMarkup());
    assertFalse(result.hasToolCalls());
  }

  @Test
  public void xmlAttributesFormIsParsed() {
    String xml = "prefix <tool_call id=\"c1\" name=\"file_write\">"
        + "<argument name=\"file_path\">a.java</argument>"
        + "<argument name=\"content\">hi</argument></tool_call> suffix";
    ToolCallTextParser.Result result = ToolCallTextParser.parse(xml);
    assertEquals("prefix  suffix", result.getText());
    assertEquals(1, result.getToolCalls().size());
    ToolCall call = result.getToolCalls().get(0);
    assertEquals("c1", call.getId());
    assertEquals(ToolNames.FILE_WRITE, call.getName());
    assertTrue(call.getArguments().contains("a.java"));
    assertTrue(call.getArguments().contains("hi"));
  }

  @Test
  public void xmlToolNameCanComeFromFirstArgument() {
    // 模型常把 name 写成子标签而不是属性
    String xml = "<tool_call><argument name=\"name\">glob</argument>"
        + "<argument name=\"pattern\">**/*.kt</argument></tool_call>";
    ToolCallTextParser.Result result = ToolCallTextParser.parse(xml);
    assertEquals(1, result.getToolCalls().size());
    ToolCall call = result.getToolCalls().get(0);
    assertEquals(ToolNames.GLOB, call.getName());
    // name 参数本身不应作为工具参数写入
    assertFalse(call.getArguments().contains("\"name\""));
    assertTrue(call.getArguments().contains("**/*.kt"));
  }

  @Test
  public void xmlWithoutNameIsIgnored() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("<tool_call id=\"c1\"><argument name=\"x\">1</argument></tool_call>");
    assertTrue(result.getToolCalls().isEmpty());
  }

  @Test
  public void xmlToolCallWithoutIdGetsGeneratedId() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("<tool_call name=\"glob\"></tool_call>");
    assertEquals(1, result.getToolCalls().size());
    assertTrue(result.getToolCalls().get(0).getId().startsWith("text_tool_xml_"));
  }

  @Test
  public void xmlSingleQuotedAttributeIsRead() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("<tool_call name='glob'><argument name='pattern'>**</argument></tool_call>");
    assertEquals(1, result.getToolCalls().size());
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
  }

  @Test
  public void xmlEntitiesAreDecoded() throws Exception {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_call name=\"file_write\"><argument name=\"content\">&lt;a&gt;&amp;&quot;b&quot;&apos;c&apos;</argument></tool_call>");
    // 实体在写入前解码，且结果在 JSON 参数串里被正确转义
    String content = new org.json.JSONObject(
        result.getToolCalls().get(0).getArguments()).getString("content");
    assertEquals("<a>&\"b\"'c'", content);
  }

  @Test
  public void cdataArgumentIsUnwrapped() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_call name=\"file_write\"><argument name=\"content\"><![CDATA[<xml/>]]></argument></tool_call>");
    assertTrue(result.getToolCalls().get(0).getArguments().contains("<xml/>"));
  }

  @Test
  public void normalizeToolNameMapsCommonAliases() {
    assertEquals(ToolNames.FILE_WRITE, nameFrom("write_file"));
    assertEquals(ToolNames.FILE_READ, nameFrom("readFile"));
    assertEquals(ToolNames.FILE_EDIT, nameFrom("file-edit"));
    assertEquals(ToolNames.FILE_DELETE, nameFrom("delete_file"));
    assertEquals(ToolNames.LIST_DIR, nameFrom("listDirectory"));
    assertEquals(ToolNames.GLOB, nameFrom("file_search"));
    assertEquals(ToolNames.SHELL_EXECUTE, nameFrom("shell"));
    // 未知名字原样保留
    assertEquals("custom_tool", nameFrom("custom_tool"));
  }

  @Test
  public void normalizeToolNameAliasesCoverBothSpellings() {
    // 每组别名两个写法都要能归一化，否则模型换个大小写就认不出工具
    assertEquals(ToolNames.FILE_WRITE, nameFrom("fileWrite"));
    assertEquals(ToolNames.FILE_READ, nameFrom("read_file"));
    assertEquals(ToolNames.FILE_EDIT, nameFrom("editFile"));
    assertEquals(ToolNames.FILE_DELETE, nameFrom("fileDelete"));
    assertEquals(ToolNames.LIST_DIR, nameFrom("list_dir"));
    assertEquals(ToolNames.GLOB, nameFrom("searchFile"));
    assertEquals(ToolNames.SHELL_EXECUTE, nameFrom("shell_execute"));
  }

  @Test
  public void blankNameAttributeFallsBackToArgumentName() {
    // name="" 视为未命名，继续找 <argument name="name">
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_call name=\"\"><argument name=\"name\">glob</argument></tool_call>");
    assertEquals(1, result.getToolCalls().size());
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
  }

  private static String nameFrom(String alias) {
    return ToolCallTextParser
        .parse("<tool_call name=\"" + alias + "\"></tool_call>")
        .getToolCalls().get(0).getName();
  }

  @Test
  public void jsonFunctionShapeWithStringArguments() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>[{\"function\":{\"name\":\"glob\",\"arguments\":\"{\\\"p\\\":1}\"}}]</tool_calls>");
    assertEquals("{\"p\":1}", result.getToolCalls().get(0).getArguments());
  }

  @Test
  public void jsonTopLevelNameAndArguments() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>[{\"name\":\"glob\",\"arguments\":{\"p\":1}}]</tool_calls>");
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
    assertTrue(result.getToolCalls().get(0).getArguments().contains("\"p\":1"));
  }

  @Test
  public void jsonToolNameKeyIsAccepted() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>[{\"tool_name\":\"glob\",\"input\":{\"p\":2}}]</tool_calls>");
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
    assertTrue(result.getToolCalls().get(0).getArguments().contains("\"p\":2"));
  }

  @Test
  public void jsonParametersKeyIsAccepted() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>[{\"name\":\"glob\",\"parameters\":{\"p\":3}}]</tool_calls>");
    assertTrue(result.getToolCalls().get(0).getArguments().contains("\"p\":3"));
  }

  @Test
  public void jsonSingleKeyObjectTreatsKeyAsToolName() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("<tool_calls>[{\"glob\":{\"p\":4}}]</tool_calls>");
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
    assertTrue(result.getToolCalls().get(0).getArguments().contains("\"p\":4"));
  }

  @Test
  public void jsonObjectWithoutNameIsSkipped() {
    // 单键对象走「键名当工具名」兜底；含多个键但都无名字时无法识别，应丢弃
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>[{\"arguments\":{\"p\":1},\"extra\":2}]</tool_calls>");
    assertTrue(result.getToolCalls().isEmpty());
  }

  @Test
  public void jsonObjectWithNullArgumentsBecomesEmptyObject() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>[{\"name\":\"glob\",\"arguments\":null}]</tool_calls>");
    assertEquals("{}", result.getToolCalls().get(0).getArguments());
  }

  @Test
  public void jsonWithoutIdGetsGeneratedId() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("<tool_calls>[{\"name\":\"glob\"}]</tool_calls>");
    assertTrue(result.getToolCalls().get(0).getId().startsWith("text_tool_json_"));
  }

  @Test
  public void fencedJsonPayloadIsUnwrapped() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>```json\n[{\"name\":\"glob\",\"arguments\":{\"p\":1}}]\n```</tool_calls>");
    assertEquals(1, result.getToolCalls().size());
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
  }

  @Test
  public void wrappedToolCallsObjectIsUnwrapped() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>{\"tool_calls\":[{\"name\":\"glob\",\"arguments\":{\"p\":1}}]}</tool_calls>");
    assertEquals(1, result.getToolCalls().size());
  }

  @Test
  public void malformedJsonPayloadIsIgnored() {
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("<tool_calls>{not valid json}</tool_calls>");
    assertTrue(result.getToolCalls().isEmpty());
  }

  @Test
  public void emptyToolCallsPayloadIsIgnored() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse("<tool_calls>   </tool_calls>");
    assertTrue(result.getToolCalls().isEmpty());
  }

  @Test
  public void argumentsGivenAsBareScalarAreStringified() {
    // 少数模型把 arguments 直接写成字符串标量而非对象
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("<tool_calls>[{\"name\":\"glob\",\"arguments\":\"  \"}]</tool_calls>");
    assertEquals("{}", result.getToolCalls().get(0).getArguments());
  }

  @Test
  public void argumentsGivenAsNonEmptyScalarAreKept() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_calls>[{\"name\":\"glob\",\"arguments\":\"{\\\"p\\\":9}\"}]</tool_calls>");
    assertEquals("{\"p\":9}", result.getToolCalls().get(0).getArguments());
  }

  @Test
  public void plainToolCallJsonBodyIsParsed() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_call>{\"name\":\"glob\",\"arguments\":{\"p\":1}}</tool_call>");
    assertEquals(1, result.getToolCalls().size());
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
  }

  @Test
  public void streamingPreviewParsesClosedXmlCall() {
    ToolCallTextParser.Result result = ToolCallTextParser.parseStreamingPreview(
        "<tool_call id=\"c1\" name=\"glob\"><argument name=\"pattern\">**</argument></tool_call>");
    assertEquals(1, result.getToolCalls().size());
    assertEquals("c1", result.getToolCalls().get(0).getId());
  }

  @Test
  public void streamingPreviewParsesUnclosedXmlCall() {
    // 流式输出到这里只发了一半：开标签完整但闭标签还没到。参数本身也尚未闭合，
    // 因此只识别出工具名，参数为空对象。
    ToolCallTextParser.Result result = ToolCallTextParser.parseStreamingPreview(
        "<tool_call id=\"c1\" name=\"glob\"><argument name=\"pattern\">**</argument>");
    assertEquals(1, result.getToolCalls().size());
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
    assertTrue(result.getToolCalls().get(0).getArguments().contains("**"));
  }

  @Test
  public void streamingPreviewParsesHalfWrittenOpenTag() {
    // 连 > 都还没到
    ToolCallTextParser.Result result =
        ToolCallTextParser.parseStreamingPreview("<tool_call name=\"glob\"");
    assertEquals(1, result.getToolCalls().size());
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
  }

  @Test
  public void streamingPreviewPastClosedCallDoesNotParseExtra() {
    ToolCallTextParser.Result result = ToolCallTextParser.parseStreamingPreview(
        "<tool_call name=\"glob\"></tool_call> tail text");
    assertEquals(1, result.getToolCalls().size());
    assertEquals("<tool_call name=\"glob\"></tool_call> tail text".length() > 0, result.hasToolMarkup());
  }

  @Test
  public void openTagWithoutCloserNamesViaArgumentInPreview() {
    ToolCallTextParser.Result result = ToolCallTextParser.parseStreamingPreview(
        "<tool_call><argument name=\"name\">glob</argument>");
    assertEquals(1, result.getToolCalls().size());
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(0).getName());
  }

  @Test
  public void hasToolCallsReflectsParsedContent() {
    assertTrue(ToolCallTextParser.parse("<tool_call name=\"glob\"></tool_call>").hasToolCalls());
    assertFalse(ToolCallTextParser.parse("nothing").hasToolCalls());
  }

  @Test
  public void openMarkupIsStrippedFromTrailingText() {
    // 未闭合的 <tool_calls 之后的内容不应作为正文交给用户
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("好的<tool_calls>[{\"name\":\"glob\"}]");
    assertEquals("好的", result.getText());
  }

  @Test
  public void openToolCallMarkupStripsTrailingText() {
    // 只有 <tool_call（没有 tool_calls 兄弟标签）时走另一分支
    ToolCallTextParser.Result result =
        ToolCallTextParser.parse("reply<tool_call name=\"glob\">");
    assertEquals("reply", result.getText());
  }

  @Test
  public void markupDetectionIsCaseInsensitive() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse("<TOOL_CALL name=\"glob\"></TOOL_CALL>");
    assertTrue(result.hasToolMarkup());
    assertEquals(1, result.getToolCalls().size());
  }

  @Test
  public void streamingPreviewResumesAfterClosedCall() {
    // 第一个调用已闭合，其后是第二个尚未闭合的开标签：应识别出后一个
    ToolCallTextParser.Result result = ToolCallTextParser.parseStreamingPreview(
        "<tool_call name=\"file_read\"></tool_call>继续<tool_call name=\"glob\"");
    assertEquals(2, result.getToolCalls().size());
    assertEquals(ToolNames.GLOB, result.getToolCalls().get(1).getName());
  }

  @Test
  public void argumentWithoutNameAttributeIsIgnored() {
    ToolCallTextParser.Result result = ToolCallTextParser.parse(
        "<tool_call name=\"glob\"><argument>orphan</argument><argument name=\"p\">1</argument></tool_call>");
    String args = result.getToolCalls().get(0).getArguments();
    assertFalse(args.contains("orphan"), args);
    assertTrue(args.contains("\"p\":\"1\""), args);
  }
}
