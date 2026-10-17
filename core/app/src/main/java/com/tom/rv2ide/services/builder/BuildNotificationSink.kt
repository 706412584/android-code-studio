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

package com.tom.rv2ide.services.builder

/**
 * 构建通知的接收方。
 *
 * <p><b>为什么单独抽接口</b>：构建状态（空闲/进行中/成功/失败）由
 * {@link ToolingConnection} 产生，而「怎么展示」属于宿主——编辑器连接用前台
 * Service 的常驻通知，后台连接将来可能用独立的 tag/通知 id（每项目一条）。
 * 把两者解耦后，连接层不再依赖 `Service`，可以在纯 JVM 侧构造与测试。
 *
 * <p>实现方必须保证线程安全：回调来自 tooling 线程与协程。
 */
internal interface BuildNotificationSink {

  /** 构建状态变化。 */
  fun update(state: BuildNotificationState)

  /** tooling server 进程退出。宿主通常在此停止前台状态。 */
  fun onServerExited()
}

/** 构建状态；对应通知文案由宿主映射到字符串资源。 */
internal enum class BuildNotificationState {
  /** 空闲（Service 刚创建）。 */
  IDLE,
  /** 构建进行中。 */
  IN_PROGRESS,
  /** 构建成功。 */
  SUCCESS,
  /** 构建失败。 */
  FAILED,
}
