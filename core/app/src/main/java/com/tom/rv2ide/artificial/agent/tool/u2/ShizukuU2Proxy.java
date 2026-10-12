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

package com.tom.rv2ide.artificial.agent.tool.u2;

import com.tom.rv2ide.ai.tool.U2ProxyPort;
import java.util.function.BooleanSupplier;

/**
 * {@link U2ProxyPort} 的实现：全部委托给 {@link U2ServerManager}。
 *
 * <p>这一层只做「端口 → app 层实现」的适配，不含逻辑——保持端口接口的
 * 纯 JVM 可测性（工具层用 FakePort 测试），app 层实现集中在一处维护。
 */
public final class ShizukuU2Proxy implements U2ProxyPort {

  private final U2ServerManager manager;

  public ShizukuU2Proxy(U2ServerManager manager) {
    this.manager = manager;
  }

  @Override
  public Result call(String method, String paramsJson, long timeoutMs, BooleanSupplier cancelled) {
    return manager.call(method, paramsJson, timeoutMs, cancelled);
  }

  @Override
  public boolean isAvailable() {
    return manager.isAvailable();
  }

  @Override
  public String unavailableReason() {
    return manager.unavailableReason();
  }

  @Override
  public void stop() {
    manager.stop();
  }

  @Override
  public String restart() {
    return manager.restart();
  }

  @Override
  public void onActionFinished() {
    manager.onActionFinished();
  }
}
