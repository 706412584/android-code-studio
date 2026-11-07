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

package com.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistant.ui.compat.AppTextField
import com.assistant.ui.components.independent.SettingsGroup
import com.assistant.ui.components.independent.SettingsGroupHeader
import com.assistant.ui.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2

/** 一条 MCP server（渲染层投影）。 */
data class McpServerItem(
    val id: String,
    val label: String,
    /** HTTP/SSE 为端点 URL；stdio 为启动命令。 */
    val target: String,
    /** http / sse / stdio */
    val transport: String,
    val enabled: Boolean,
)

/**
 * MCP 服务端管理。
 *
 * <p><b>为什么要这个入口</b>：MCP 是本 App 唯一的「外接能力」通道。没有它，
 * 助手的工具集就固定死在内置的十几个；有了它，用户接一个 server 就能多出一批工具。
 *
 * <p><b>失败要可见</b>：某个 server 连不上时只在运行时跳过、界面上不提示的话，
 * 用户会以为「配置生效了但工具没出现」——这是最难排查的状态。因此列表里
 * 显示每条配置，运行日志里记录失败原因。
 */
@Composable
fun AssistantMcpScreen(
    servers: List<McpServerItem>,
    onAdd: (label: String, target: String, transport: String) -> Unit,
    onUpdate: (id: String, label: String, target: String, transport: String, enabled: Boolean) -> Unit,
    onDelete: (id: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  // null = 列表；"" = 新增；其它 = 编辑该 id。
  var editingId by remember { mutableStateOf<String?>(null) }

  AssistantManageScaffold("MCP 服务端", onBack, modifier, darkTheme) {
    if (editingId != null) {
      val existing = servers.firstOrNull { it.id == editingId }
      McpEditForm(
          existing = existing,
          onCancel = { editingId = null },
          onSave = { label, target, transport, enabled ->
            if (existing == null) {
              onAdd(label, target, transport)
            } else {
              onUpdate(existing.id, label, target, transport, enabled)
            }
            editingId = null
          },
      )
    } else {
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = Spacing.md),
      ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = { editingId = "" }) {
            Icon(
                FeatherIcons.Plus,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("添加", modifier = Modifier.padding(start = 4.dp))
          }
        }

        if (servers.isEmpty()) {
          Text(
              text =
                  "还没有接入 MCP server。\n\n" +
                      "MCP（Model Context Protocol）让你把外部工具服务接进助手：\n" +
                      "· HTTP / SSE：填服务端 URL\n" +
                      "· stdio：填启动命令（本 App 用内置 shell 启动）\n\n" +
                      "接入后 server 的工具会出现在模型的工具清单里。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = Spacing.lg),
          )
        } else {
          SettingsGroupHeader(text = "已配置 ${servers.size} 个")
          servers.forEach { item ->
            SettingsGroup(modifier = Modifier.padding(bottom = Spacing.xs)) {
              Row(
                  modifier =
                      Modifier.fillMaxWidth()
                          .clickable { editingId = item.id }
                          .padding(horizontal = Spacing.lg, vertical = 11.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                      text = item.label.ifBlank { item.target },
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurface,
                  )
                  Text(
                      text = "${item.transport.uppercase()} · ${item.target}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                androidx.compose.material3.IconButton(onClick = { onDelete(item.id) }) {
                  Icon(
                      FeatherIcons.Trash2,
                      contentDescription = "删除",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(16.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun McpEditForm(
    existing: McpServerItem?,
    onCancel: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit,
) {
  var label by remember { mutableStateOf(existing?.label ?: "") }
  var target by remember { mutableStateOf(existing?.target ?: "") }
  var transport by remember { mutableStateOf(existing?.transport ?: "http") }
  var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

  // 表单是弹窗，不是页面（与「服务商」编辑表单同一取舍）。
  androidx.compose.ui.window.Dialog(
      onDismissRequest = onCancel,
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
  ) {
  androidx.compose.material3.Surface(
      shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.fillMaxWidth().padding(Spacing.md),
  ) {
  Column(
      modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    Text(
        text = if (existing == null) "添加 MCP 服务端" else "编辑 MCP 服务端",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    AppTextField(
        value = label,
        onValueChange = { label = it },
        label = "名称",
        placeholder = "例如：图像生成服务",
    )
    AppTextField(
        value = target,
        onValueChange = { target = it },
        label = if (transport == "stdio") "启动命令" else "服务端 URL",
        placeholder =
            if (transport == "stdio") "npx -y @modelcontextprotocol/server-filesystem"
            else "https://host/mcp",
    )

    SettingsGroupHeader(text = "传输方式")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      listOf("http" to "HTTP", "sse" to "SSE", "stdio" to "stdio")
          .forEach { (id, name) ->
            val selected = id == transport
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color =
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { transport = id },
            ) {
              Text(
                  text = name,
                  style = MaterialTheme.typography.bodySmall,
                  color =
                      if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              )
            }
          }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      Switch(checked = enabled, onCheckedChange = { enabled = it })
      Text(
          text = "启用",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(start = Spacing.sm),
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      TextButton(
          onClick = { onSave(label.trim(), target.trim(), transport, enabled) }) {
            Text("保存")
          }
      TextButton(onClick = onCancel) { Text("取消") }
    }
  }
  }
  }
}
