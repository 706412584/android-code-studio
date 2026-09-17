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

package com.tom.rv2ide.ai.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.agent.prompt.ChatMode;
import org.junit.jupiter.api.Test;

/**
 * 斜杠命令解析的回归测试。
 *
 * <p><b>为什么需要它</b>：这里最容易犯的错是「把以斜杠开头的普通消息当成命令」。
 * 用户输入 {@code /etc/hosts 是干什么的} 时，若弹一个「未知命令」错误，消息就发不出去——
 * 而用户本来就没打算用命令。这条边界必须有测试钉住。
 */
final class SlashCommandCatalogTest {

  private static SlashCommandCatalog.Parsed parse(String input) {
    return SlashCommandCatalog.parse(input);
  }

  // ---- 非命令 ----

  @Test
  void plainTextIsNotACommand() {
    SlashCommandCatalog.Parsed result = parse("帮我改一下构建文件");

    assertEquals(SlashCommandCatalog.Kind.NOT_A_COMMAND, result.getKind());
    assertFalse(result.isCommand());
  }

  @Test
  void pathStartingWithSlashIsNotACommand() {
    // 关键边界：用户完全可能输入以斜杠开头的普通消息。
    // 弹「未知命令」会让这条消息发不出去，而用户本来没打算用命令。
    SlashCommandCatalog.Parsed result = parse("/etc/hosts 这个文件是干什么的");

    assertEquals(SlashCommandCatalog.Kind.NOT_A_COMMAND, result.getKind());
    assertFalse(result.isCommand());
  }

  @Test
  void unknownSlashWordIsNotACommand() {
    assertEquals(
        SlashCommandCatalog.Kind.NOT_A_COMMAND, parse("/foobar").getKind());
    assertEquals(
        SlashCommandCatalog.Kind.NOT_A_COMMAND, parse("/usr/bin/env").getKind());
  }

  @Test
  void loneSlashIsNotACommand() {
    assertEquals(SlashCommandCatalog.Kind.NOT_A_COMMAND, parse("/").getKind());
    assertEquals(SlashCommandCatalog.Kind.NOT_A_COMMAND, parse("   ").getKind());
    assertEquals(SlashCommandCatalog.Kind.NOT_A_COMMAND, parse(null).getKind());
    assertEquals(SlashCommandCatalog.Kind.NOT_A_COMMAND, parse("").getKind());
  }

  // ---- 基本命令 ----

  @Test
  void parsesSimpleCommands() {
    assertEquals(SlashCommandCatalog.Kind.NEW_CONVERSATION, parse("/new").getKind());
    assertEquals(SlashCommandCatalog.Kind.CLEAR, parse("/clear").getKind());
    assertEquals(SlashCommandCatalog.Kind.HELP, parse("/help").getKind());
  }

  @Test
  void commandNamesAreCaseInsensitive() {
    assertEquals(SlashCommandCatalog.Kind.NEW_CONVERSATION, parse("/NEW").getKind());
    assertEquals(SlashCommandCatalog.Kind.MODE, parse("/Mode plan").getKind());
  }

  @Test
  void toleratesSurroundingWhitespace() {
    assertEquals(SlashCommandCatalog.Kind.HELP, parse("  /help  ").getKind());
    assertEquals(ChatMode.PLAN, parse("  /mode   plan  ").modeOrNull());
  }

  @Test
  void simpleCommandsIgnoreTrailingArguments() {
    // 「/new 一下」不该因为多了个字就失败——用户的意图很清楚。
    assertEquals(SlashCommandCatalog.Kind.NEW_CONVERSATION, parse("/new 一下").getKind());
  }

  // ---- /mode ----

  @Test
  void parsesModeArgument() {
    assertEquals(ChatMode.PLAN, parse("/mode plan").modeOrNull());
    assertEquals(ChatMode.CHAT, parse("/mode chat").modeOrNull());
    assertEquals(ChatMode.AGENT, parse("/mode agent").modeOrNull());
    assertEquals(ChatMode.CONTROL, parse("/mode control").modeOrNull());
  }

  @Test
  void modeWithoutArgumentIsInvalidWithUsageHint() {
    // 不带参数时列出可选值，比只说「参数错误」更能让用户立刻用起来。
    SlashCommandCatalog.Parsed result = parse("/mode");

    assertEquals(SlashCommandCatalog.Kind.INVALID, result.getKind());
    assertTrue(result.getError().contains("chat"), result.getError());
    assertTrue(result.getError().contains("plan"), result.getError());
    assertTrue(result.isCommand());
    assertFalse(result.isExecutable());
  }

  @Test
  void unknownModeListsValidValues() {
    SlashCommandCatalog.Parsed result = parse("/mode nonsense");

    assertEquals(SlashCommandCatalog.Kind.INVALID, result.getKind());
    assertTrue(result.getError().contains("nonsense"), result.getError());
    assertTrue(result.getError().contains("agent"), result.getError());
  }

  @Test
  void modeOrNullIsNullForNonModeCommands() {
    assertNull(parse("/new").modeOrNull());
    assertNull(parse("/help").modeOrNull());
    assertNull(parse("普通消息").modeOrNull());
  }

  // ---- /model ----

