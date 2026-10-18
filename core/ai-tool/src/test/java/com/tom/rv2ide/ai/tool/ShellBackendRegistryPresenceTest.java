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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link ShellBackendRegistry#available()} 的「廉价枚举」语义。
 *
 * <p><b>回归背景</b>：该方法的调用方是「生成工具提示词 / 判断有没有后端可用」，
 * 原先对每个后端调 {@link ShellBackend#isAvailable()}。root 后端的可用性探测要
 * fork {@code su}（首次还会弹 Magisk 授权框），于是**即使用户选的是 Termux**，
 * 每次 agent 运行也会触发一次 root 探测；失败缓存过期后还会反复触发。
 *
 * <p>改为 {@link ShellBackend#isPresent()}：只查文件存在，不起进程。
 */
final class ShellBackendRegistryPresenceTest {

  /** 探测次数可观测的 root 后端（候选路径都不存在，只走 isPresent 的快速路径）。 */
  private static RootShellBackend rootBackend(AtomicInteger probes) {
    return new RootShellBackend(
        Arrays.asList("/definitely/missing/su", "/also/missing/su"),
        new RootShellBackend.ProcessStarter() {
          @Override
          public Process start(List<String> argv, String cwd, String stdin) throws IOException {
            probes.incrementAndGet();
            throw new IOException("should not be called");
          }
        },
        1_000L,
        60_000L,
        10_000L);
  }

  @Test
  void availableDoesNotProbeRootBackend() {
    AtomicInteger probes = new AtomicInteger();
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(rootBackend(probes));

    assertTrue(registry.available().isEmpty(), "候选路径都不存在，不应视为存在");
    assertEquals(0, probes.get(), "available() 不应 fork su");
  }

  @Test
  void resolveStillPerformsRealProbe() {
    AtomicInteger probes = new AtomicInteger();
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(rootBackend(probes));

    // 真正执行命令前必须做真实探测，否则「文件存在但未授权」会被误判为可用。
    ShellBackendRegistry.Resolution resolution = registry.resolve("root");

    assertFalse(resolution.isUsable());
    assertTrue(probes.get() > 0, "resolve() 必须真实探测");
  }
}
