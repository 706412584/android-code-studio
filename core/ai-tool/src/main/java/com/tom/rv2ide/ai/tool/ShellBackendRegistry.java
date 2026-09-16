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

package com.tom.rv2ide.ai.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * shell 后端的注册表：登记可用后端，并记录当前选中的那个。
 *
 * <p>选择策略：配置指定的后端不可用时，{@link #resolveActive()} 会回退到第一个可用后端，
 * 并把回退原因一并返回，让调用方（工具）能如实告知模型实际用了哪个后端。
 * 这个「如实报告」是必要的——用 Termux 执行 {@code pm install} 必然失败，
 * 模型需要知道它拿到的不是 adb 权限。
 */
public final class ShellBackendRegistry {

  /** 解析结果：选中的后端，以及（若发生回退）回退原因。 */
  public static final class Resolution {
    private final ShellBackend backend;
    private final String fallbackReason;

    Resolution(ShellBackend backend, String fallbackReason) {
      this.backend = backend;
      this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
    }

    public ShellBackend getBackend() {
      return backend;
    }

    /** 非空表示发生了回退，内容为原后端不可用的原因。 */
    public String getFallbackReason() {
      return fallbackReason;
    }

    public boolean isFallback() {
      return !fallbackReason.isEmpty();
    }

    public boolean isUsable() {
      return backend != null;
    }
  }

  private final Map<String, ShellBackend> backends = new LinkedHashMap<>();
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private volatile String activeId = "";

  /** 登记一个后端。同名会覆盖。 */
  public void register(ShellBackend backend) {
    if (backend == null || backend.id().isEmpty()) {
      return;
    }
    lock.writeLock().lock();
    try {
      backends.put(backend.id(), backend);
      if (activeId.isEmpty()) {
        activeId = backend.id();
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** 按 id 取后端；不存在返回 null。 */
  public ShellBackend get(String id) {
    lock.readLock().lock();
    try {
      return backends.get(id);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** 全部已登记后端（含不可用的，供设置界面展示状态）。 */
  public List<ShellBackend> all() {
    lock.readLock().lock();
    try {
      return Collections.unmodifiableList(new ArrayList<>(backends.values()));
    } finally {
      lock.readLock().unlock();
    }
  }

  /** 仅当前可用的后端。 */
  public List<ShellBackend> available() {
    List<ShellBackend> result = new ArrayList<>();
    for (ShellBackend backend : all()) {
      if (backend.isAvailable()) {
        result.add(backend);
      }
    }
    return result;
  }

  /** 配置中选定的后端 id。 */
  public String activeId() {
    return activeId;
  }

  /** 切换选定的后端；id 不存在时忽略。 */
  public void setActiveId(String id) {
    if (id == null) {
      return;
    }
    lock.readLock().lock();
    try {
      if (backends.containsKey(id)) {
        activeId = id;
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * 解析出实际可用的后端。
   *
   * <p>选定的后端可用则直接返回；否则回退到第一个可用后端，并在
   * {@link Resolution#getFallbackReason()} 中说明原因。全部不可用时返回的后端为 null。
   */
  public Resolution resolveActive() {
    ShellBackend selected = get(activeId);

    if (selected != null && selected.isAvailable()) {
      return new Resolution(selected, "");
    }

    String reason =
        selected == null
            ? "未配置 shell 后端。"
            : selected.displayName() + " 不可用: " + selected.unavailableReason();

    for (ShellBackend candidate : all()) {
      if (candidate.isAvailable()) {
        return new Resolution(candidate, reason);
      }
    }
    return new Resolution(null, reason + " 且没有其它可用后端。");
  }
}
