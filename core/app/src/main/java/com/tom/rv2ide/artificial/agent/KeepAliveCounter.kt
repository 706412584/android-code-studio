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

package com.tom.rv2ide.artificial.agent

/**
 * 保活引用计数。
 *
 * <p><b>为什么需要它</b>：agent 运行与后台 shell 任务都会要求「息屏后别冻住进程」，
 * 但两者的起止互相独立——后台任务可以比发起它的那次运行活得更久，两次运行也可能
 * 首尾相接。若各自直接启停前台服务，先结束的一方会把仍需要保活的另一方一起关掉
 * （或触发一次无意义的「停→立刻再启」闪烁）。
 *
 * <p>引用计数把「有几个持有者」与「是否要拉起服务」分开：
 * <ul>
 *   <li>[acquire] 返回 true 表示计数 0→1，调用方应拉起前台服务；
 *   <li>[release] 返回 true 表示计数 1→0，调用方应停止前台服务；
 *   <li>其余情况只改计数、不动服务。
 * </ul>
 *
 * <p><b>线程安全</b>：acquire/release 分别来自 agent 循环线程与后台任务线程，
 * 必然并发；用锁而非原子类，因为判定与自增必须是一个原子操作——先读后改会丢信号。
 *
 * <p><b>为什么与 Android API 完全解耦</b>：只做计数，不碰 Service/PowerManager，
 * 因此可以在 JVM 上直接单测，不需要 Robolectric。
 */
class KeepAliveCounter {

  /** 当前持有者数量。只在 [lock] 内读写。 */
  private var count = 0

  private val lock = Any()

  /**
   * 申请一个保活名额。
   *
   * @return true 表示这是第一个持有者（0→1），调用方应启动前台服务
   */
  fun acquire(): Boolean = synchronized(lock) {
    count++
    count == 1
  }

  /**
   * 释放一个保活名额。
   *
   * <p>计数已为 0 时不再递减并返回 false：这意味着调用方多释放了一次
   * （重复 release 或未配对 acquire），把它挡在计数之外可避免负数
   * 导致后续 acquire 永远等不到 0→1、服务再也起不来。
   *
   * @return true 表示这是最后一个持有者（1→0），调用方应停止前台服务
   */
  fun release(): Boolean = synchronized(lock) {
    if (count == 0) {
      return@synchronized false
    }
    count--
    count == 0
  }

  /** 当前持有者数量（测试与诊断用）。 */
  fun current(): Int = synchronized(lock) { count }
}
