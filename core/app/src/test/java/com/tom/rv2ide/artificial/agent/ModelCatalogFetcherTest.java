/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * 锁定模型目录的响应解析。
 *
 * <p>不同服务商的 {@code /v1/models} 响应结构不一致，这是拉取功能里最容易出错的一环。
 * 每个用例对应一种真实见过的形态。
 */
public class ModelCatalogFetcherTest {

  private static List<String> parse(String body) {
    return ModelCatalogFetcher.parseModelIds(body);
  }

  @Test
  public void parsesOpenAiShape() {
    // OpenAI / DeepSeek / Groq 等标准形态
    String body = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}";
    assertEquals(Arrays.asList("gpt-4o", "gpt-4o-mini"), parse(body));
  }

  @Test
  public void parsesModelsKeyShape() {
    // 部分网关用 models 而不是 data
    String body = "{\"models\":[{\"id\":\"qwen-plus\"}]}";
    assertEquals(Arrays.asList("qwen-plus"), parse(body));
  }

  @Test
  public void parsesTopLevelArray() {
    // 少数服务商直接返回数组
    String body = "[{\"id\":\"m1\"},{\"id\":\"m2\"}]";
    assertEquals(Arrays.asList("m1", "m2"), parse(body));
  }

  @Test
  public void parsesBareStringArray() {
    String body = "[\"alpha\",\"beta\"]";
    assertEquals(Arrays.asList("alpha", "beta"), parse(body));
  }

  @Test
  public void fallsBackToNameField() {
    // 有些服务商用 name 而不是 id
    String body = "{\"data\":[{\"name\":\"claude-sonnet-4-5\"}]}";
    assertEquals(Arrays.asList("claude-sonnet-4-5"), parse(body));
  }

  @Test
  public void parsesNestedResultShape() {
    String body = "{\"result\":{\"data\":[{\"id\":\"nested-model\"}]}}";
    assertEquals(Arrays.asList("nested-model"), parse(body));
  }

  @Test
  public void deduplicates() {
    String body = "{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"},{\"id\":\"b\"}]}";
    assertEquals(Arrays.asList("a", "b"), parse(body));
  }

  @Test
  public void sortedForStableOrdering() {
    // 排序让同一份目录每次显示顺序一致，用户不会看到列表无规律跳动。
    String body = "{\"data\":[{\"id\":\"z\"},{\"id\":\"a\"},{\"id\":\"m\"}]}";
    assertEquals(Arrays.asList("a", "m", "z"), parse(body));
  }

  @Test
  public void malformedJsonYieldsEmpty() {
    assertTrue(parse("{not json").isEmpty());
    assertTrue(parse("").isEmpty());
    assertTrue(parse("   ").isEmpty());
  }

  @Test
  public void errorResponseYieldsEmpty() {
    // 401 的错误体会被上层拦掉，但万一漏到这里也不能崩，且不该产出垃圾条目。
    assertTrue(parse("{\"error\":{\"message\":\"invalid api key\"}}").isEmpty());
  }

  @Test
  public void skipsEntriesWithoutIdOrName() {
    String body = "{\"data\":[{\"id\":\"\"},{\"foo\":\"bar\"},{\"id\":\"real\"}]}";
    assertEquals(Arrays.asList("real"), parse(body));
  }

  @Test
  public void nullsAndEmptyStringsHandled() {
    assertTrue(parse(null).isEmpty());
    assertTrue(parse("null").isEmpty());
  }
}
