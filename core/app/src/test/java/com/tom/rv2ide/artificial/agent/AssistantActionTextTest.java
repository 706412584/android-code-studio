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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 底部状态条与重试卡片的两段纯逻辑。
 *
 * <p><b>为什么值得单独测</b>：状态条那一行是用户在整个 agent 运行期间盯着看的唯一文字。
 * 它拼错的两种典型形态都很难在真机上注意到——
 * <ul>
 *   <li><b>整条绝对路径塞进去</b>：会被省略号截成 `…/Renderer.cpp`，看起来像 bug；
 *   <li><b>取不到对象时留下空串</b>：拼出「正在读取 」，末尾一个孤零零的空格。
 * </ul>
 * 这两条都在下面钉住了。重试卡片那部分则是「实测反馈的直接来源」：
 * 用户看到「正在重试 1/10」紧接「请求失败」，中间九次重试毫无痕迹。
 */
public final class AssistantActionTextTest {

  // ---- 动作对象名 ----

  @Test
  public void pathIsCompressedToLastTwoSegments() {
    String target =
        AssistantActionText.INSTANCE.targetOf(
            "file_read",
            "{\"file_path\":\"app/src/main/cpp/Renderer.cpp\"}");

    assertEquals("cpp/Renderer.cpp", target);
  }

  @Test
  public void absoluteAndroidPathIsAlsoCompressed() {
    // 实测设备上的形态：模型给的常常是完整绝对路径。
    String target =
        AssistantActionText.INSTANCE.targetOf(
            "file_edit",
            "{\"file_path\":\"/data/data/com.tom.rv2ide/files/home/ACSProjects/MyGameActivity/app/src/main/cpp/Renderer.h\"}");

    assertEquals("cpp/Renderer.h", target);
  }

  @Test
  public void windowsBackslashesAreNormalized() {
    String target =
        AssistantActionText.INSTANCE.targetOf(
            "file_read", "{\"file_path\":\"core\\\\app\\\\src\\\\Main.kt\"}");

    assertEquals("src/Main.kt", target);
  }

  @Test
  public void shellCommandKeepsItsFullText() {
    // 用户明确要求「显示完整命令动作」：`./gradlew clean` 与 `./gradlew assembleDebug`
    // 是两件事，只显示首个 token 等于什么都没说。
    String command = "cd /data/data/com.tom.rv2ide/files/home/ACSProjects/MyGameActivity && ./gradlew clean";
    String target = AssistantActionText.INSTANCE.targetOf("shell_execute", "{\"command\":\"" + command + "\"}");

    assertEquals(command, target);
  }

  @Test
  public void multiLineCommandIsFlattenedToASingleLine() {
    // 命令常写成多行（`cd x &&\n ./gradlew`）。状态条是固定高度的一行，
    // 带换行的文案会把整行撑高。
    String target =
        AssistantActionText.INSTANCE.targetOf(
            "shell_execute", "{\"command\":\"cd /tmp &&\\n   ./gradlew   clean\"}");

    assertEquals("cd /tmp && ./gradlew clean", target);
    assertFalse("不应含换行", target.contains("\n"));
  }

  @Test
  public void extremelyLongCommandIsTruncatedAtTheEnd() {
    // 兜底：几 KB 的命令不该整条塞进 TextView。截断保留**开头**——
    // 命令的可执行文件与子命令是最能区分它的一条信息。
    StringBuilder sb = new StringBuilder("./gradlew ");
    for (int i = 0; i < 500; i++) {
      sb.append("-Pkey").append(i).append(' ');
    }
    String target = AssistantActionText.INSTANCE.targetOf("shell_execute", "{\"command\":\"" + sb + "\"}");

    assertTrue("应从开头保留: " + target, target.startsWith("./gradlew "));
    assertTrue("应截断: " + target, target.endsWith("…"));
    // 断言**硬编码**的期望长度，而不是拿 TARGET_MAX 去比——用被测常量自身做上界，
    // 改这个常量时断言会跟着一起变，永远测不出「常量被改小导致命令被截太狠」。
    // 120 + 省略号 = 121。
    assertEquals("截断长度应等于 TARGET_MAX+1", 121, target.length());
  }

  @Test
  public void longTargetIsTruncatedWithEllipsis() {
    // 路径压缩到末两段之后仍可能超长（末两段各自很长），此时才走兜底截断。
    StringBuilder longName = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      longName.append('b');
    }
    String target =
        AssistantActionText.INSTANCE.targetOf(
            "file_read", "{\"file_path\":\"a/" + longName + ".cpp\"}");

