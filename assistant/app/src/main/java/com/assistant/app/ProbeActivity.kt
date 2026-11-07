/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.tom.rv2ide.ai.agent.AgentEvent
import com.tom.rv2ide.ai.agent.AgentSession
import com.tom.rv2ide.ai.agent.conversation.FileConversationStore
import com.tom.rv2ide.ai.tool.FileReadTool
import com.tom.rv2ide.ai.tool.ToolContext
import com.tom.rv2ide.ai.tool.ToolExecutor
import com.tom.rv2ide.ai.tool.ToolRegistry
import java.io.File

/**
 * 剥离验证（第 1 步）：**不依赖任何 ACS 模块**，直接消费 `core/ai-*` 内核。
 *
 * <p>本 Activity 刻意只碰内核的公开 API，不发起真实模型请求（那需要服务商配置）。
 * 它要证明的是：内核类能在 Android 运行时**被加载并实例化**——
 * 这比「编译通过」强，因为内核是 `java-library`（JDK 17 编译、Java 8 API 面），
 * 编译过不代表真机能跑（`NoSuchMethodError` 只在设备上暴露）。
 *
 * <p>验证要点：
 * <ul>
 *   <li>[ToolRegistry] / [ToolExecutor] / [FileConversationStore] 能构造；
 *   <li>[AgentSession] 能构造（它引用 ModelClient、ToolRegistry、ToolExecutor）；
 *   <li>[AgentEvent] 的枚举能取到（协议层与事件层都真加载了）；
 *   <li>不依赖 `com.tom.rv2ide.*` 的任何非 ai 包——若这个 Activity 编译通过，
 *       说明内核的传递依赖里没有混进 ACS 类型。
 * </ul>
 */
class ProbeActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val report = StringBuilder()
    var ok = true

    fun probe(name: String, block: () -> String) {
      try {
        report.append("✓ ").append(name).append(": ").append(block()).append('\n')
      } catch (t: Throwable) {
        ok = false
        report.append("✗ ").append(name).append(": ")
            .append(t.javaClass.simpleName).append(" — ").append(t.message).append('\n')
      }
    }

    probe("ToolRegistry") {
      val registry = ToolRegistry()
      registry.register(FileReadTool())
      "${registry.getAll().size} 个工具"
    }

    probe("ToolExecutor") {
      val registry = ToolRegistry()
      registry.register(FileReadTool())
      ToolExecutor(registry, null, null)
      "构造成功"
    }

    probe("FileConversationStore") {
      val dir = File(filesDir, "ai/conversations")
      FileConversationStore(dir)
      "会话存储就绪：${dir.absolutePath}"
    }

    probe("AgentSession") {
      val registry = ToolRegistry()
      registry.register(FileReadTool())
      val executor = ToolExecutor(registry, null, null)
      AgentSession(com.tom.rv2ide.ai.protocol.ModelClient(), registry, executor)
      "构造成功"
    }

    probe("AgentEvent 枚举") {
      "类型数 = ${AgentEvent.Type.values().size}"
    }

    probe("ToolContext") {
      ToolContext.builder().homePath(filesDir.absolutePath).build()
      "构造成功"
    }

    val text = TextView(this).apply {
      text = (if (ok) "内核独立消费：通过\n\n" else "内核独立消费：有失败\n\n") + report
      textSize = 12f
      setPadding(32, 96, 32, 32)
    }
    setContentView(text)
  }
}
