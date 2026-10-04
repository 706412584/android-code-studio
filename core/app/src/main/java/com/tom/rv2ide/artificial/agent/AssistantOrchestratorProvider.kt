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

import android.content.Context
import com.tom.rv2ide.app.BaseApplication

/**
 * 进程级 [AgentOrchestrator] 单例。
 *
 * <p><b>为什么需要它（问题）</b>：助手有四个宿主——主页悬浮（SIDEBAR）、内联页
 * （INLINE）、应用外系统悬浮（[com.tom.rv2ide.services.AssistantOverlayService]）、
 * 真全屏 Activity（[com.tom.rv2ide.activities.editor.AssistantFullscreenActivity]）。
 * [FloatingAssistantView] 的 `sharedOrchestrator` 为 null 时每个视图**自建**一个实例
 * （见其 `orchestrator` 的 lazy）。此前只有主页与内联页经 [com.tom.rv2ide.viewmodel.MainViewModel]
 * 共用一个实例，另两个宿主没注入 → 会话列表、正在跑的任务、上下文用量、工作区全对不上：
 * 用户在应用外悬浮里开了一段对话，切回应用内看到的是另一条。
 *
 * <p><b>为什么不能用 Activity 作用域的实例</b>：应用外悬浮跑在
 * [com.tom.rv2ide.services.AssistantOverlayService] 里，**没有 Activity**，
 * 取不到 `MainViewModel`。必须有一个不依赖 Activity 的、进程内唯一的来源。
 *
 * <p><b>生命周期</b>：单例持 [Context.getApplicationContext]，与进程同寿。orchestrator
 * 内部只用 Context 读偏好、写会话目录（见其构造），不需要 Activity 资源，因此不会泄漏。
 *
 * <p><b>并发</b>：agent 循环与视图都在主线程，但为防御将来有人在别的线程首次取用，
 * 初始化加锁；`@Volatile` 保证双重检查下的可见性。整个进程**只创建一次**——这与
 * [FloatingAssistantView] 「orchestrator 持有当前会话 id，新建会让历史断掉」的约束一致。
 */
object AssistantOrchestratorProvider {

  @Volatile private var instance: AgentOrchestrator? = null

  /**
   * 取得进程唯一的 orchestrator，首次调用时创建。
   *
   * <p>不接收参数：单例必须是**无参**的，否则不同调用点可能传入不同的 Context，
   * 而「进程唯一」的语义正是不允许存在第二个实例。Context 一律从
   * [BaseApplication.getBaseInstance] 取（与 [MainViewModel] 原实现同源，避免
   * Activity context 泄漏）。
   */
  @JvmStatic
  fun get(): AgentOrchestrator =
      instance
          ?: synchronized(this) {
            instance
                ?: AgentOrchestrator(
                        BaseApplication.getBaseInstance(),
                        AgentOrchestrator.defaultDiffStore(BaseApplication.getBaseInstance()),
                    )
                    .also { instance = it }
          }
}
