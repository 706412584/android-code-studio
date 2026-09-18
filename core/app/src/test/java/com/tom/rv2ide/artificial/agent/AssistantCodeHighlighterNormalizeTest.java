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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * {@link AssistantCodeHighlighter#normalize} 的行为锁定。
 *
 * <p>这个方法决定「代码块有没有颜色」。模型写语言标注的方式很杂（简写、全称、带附加参数、
 * 大小写混用），任何一条没归一就会让那个代码块静默失去高亮——表现为用户眼里的
 * 「有时有颜色有时没有」，很难归因。因此逐类固定下来。
 *
 * <p>高亮本体依赖 Android 的 Spannable，属于设备侧行为，不在这个纯 JVM 测试范围内。
 */
public class AssistantCodeHighlighterNormalizeTest {

  @Test
  public void exactNamesPassThrough() {
    // 语法表里已有的名字不该被别名表改写。
    assertEquals("java", AssistantCodeHighlighter.normalize("java"));
    assertEquals("kotlin", AssistantCodeHighlighter.normalize("kotlin"));
    assertEquals("python", AssistantCodeHighlighter.normalize("python"));
    assertEquals("groovy", AssistantCodeHighlighter.normalize("groovy"));
    assertEquals("cpp", AssistantCodeHighlighter.normalize("cpp"));
    assertEquals("csharp", AssistantCodeHighlighter.normalize("csharp"));
  }

  @Test
  public void commonAbbreviationsAreMapped() {
    // 这些简写的出现频率其实高于全称，漏一个就是一个没颜色的代码块。
    assertEquals("javascript", AssistantCodeHighlighter.normalize("js"));
    assertEquals("javascript", AssistantCodeHighlighter.normalize("ts"));
    assertEquals("python", AssistantCodeHighlighter.normalize("py"));
    assertEquals("bash", AssistantCodeHighlighter.normalize("sh"));
    assertEquals("bash", AssistantCodeHighlighter.normalize("shell"));
    assertEquals("yaml", AssistantCodeHighlighter.normalize("yml"));
    assertEquals("kotlin", AssistantCodeHighlighter.normalize("kt"));
    assertEquals("groovy", AssistantCodeHighlighter.normalize("gradle"));
  }

  @Test
  public void markupAliasesCoverHtmlAndXml() {
    // Prism4j 把 xml/html/svg 统一注册成 markup。模型几乎不会写 markup，
    // 只会写 xml 或 html——不映射就等于这三种标签全都拿不到高亮。
    assertEquals("markup", AssistantCodeHighlighter.normalize("xml"));
    assertEquals("markup", AssistantCodeHighlighter.normalize("html"));
    assertEquals("markup", AssistantCodeHighlighter.normalize("svg"));
  }

  @Test
  public void caseIsIgnored() {
    assertEquals("java", AssistantCodeHighlighter.normalize("JAVA"));
    assertEquals("javascript", AssistantCodeHighlighter.normalize("JS"));
    assertEquals("bash", AssistantCodeHighlighter.normalize("Shell"));
  }

  @Test
  public void surroundingWhitespaceIsTrimmed() {
    assertEquals("java", AssistantCodeHighlighter.normalize("  java  "));
    assertEquals("java", AssistantCodeHighlighter.normalize("\tjava\n"));
  }

  @Test
  public void extraAnnotationsAfterLanguageAreIgnored() {
    // 模型常写成 ```java title="Foo.java" 或 ```java,kt，语言名后面的内容要丢掉。
    assertEquals("java", AssistantCodeHighlighter.normalize("java title=\"Foo.java\""));
    assertEquals("java", AssistantCodeHighlighter.normalize("java,kt"));
    assertEquals("bash", AssistantCodeHighlighter.normalize("sh;bash"));
    assertEquals("json", AssistantCodeHighlighter.normalize("json | jsonc"));
  }

  @Test
  public void noLanguageMeansNoHighlight() {
    assertNull(AssistantCodeHighlighter.normalize(null));
    assertNull(AssistantCodeHighlighter.normalize(""));
    assertNull(AssistantCodeHighlighter.normalize("   "));
  }

  @Test
  public void explicitPlainTextMarkersMeanNoHighlight() {
    // 模型有时显式标注「这不是代码」。此时不该去猜一个语法来高亮，
    // 否则一段中文说明会被当成某种语言乱着色。
    assertNull(AssistantCodeHighlighter.normalize("text"));
    assertNull(AssistantCodeHighlighter.normalize("txt"));
    assertNull(AssistantCodeHighlighter.normalize("plaintext"));
    assertNull(AssistantCodeHighlighter.normalize("none"));
  }

  @Test
  public void unknownLanguageIsPassedThroughForPrismToReject() {
    // 不认识的语言不在 normalize 阶段拦截——交给 Prism4j 查表，
    // 它返回 null 时高亮器原样返回文本。这样将来 Prism4j 支持了新语言，
    // 无需改这里就能生效。
    assertEquals("cobol", AssistantCodeHighlighter.normalize("cobol"));
    assertEquals("brainfuck", AssistantCodeHighlighter.normalize("brainfuck"));
  }

  @Test
  public void aliasMappingIsAppliedAfterSplitting() {
    // 组合场景：带附加信息的简写也要先拆再映射，顺序错了就漏。
    assertEquals("javascript", AssistantCodeHighlighter.normalize("js title=\"a.js\""));
    assertEquals("bash", AssistantCodeHighlighter.normalize("sh,shell"));
  }
}
