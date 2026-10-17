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

package com.tom.rv2ide.artificial.agent.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.tom.rv2ide.ai.tool.ShellBackend;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ShellRequest;
import org.junit.Test;

/**
 * 设备控制命令的后端解析：选中后端不具备 adb 权限时，应回退到任意可用的 adb 后端。
 *
 * <p>背景（真机 E2E 实测，黑鲨 SKW-A0）：shell 后端设置为默认的 {@code termux} 时，
 * 旧逻辑直接拒绝 {@code phone_*} / {@code vscreen} 命令，错误信息还误导用户
 * 「请在 Shizuku 应用中安装并授权」——而 Shizuku 明明已就绪，真正要做的是切换设置。
 * 这些断言钉住修复后的语义：能升级（Termux → Shizuku）就不失败，无 adb 后端时才拒绝。
 * 若有人把实现改回「只看 resolveActive」，前两条用例会失败。
 */
public class PhoneShellRunnerBackendTest {

  /** 可编程后端探针：id / adb 权限 / 可用性都可指定。 */
  private static final class FakeBackend implements ShellBackend {
    private final String id;
    private final boolean adb;
    private final boolean available;

    FakeBackend(String id, boolean adb, boolean available) {
      this.id = id;
      this.adb = adb;
      this.available = available;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String displayName() {
      return id;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return available ? "" : id + " 不可用";
    }

    @Override
    public boolean hasAdbPrivileges() {
      return adb;
    }

    @Override
    public ShellRequest.ShellResult execute(ShellRequest request, ShellRequest.ShellOutputSink sink) {
      return ShellRequest.ShellResult.error("fake");
    }
  }

  @Test
  public void fallsBackToAvailableAdbBackendWhenActiveLacksAdb() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FakeBackend("termux", false, true));
    registry.register(new FakeBackend("shizuku", true, true));
    // 默认选中第一个注册的（termux）——正是真机上出问题的组合。
    assertEquals("termux", registry.activeId());

    ShellBackend resolved = PhoneShellRunner.resolveAdbBackend(registry);

    assertEquals("应回退到具备 adb 权限的 Shizuku", "shizuku", resolved.id());
  }

  @Test
  public void prefersActiveAdbBackendWhenItHasPrivileges() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FakeBackend("termux", false, true));
    registry.register(new FakeBackend("shizuku", true, true));
    registry.setActiveId("shizuku");

    ShellBackend resolved = PhoneShellRunner.resolveAdbBackend(registry);

    assertEquals("选中后端本身有 adb 权限时直接用", "shizuku", resolved.id());
  }

  @Test
  public void returnsNullWhenNoAdbBackendAvailable() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FakeBackend("termux", false, true));
    registry.register(new FakeBackend("shizuku", true, false)); // 装了但未授权/未运行

    assertNull("没有可用 adb 后端时应拒绝（由调用方给出明确错误）",
        PhoneShellRunner.resolveAdbBackend(registry));
  }

  @Test
  public void returnsNullForNullRegistry() {
    assertNull(PhoneShellRunner.resolveAdbBackend(null));
  }

  @Test
  public void doesNotDowngradeToUnavailableAdbBackend() {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FakeBackend("termux", false, true));
    // 唯一 adb 后端不可用：绝不能退而求其次用 Termux 执行特权命令（会产生误导性空结果）。
    assertNull(PhoneShellRunner.resolveAdbBackend(registry));
  }

  @Test
  public void rootBackendIsAnAdbBackend() {
    // 显式 root 后端（su）是 adb 的超集：设备没装 Shizuku 但已 root 时，
    // phone_* / vscreen 应能走 root 通道，而不是报「请安装 Shizuku」。
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new FakeBackend("termux", false, true));
    registry.register(new FakeBackend("root", true, true));

    ShellBackend resolved = PhoneShellRunner.resolveAdbBackend(registry);

    assertEquals("没有可用 Shizuku 时应回退到 root", "root", resolved.id());
  }
}
