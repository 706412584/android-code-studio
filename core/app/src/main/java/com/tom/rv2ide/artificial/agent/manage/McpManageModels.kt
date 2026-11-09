/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.manage

import com.tom.rv2ide.artificial.agent.McpServers

/**
 * MCP 服务端管理页的渲染层条目。
 *
 * <p><b>为什么保留 args/env/cwd</b>：ACS 的 MCP 支持 stdio 传输，其 args（命令参数）、
 * env（附加环境变量）、cwd（工作目录）是真实功能，不能因为页面化丢掉——丢一个字段会让
 * 用户已配好的本地 MCP server 起不来，且症状难以归因。这三项仅 stdio 时有意义。
 *
 * <p>存储侧用 `url` 作主键（[McpServers.remove] 按 url 匹配），因此 [id] 即 url。
 */
data class McpServerEntry(
    /** 主键：HTTP/SSE 为端点 URL，stdio 为启动命令。与 McpServers.Server.url 对应。 */
    val id: String,
    val label: String,
    /** http / sse / stdio */
    val transport: String,
    val enabled: Boolean,
    /** stdio 命令参数（空格分隔存储，编辑页以文本呈现）。 */
    val args: String,
    /** stdio 环境变量；每行 `KEY=VALUE`。 */
    val env: String,
    /** stdio 工作目录；空串继承父进程。 */
    val cwd: String,
) {
  /** 展示名：优先 label，否则地址/命令。 */
  val displayName: String get() = label.ifBlank { id }

  val isStdio: Boolean get() = transport == McpServers.TYPE_STDIO

  /** 回写成存储类型。 */
  fun toServer(): McpServers.Server {
    if (!isStdio) {
      return McpServers.Server(id, label, enabled, transport)
    }
    val argList = splitArgs(args)
    val envMap =
        env.trim()
            .lines()
            .mapNotNull { line ->
              val kv = line.split('=', limit = 2)
              if (kv.size == 2 && kv[0].trim().isNotEmpty()) kv[0].trim() to kv[1].trim() else null
            }
            .toMap()
    return McpServers.Server(id, label, enabled, transport, argList, envMap, cwd.trim())
  }

  companion object {
    fun of(server: McpServers.Server): McpServerEntry =
        McpServerEntry(
            id = server.url,
            label = server.label,
            transport = server.type,
            enabled = server.enabled,
            args = joinArgs(server.args),
            env = server.env.entries.joinToString("\n") { "${it.key}=${it.value}" },
            cwd = server.cwd,
        )

    /** 新建草稿。 */
    fun draft(transport: String = McpServers.TYPE_HTTP): McpServerEntry =
        McpServerEntry(
            id = "",
            label = "",
            transport = transport,
            enabled = true,
            args = "",
            env = "",
            cwd = "",
        )

    /**
     * 参数列表 → 编辑框文本。含空格的参数用双引号包起来，否则回写时会被拆成两个。
     */
    fun joinArgs(args: List<String>): String =
        args.joinToString(" ") { arg -> if (arg.any { it.isWhitespace() }) "\"$arg\"" else arg }

    /**
     * 编辑框文本 → 参数列表。空白分隔，但**双引号内的空白不切**。
     *
     * <p>此前直接按 `\\s+` 切：`--model "my model"` 会变成两个参数，用户打开编辑页
     * 什么都不改直接保存，一份本来能跑起来的 stdio 配置就被改坏了。
     * 只支持双引号（这是编辑框里唯一可预期的书写方式）；未闭合的引号按「一直到末尾都是
     * 同一个参数」处理，比静默丢弃剩余文本更好。
     */
    fun splitArgs(text: String): List<String> {
      val out = mutableListOf<String>()
      val current = StringBuilder()
      var quoted = false
      var hasToken = false
      for (ch in text) {
        when {
          ch == '"' -> {
            quoted = !quoted
            hasToken = true
          }
          ch.isWhitespace() && !quoted -> {
            if (hasToken) {
              out.add(current.toString())
              current.setLength(0)
              hasToken = false
            }
          }
          else -> {
            current.append(ch)
            hasToken = true
          }
        }
      }
      if (hasToken) out.add(current.toString())
      return out
    }
  }
}

/** 传输类型的展示名与稳定取值（与 McpServers 的常量一致）。 */
internal val MCP_TRANSPORTS: List<Pair<String, String>> =
    listOf(
        McpServers.TYPE_HTTP to "HTTP",
        McpServers.TYPE_SSE to "SSE",
        McpServers.TYPE_STDIO to "stdio",
    )
