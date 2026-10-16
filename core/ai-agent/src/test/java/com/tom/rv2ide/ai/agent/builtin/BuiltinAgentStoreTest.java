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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.agent.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 钉住 {@link BuiltinAgentStore} 的「默认值 + 用户覆盖」语义。
 *
 * <p>核心不变量：落盘的**只有与默认不同的项**，因此恢复默认 = 删掉那条覆盖。
 * 一旦有人把 save 改成「无条件存」，升级后就再也分不清「用户改过」与「默认变了」。
 */
final class BuiltinAgentStoreTest {

  /** 取一个真实存在的内置 id（避免测试与预设清单耦合过紧：用列表首项）。 */
  private static String anyBuiltinId() {
    return BuiltinAgents.all().get(0).getId();
  }

  private static BuiltinAgent editedCopy(String id) {
    BuiltinAgent preset = BuiltinAgents.find(id);
    assertNotNull(preset, "前置：id 必须是已知内置 agent");
    return preset.withPrompt(preset.getPrompt() + "\n（用户补充的要求）");
  }

  @Test
  void saveStoresOverrideAndIsVisibleInAll() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    String id = anyBuiltinId();
    BuiltinAgent edited = editedCopy(id);

    assertEquals("", store.save(edited), "合法覆盖应保存成功");
    assertTrue(store.isCustomized(id));
    assertSame(edited, store.find(id), "find 应返回覆盖后的版本");
  }

  @Test
  void savingIdenticalToDefaultRemovesOverride() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    String id = anyBuiltinId();
    store.save(editedCopy(id));
    assertTrue(store.isCustomized(id));

    // 再存一次「与默认完全相同」的版本 → 等价于恢复默认，不留冗余记录。
    assertEquals("", store.save(BuiltinAgents.find(id)));
    assertFalse(store.isCustomized(id), "与默认一致时不应保留覆盖");
  }

  @Test
  void resetRemovesOverrideAndUnknownIdIsReported() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    String id = anyBuiltinId();
    store.save(editedCopy(id));

    assertEquals("", store.reset(id), "已知 id 的 reset 应成功");
    assertFalse(store.isCustomized(id));
    // 本就无覆盖时 reset 也成功（幂等）。
    assertEquals("", store.reset(id));

    assertFalse(store.reset("no-such-agent").isEmpty(), "未知 id 应返回原因");
  }

  @Test
  void unknownIdIsRejectedOnSave() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    String err = store.save(new BuiltinAgent("ghost-agent", "幽灵", "d", "p".repeat(50),
        Collections.emptyList(), true));
    assertTrue(err.contains("未知"), "未知内置 id 应被拒绝：" + err);
    assertFalse(store.isCustomized("ghost-agent"));
  }

  @Test
  void invalidAgentIsRejectedWithValidationMessage() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    String id = anyBuiltinId();
    // 名字为空 → 校验失败（且 id 已知，确保走到了 validationError 分支）。
    BuiltinAgent broken =
        new BuiltinAgent(id, "", "d", "p".repeat(50), Collections.emptyList(), true);
    String err = store.save(broken);
    assertTrue(err.contains("名字"), "应给出校验失败原因：" + err);
    assertFalse(store.isCustomized(id));
  }

  @Test
  void clearRemovesAllOverrides() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    for (BuiltinAgent preset : BuiltinAgents.all()) {
      store.save(preset.withPrompt(preset.getPrompt() + " x"));
    }
    assertTrue(store.isCustomized(anyBuiltinId()));

    store.clear();
    for (BuiltinAgent preset : BuiltinAgents.all()) {
      assertFalse(store.isCustomized(preset.getId()), "clear 后不应残留覆盖：" + preset.getId());
    }
  }

  @Test
  void findReturnsNullForBlankOrUnknownId() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    assertNull(store.find(null));
    assertNull(store.find(""));
    assertNull(store.find("   "));
    assertNull(store.find("definitely-not-a-builtin"));
    assertNotNull(store.find(anyBuiltinId()));
  }

  @Test
  void enabledOnlyReturnsUsableAgents() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    // 禁用全部 → enabled() 应为空（isUsable 要求 enabled）。
    for (BuiltinAgent preset : BuiltinAgents.all()) {
      store.save(preset.withEnabled(false));
    }
    assertTrue(store.enabled().isEmpty(), "全部禁用后可派遣列表应为空");

    store.clear();
    assertFalse(store.enabled().isEmpty(), "恢复默认后应有可派遣的 agent");
    for (BuiltinAgent a : store.enabled()) {
      assertTrue(a.isUsable(), "enabled() 只能含 isUsable 的 agent");
    }
  }

  @Test
  void persistsOverridesAcrossInstances(@TempDir Path dir) throws Exception {
    File file = dir.resolve("builtin_agents.json").toFile();
    String id = anyBuiltinId();

    BuiltinAgentStore first = new BuiltinAgentStore(file);
    first.save(editedCopy(id));
    assertTrue(file.isFile(), "save 应落盘");

    // 新实例读回：覆盖仍在（这是「跨启动保留用户修改」的保证）。
    BuiltinAgentStore second = new BuiltinAgentStore(file);
    assertTrue(second.isCustomized(id), "重新加载后覆盖应保留");
    assertEquals(
        editedCopy(id).getPrompt(), second.find(id).getPrompt(), "覆盖内容应逐字保留");
  }

  @Test
  void corruptFileDegradesToDefaultsWithoutThrowing(@TempDir Path dir) throws Exception {
    File file = dir.resolve("builtin_agents.json").toFile();
    Files.write(file.toPath(), "{ not json at all".getBytes(StandardCharsets.UTF_8));

    // 关键：损坏文件不能让构造抛异常——内置 agent 是扩展能力，
    // 配置坏掉不该让整个 AI 功能不可用（见类注释的静默降级约定）。
    BuiltinAgentStore store = new BuiltinAgentStore(file);
    assertFalse(store.all().isEmpty(), "损坏配置下应回落到默认值");
    assertNotNull(store.find(anyBuiltinId()));
  }

  @Test
  void loadDropsEntriesWithUnknownIdOrMissingFields(@TempDir Path dir) throws Exception {
    File file = dir.resolve("builtin_agents.json").toFile();
    String knownId = anyBuiltinId();
    // ① 未知 id 的残渣（旧版本移除的内置 agent）② 缺 name/prompt 的残缺记录
    //    ③ 一条合法覆盖 —— 只应保留第 ③ 条。
    String json =
        "[{\"id\":\"removed-agent\",\"name\":\"旧\",\"prompt\":\""
            + "p".repeat(50)
            + "\"},{\"id\":\""
            + knownId
            + "\",\"name\":\"\",\"prompt\":\"\"},{\"id\":\""
            + knownId
            + "\",\"name\":\"用户改的\",\"description\":\"d\",\"prompt\":\""
            + "q".repeat(50)
            + "\",\"enabled\":true,\"tools\":[\"file_read\"]}]";
    Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));

    BuiltinAgentStore store = new BuiltinAgentStore(file);
    assertFalse(store.isCustomized("removed-agent"), "未知 id 的残渣应被丢弃");
    assertTrue(store.isCustomized(knownId), "合法覆盖应被保留");
    assertEquals("用户改的", store.find(knownId).getName());
  }

  @Test
  void nullFileStoreNeverPersistsAndStaysUsable() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    assertNull(store.getFile());
    String id = anyBuiltinId();
    // 无文件时必须仍可读写内存态（persist 早返回，不能抛）。
    assertEquals("", store.save(editedCopy(id)));
    assertTrue(store.isCustomized(id));
    assertFalse(store.all().isEmpty());
  }
}