  @Test
  void parsesModelNamePreservingCase() {
    // 模型名大小写敏感（deepseek-chat 与 DeepSeek-Chat 不是一回事），不能归一化。
    SlashCommandCatalog.Parsed result = parse("/model DeepSeek-Chat");

    assertEquals(SlashCommandCatalog.Kind.MODEL, result.getKind());
    assertEquals("DeepSeek-Chat", result.getArgument());
  }

  @Test
  void modelWithoutArgumentIsInvalid() {
    SlashCommandCatalog.Parsed result = parse("/model");

    assertEquals(SlashCommandCatalog.Kind.INVALID, result.getKind());
    assertTrue(result.getError().contains("/model"), result.getError());
  }

  @Test
  void modelNameWithSpacesIsKeptWhole() {
    SlashCommandCatalog.Parsed result = parse("/model gpt 5.1 codex");

    assertEquals(SlashCommandCatalog.Kind.MODEL, result.getKind());
    assertEquals("gpt 5.1 codex", result.getArgument());
  }

  // ---- 补全 ----

  @Test
  void matchesCommandsByPrefix() {
    assertEquals(5, SlashCommandCatalog.matching("/").size());
    // "m" / "mo" / "mode" 都同时匹配 mode 与 model——"model" 以 "mode" 开头。
    // 这是前缀匹配的正常行为，用户多打一个字符就能区分。
    assertEquals(2, SlashCommandCatalog.matching("/m").size());
    assertEquals(2, SlashCommandCatalog.matching("/mo").size());
    assertEquals(2, SlashCommandCatalog.matching("/mode").size());
    // "model" 只剩它自己
    assertEquals(1, SlashCommandCatalog.matching("/model").size());
    assertEquals("model", SlashCommandCatalog.matching("/model").get(0).getName());
    assertEquals(1, SlashCommandCatalog.matching("/ne").size());
    assertEquals(1, SlashCommandCatalog.matching("/h").size());
  }

  @Test
  void matchingStopsAfterArgumentStarts() {
    // 已打空格 → 命令名确定，不再给补全提示，否则会遮住参数输入。
    assertTrue(SlashCommandCatalog.matching("/mode ").isEmpty());
    assertTrue(SlashCommandCatalog.matching("/mode pl").isEmpty());
  }

  @Test
  void matchingReturnsEmptyForNonSlashInput() {
    assertTrue(SlashCommandCatalog.matching("普通消息").isEmpty());
    assertTrue(SlashCommandCatalog.matching(null).isEmpty());
    assertTrue(SlashCommandCatalog.matching("").isEmpty());
  }

  @Test
  void matchingIsCaseInsensitive() {
    // /MO 同时匹配 mode 与 model，大小写不影响匹配结果
    assertEquals(2, SlashCommandCatalog.matching("/MO").size());
    assertEquals(
        SlashCommandCatalog.matching("/mo").size(), SlashCommandCatalog.matching("/MO").size());
  }

  // ---- 目录与帮助 ----

  @Test
  void definitionsHaveUsageAndDescription() {
    for (SlashCommandCatalog.Definition definition : SlashCommandCatalog.definitions()) {
      assertFalse(definition.getName().isEmpty());
      assertTrue(definition.getUsage().startsWith("/"), definition.getUsage());
      assertFalse(definition.getDescription().isEmpty(), definition.getName());
    }
  }

  @Test
  void helpTextListsEveryCommand() {
    String help = SlashCommandCatalog.helpText();

    for (SlashCommandCatalog.Definition definition : SlashCommandCatalog.definitions()) {
      assertTrue(help.contains(definition.getUsage()), "帮助缺少 " + definition.getName());
    }
    // 说明命令是本地的，用户才会放心使用
    assertTrue(help.contains("不会发给模型"), help);
  }

  @Test
  void everyDefinitionIsActuallyParsable() {
    // 定义里列了但解析不出来的命令，用户打了只会被当成普通消息——静默失效。
    for (SlashCommandCatalog.Definition definition : SlashCommandCatalog.definitions()) {
      SlashCommandCatalog.Parsed result = parse("/" + definition.getName());
      assertTrue(result.isCommand(), "命令 " + definition.getName() + " 无法被解析");
    }
  }

  @Test
  void commandsNeedingArgumentsSaySoWhenCalledBare() {
    // /mode 与 /model 单独调用时参数缺失。用法字符串里写了 <...> 的，
    // 裸调用必须给出提示而不是静默什么都不做。
    for (SlashCommandCatalog.Definition definition : SlashCommandCatalog.definitions()) {
      if (!definition.getUsage().contains("<")) {
        continue;
      }
      SlashCommandCatalog.Parsed result = parse("/" + definition.getName());
      assertEquals(
          SlashCommandCatalog.Kind.INVALID,
          result.getKind(),
          "命令 " + definition.getName() + " 需要参数，裸调用应给出提示");
      assertFalse(result.getError().isEmpty(), definition.getName());
    }
  }

  @Test
  void commandsWithoutArgumentsAreExecutableBare() {
    for (SlashCommandCatalog.Definition definition : SlashCommandCatalog.definitions()) {
      if (definition.getUsage().contains("<")) {
        continue;
      }
      assertTrue(
          parse("/" + definition.getName()).isExecutable(),
          "命令 " + definition.getName() + " 无需参数，应可直接执行");
    }
  }
}