    assertTrue("超长目标应被截断: " + target, target.endsWith("…"));
    // 同上：用硬编码值，不引用 TARGET_MAX。
    assertEquals(121, target.length());
  }

  @Test
  public void truncationNeverSplitsASurrogatePair() {
    // 代理对（emoji、CJK 扩展 B 区汉字）占两个 UTF-16 单元。按单元数截断若正好
    // 落在代理对中间，结果末尾会是一个孤立的高位代理——它渲染成 `?`，
    // 并且只在特定长度的名字上偶发，极难定位。中文项目里完全可能出现。
    //
    // 构造：让第 TARGET_MAX 个单元落在代理对的高位上。
    // "𠀋" = U+2000B，UTF-16 是两个单元。前缀填 119 个 ASCII，则
    // 索引 119 是高位、120 是低位；max=120 时 substring(0,120) 会把高位留下。
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 119; i++) {
      sb.append('a');
    }
    sb.append('\uD840').append('\uDC0B'); // 𠀋
    for (int i = 0; i < 50; i++) {
      sb.append('b');
    }
    String target = AssistantActionText.INSTANCE.targetOf("file_read", "{\"file_path\":\"" + sb + "\"}");

    assertTrue("应截断: " + target, target.endsWith("…"));
    String body = target.substring(0, target.length() - 1);
    assertFalse(
        "末尾不应是孤立的高位代理: " + (int) body.charAt(body.length() - 1),
        Character.isHighSurrogate(body.charAt(body.length() - 1)));
    // 逐字符校验：整个串必须是合法的 UTF-16（每个高位代理后面都跟低位代理）。
    for (int i = 0; i < body.length(); i++) {
      if (Character.isHighSurrogate(body.charAt(i))) {
        assertTrue(
            "高位代理后必须跟低位代理 (index " + i + ")",
            i + 1 < body.length() && Character.isLowSurrogate(body.charAt(i + 1)));
        i++;
      } else {
        assertFalse(
            "低位代理不应单独出现 (index " + i + ")", Character.isLowSurrogate(body.charAt(i)));
      }
    }
  }

  @Test
  public void missingArgumentYieldsEllipsisNotBlank() {
    // 空串会拼出「正在读取 」（末尾一个孤零零的空格），这是最容易被忽略的一种坏文案。
    assertEquals("…", AssistantActionText.INSTANCE.targetOf("file_read", "{}"));
    assertEquals("…", AssistantActionText.INSTANCE.targetOf("file_read", ""));
    assertEquals("…", AssistantActionText.INSTANCE.targetOf("file_read", "not json at all"));
  }

  @Test
  public void directoryPathKeepsTheLeafName() {
    // 末两段里如果最后一段为空（路径以 / 结尾），filter 会把它去掉，
    // 否则会得到「cpp/」这种带尾斜杠的奇怪结果。
    assertEquals(
        "main/cpp",
        AssistantActionText.INSTANCE.targetOf("list_dir", "{\"path\":\"app/src/main/cpp/\"}"));
  }

  // ---- 重试卡片 ----

  @Test
  public void retryCardStaysHiddenForTheFirstFewAttempts() {
    // 偶发的一两次重连是常态（切网、DNS 抖动），每次都弹红底卡片会让人以为出了大问题。
    assertFalse(AssistantActionText.INSTANCE.shouldShowRetry(1));
    assertFalse(AssistantActionText.INSTANCE.shouldShowRetry(2));
    assertFalse(AssistantActionText.INSTANCE.shouldShowRetry(3));
    assertTrue("第 4 次起应显示", AssistantActionText.INSTANCE.shouldShowRetry(4));
    assertTrue(AssistantActionText.INSTANCE.shouldShowRetry(10));
  }

  @Test
  public void httpStatusIsExtractedAsTheShortCode() {
    String code =
        AssistantActionText.INSTANCE.shortErrorCode(
            "HTTP 503: {\"error\":{\"message\":\"upstream unavailable\",\"type\":\"api_error\"}}");

    assertEquals("HTTP 503", code);
  }

  @Test
  public void streamErrorTypeIsUsedWhenThereIsNoHttpStatus() {
    // 流内错误的 type 比数字更能说明该怎么办。
    String code =
        AssistantActionText.INSTANCE.shortErrorCode(
            "stream error: {\"type\":\"rate_limit_error\",\"message\":\"slow down\"}");

    assertEquals("rate_limit_error", code);
  }

  @Test
  public void plainMessageFallsBackToItsFirstLine() {
    String code =
        AssistantActionText.INSTANCE.shortErrorCode("Model stream communication failed\nlong detail");

    assertEquals("Model stream communication failed", code);
  }

  @Test
  public void veryLongMessageIsTruncated() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append('x');
    }

    String code = AssistantActionText.INSTANCE.shortErrorCode(sb.toString());

    assertTrue("应截断: " + code, code.endsWith("…"));
    assertTrue(code.length() <= AssistantActionText.SHORT_ERROR_MAX + 1);
  }

  @Test
  public void emptyReasonYieldsEmptyCode() {
    // 空串表示「没有可展示的错误码」，调用方据此决定不加那个分隔符。
    assertEquals("", AssistantActionText.INSTANCE.shortErrorCode(""));
    assertEquals("", AssistantActionText.INSTANCE.shortErrorCode("   "));
  }
}
