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

/**
 * shell 命令的执行后端。
 *
 * <p>不同后端提供不同级别的权限，因此需要抽象：
 * <ul>
 *   <li>{@code termux} — 复用 IDE 内置终端，在 app 自身 uid 下执行；无需额外安装
 *   <li>{@code shizuku} — 通过 Shizuku 获得 adb 级权限（uid 2000），可执行
 *       {@code pm install} / {@code am start} / {@code logcat} 等
 * </ul>
 *
 * <p><b>重要约定</b>：后端不可用时必须通过 {@link #unavailableReason()} 说明原因，
 * 而不是静默失败或降级。模型需要知道「命令为什么没执行」，否则会误判执行结果
 * （例如以为 APK 已安装）。调用方在 {@link #isAvailable()} 为 false 时不应调用 execute。
 *
 * <p>实现方需保证 {@link #execute} 是线程安全的，且不会抛出异常——失败以
 * {@link ShellRequest.ShellResult} 的错误形态返回。
 */
public interface ShellBackend {

  /** 后端标识，用于配置持久化与注册表查找。 */
  String id();

  /** 展示名，用于设置界面。 */
  String displayName();

  /** 当前是否可用。不可用时调用方应改用其它后端或向用户报告。 */
  boolean isAvailable();

  /**
   * 不可用的原因，面向模型与用户的可读说明。
   *
   * <p>可用时返回空串。这个文本会被放进工具结果，因此要具体
   * （例如「Shizuku 未安装或未授权」而非「不可用」）。
   */
  String unavailableReason();

  /**
   * 执行命令。
   *
   * @param request 命令、工作目录、超时
   * @param sink 输出增量回调，可为 {@link ShellRequest.ShellOutputSink#NOOP}
   * @return 执行结果，含退出码与输出；失败时用 {@link ShellRequest.ShellResult#error}
   */
  ShellRequest.ShellResult execute(ShellRequest request, ShellRequest.ShellOutputSink sink);

  /**
   * 该后端是否具备 adb 级权限。
   *
   * <p>用于工具判断能否执行 {@code pm install} 之类的特权命令。
   * 默认 false；Shizuku 后端覆写为 true。
   */
  default boolean hasAdbPrivileges() {
    return false;
  }
}
