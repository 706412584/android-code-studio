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

package com.tom.rv2ide.ai.agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 锁定 {@link TitleGenerator} 的占位派生与模型输出解析（不发真实网络请求）。 */
public class TitleGeneratorTest {

  // ---- 占位标题 ----

  @Test
  public void placeholderTakesFirstSentence() {
    assertEquals("你好。", TitleGenerator.derivePlaceholder("你好。第二句不该出现"));
  }

  @Test
  public void placeholderCollapsesWhitespace() {
    // 换行与连续空白压成单个空格（不是删除）——保证句子可读性。
    assertEquals("编译 失败了", TitleGenerator.derivePlaceholder("编译\n  失败了"));
  }

  @Test
  public void placeholderTruncatesLongText() {
    // 没有句末标点、且明显超过占位上限的长文本应被截断并带省略号。
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      sb.append("很长的消息内容");
    }
    String result = TitleGenerator.derivePlaceholder(sb.toString());
    assertTrue(result.length() <= TitleGenerator.PLACEHOLDER_MAX_LEN);
    assertTrue(result.endsWith("…"));
  }

  @Test
  public void placeholderEmptyForBlankInput() {
    assertEquals("", TitleGenerator.derivePlaceholder(null));
    assertEquals("", TitleGenerator.derivePlaceholder("   "));
  }

  // ---- 对话文本拼接 ----

  @Test
  public void conversationInputIncludesRoles() {
    String text =
        TitleGenerator.buildConversationInput(
            Arrays.asList(
                new TitleGenerator.Turn("编译失败", "我看看日志"),
                new TitleGenerator.Turn("还是报错", "换种方式")));
    assertTrue(text.contains("user: 编译失败"));
    assertTrue(text.contains("assistant: 我看看日志"));
    assertTrue(text.contains("user: 还是报错"));
  }

  @Test
  public void conversationInputSkipsEmptyParts() {
    String text =
        TitleGenerator.buildConversationInput(
            Arrays.asList(new TitleGenerator.Turn("你好", "")));
    assertTrue(text.contains("user: 你好"));
    assertTrue(!text.contains("assistant:"));
  }

  @Test
  public void conversationInputTruncatesToLimit() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      big.append("很长的内容");
    }
    String text =
        TitleGenerator.buildConversationInput(
            Arrays.asList(new TitleGenerator.Turn(big.toString(), "")));
    assertTrue(text.length() <= TitleGenerator.TITLE_INPUT_MAX_LEN);
  }

  // ---- 模型输出解析 ----

  @Test
  public void parsesStandardChatCompletion() {
    String body =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"title\\\":\\\"修复构建失败\\\"}\"}}]}";
    assertEquals("修复构建失败", TitleGenerator.extractTitle(body));
  }

  @Test
  public void parsesFencedJson() {
    assertEquals("编译问题排查", TitleGenerator.parseTitleText("```json\n{\"title\":\"编译问题排查\"}\n```"));
  }

  @Test
  public void parsesPlainTextTitle() {
    assertEquals("局域网模型配置", TitleGenerator.parseTitleText("局域网模型配置"));
  }

  @Test
  public void parsesJsonEmbeddedInProse() {
    assertEquals("项目初始化", TitleGenerator.parseTitleText("好的，标题是：{\"title\":\"项目初始化\"}"));
  }

  @Test
  public void rejectsOverlongTitle() {
    StringBuilder longTitle = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      longTitle.append("标题");
    }
    assertNull(TitleGenerator.parseTitleText(longTitle.toString()));
  }

  @Test
  public void rejectsStructuredFragment() {
    assertNull(TitleGenerator.parseTitleText("{\"title\":"));
    assertNull(TitleGenerator.parseTitleText("```json"));
  }

  @Test
  public void extractTitleHandlesMissingChoices() {
    assertNull(TitleGenerator.extractTitle("{}"));
    assertNull(TitleGenerator.extractTitle("not json"));
    assertNull(TitleGenerator.extractTitle(""));
  }

  @Test
  public void generateReturnsNullOnNetworkFailure() {
    // poster 抛异常时必须静默返回 null——标题失败不该影响对话。
    String result =
        TitleGenerator.generate(
            "user: 你好",
            modelConfig(),
            (url, body, config) -> {
              throw new Exception("模拟网络失败");
            });
    assertNull(result);
  }

  @Test
  public void generateReturnsNullForEmptyBaseUrl() {
    assertNull(TitleGenerator.generate("user: 你好", modelConfigWithUrl("")));
  }

  private static com.tom.rv2ide.ai.protocol.ModelConfig modelConfig() {
    return modelConfigWithUrl("https://example.com/v1");
  }

  private static com.tom.rv2ide.ai.protocol.ModelConfig modelConfigWithUrl(String url) {
    return com.tom.rv2ide.ai.protocol.ModelConfig.builder(
            "id", "name", com.tom.rv2ide.ai.protocol.ModelProtocolType.OPENAI_COMPATIBLE,
            "label", url, "key", "model-x")
        .build();
  }
}
