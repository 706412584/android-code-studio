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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.artificial.agent.compose.compat.AppTextField
import com.tom.rv2ide.artificial.agent.compose.components.independent.SettingsGroup
import com.tom.rv2ide.artificial.agent.compose.components.independent.SettingsGroupHeader
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.resources.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.launch

/**
 * MCP 服务端管理页：**列表与编辑同页两态**，取代原先「列表弹窗 → 动作弹窗 → 表单弹窗」。
 *
 * <p>列表行内可启停（Switch）／点行编辑／点图标删除（带确认）。编辑页完整保留
 * http/sse/stdio 三态与 stdio 的 args/env/cwd 字段。
 */
@Composable
fun McpManageScreen(
    servers: List<McpServerEntry>,
    // originalId = 编辑前的存储主键（新建时为 null）。**必须传**：stdio 的 id 是启动命令、
    // HTTP 的 id 是 URL，用户在编辑页改了它们就等于换了主键；只按新 id 移除会留下旧记录，
    // 列表里出现两条同配置的 server。
    onSave: suspend (originalId: String?, item: McpServerEntry) -> String?,
    onDelete: suspend (String) -> Unit,
    onBack: () -> Unit,
) {
  var mode by remember { mutableStateOf<String?>(null) }

  // 系统返回：非列表态先回列表，列表态才退出页面。
  androidx.activity.compose.BackHandler(enabled = mode != null) { mode = null }

  val editing = mode
  if (editing != null) {
    val isNew = editing == "new"
    val existing = if (isNew) null else servers.firstOrNull { it.id == editing }
    McpEditPage(
        existing = existing,
        isNew = isNew,
        onSave = onSave,
        onCancel = { mode = null },
        // 保存成功回到列表：停在编辑页且无任何提示，用户会以为没保存上。
        onSaved = { mode = null },
    )
    return
  }

  val scope = rememberCoroutineScope()
  var pendingDelete by remember { mutableStateOf<McpServerEntry?>(null) }

  ManagePage(
      title = stringResource(R.string.ai_agent_mcp_title),
      onBack = onBack,
      actions = {
        TextButton(onClick = { mode = "new" }) {
          Icon(
              FeatherIcons.Plus,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.primary,
          )
          Text(
              stringResource(R.string.manage_add),
              modifier = Modifier.padding(start = 4.dp),
          )
        }
      },
  ) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
    ) {
      if (servers.isEmpty()) {
        Text(
            text = stringResource(R.string.manage_mcp_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.lg),
        )
      } else {
        SettingsGroupHeader(
            text = stringResource(R.string.manage_configured_count, servers.size)
        )
        servers.forEach { item ->
          // 卡片色取 IDE 主题的 colorSurfaceContainer，与页面背景同源。
          SettingsGroup(
              modifier = Modifier.padding(bottom = Spacing.xs),
              color = ideThemeColor(com.tom.rv2ide.R.attr.colorSurfaceContainer),
          ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { mode = item.id }
                        .padding(start = Spacing.lg, end = Spacing.xs, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.transport.uppercase()} · ${item.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
              }
              // 行内启停：改 enabled 保留其余字段（toServer 会带上 args/env/cwd）。
              // 主键没变，originalId 与 item.id 相同。
              Switch(
                  checked = item.enabled,
                  onCheckedChange = { checked ->
                    scope.launch { onSave(item.id, item.copy(enabled = checked)) }
                  },
              )
              IconButton(onClick = { pendingDelete = item }) {
                Icon(
                    FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.delete),
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

  val target = pendingDelete
  if (target != null) {
    AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.manage_mcp_remove_title)) },
        text = {
          Text(stringResource(R.string.manage_mcp_remove_confirm, target.displayName))
        },
        confirmButton = {
          TextButton(
              onClick = {
                pendingDelete = null
                scope.launch { onDelete(target.id) }
              }) {
                Text(
                    stringResource(R.string.ai_agent_mcp_remove),
                    color = MaterialTheme.colorScheme.error,
                )
              }
        },
        dismissButton = {
          TextButton(onClick = { pendingDelete = null }) {
            Text(stringResource(R.string.cancel))
          }
        },
    )
  }
}

@Composable
private fun McpEditPage(
    existing: McpServerEntry?,
    isNew: Boolean,
    onSave: suspend (originalId: String?, item: McpServerEntry) -> String?,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
  val key = existing?.id ?: "new"
  var label by remember(key) { mutableStateOf(existing?.label ?: "") }
  var transport by remember(key) { mutableStateOf(existing?.transport ?: "http") }
  var target by remember(key) { mutableStateOf(existing?.id ?: "") }
  var enabled by remember(key) { mutableStateOf(existing?.enabled ?: true) }
  var args by remember(key) { mutableStateOf(existing?.args ?: "") }
  var env by remember(key) { mutableStateOf(existing?.env ?: "") }
  var cwd by remember(key) { mutableStateOf(existing?.cwd ?: "") }

  var targetError by remember(key) { mutableStateOf<String?>(null) }
  var saveError by remember(key) { mutableStateOf<String?>(null) }
  var saving by remember(key) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  val isStdio = transport == "stdio"
  val targetLabel =
      stringResource(
          if (isStdio) R.string.manage_mcp_target_command else R.string.manage_mcp_target_url
      )
  // onConfirm 不是 Composable lambda，带占位符的文案必须在 Composable 作用域先取出来。
  val targetRequired = stringResource(R.string.manage_mcp_target_required, targetLabel)
  // 启动命令只填**可执行名**，参数填在下面的「命令参数」里：这一项会原样当命令名
  // 交给 shell，写成 `npx -y xxx` 整串会找不到可执行文件。
  val targetPlaceholder = if (isStdio) "npx" else "https://host/mcp"

  ManagePage(
      title =
          stringResource(
              if (isNew) R.string.manage_mcp_add_title else R.string.manage_mcp_edit_title
          ),
      onBack = onCancel,
      bottomBar = {
        ManageBottomBar(
            onCancel = onCancel,
            confirming = saving,
            onConfirm = {
              targetError = if (target.isBlank()) targetRequired else null
              if (targetError != null) return@ManageBottomBar
              val item =
                  (existing ?: McpServerEntry.draft()).copy(
                      id = target.trim(),
                      label = label.trim(),
                      transport = transport,
                      enabled = enabled,
                      // 非 stdio 时清掉三项，避免切换类型后残留旧值被写进存储。
                      args = if (isStdio) args.trim() else "",
                      env = if (isStdio) env.trim() else "",
                      cwd = if (isStdio) cwd.trim() else "",
                  )
              saving = true
              saveError = null
              scope.launch {
                val error = onSave(existing?.id, item)
                saving = false
                if (error != null) saveError = error else onSaved()
              }
            },
        )
      },
  ) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      AppTextField(
          value = label,
          onValueChange = { label = it },
          label = stringResource(R.string.ai_agent_mcp_label_hint),
          placeholder = stringResource(R.string.manage_mcp_name_hint),
      )
      AppTextField(
          value = target,
          onValueChange = {
            target = it
            targetError = null
          },
          label = targetLabel,
          placeholder = targetPlaceholder,
          isError = targetError != null,
          supportingText = targetError?.let { { Text(it) } },
      )

      SettingsGroupHeader(text = stringResource(R.string.ai_agent_mcp_type_label))
      Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MCP_TRANSPORTS.forEach { (id, name) ->
          val selected = id == transport
          Surface(
              shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
              color =
                  if (selected) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceVariant,
              modifier =
                  Modifier.clickable {
                    // 只切类型，不清空未提交草稿（切回 stdio 时 args/env/cwd 还在）。
                    transport = id
                    targetError = null
                  },
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

      if (isStdio) {
        AppTextField(
            value = args,
            onValueChange = { args = it },
            label = stringResource(R.string.ai_agent_mcp_stdio_args),
            placeholder = "--port 8080 --verbose",
        )
        AppTextField(
            value = env,
            onValueChange = { env = it },
            label = stringResource(R.string.ai_agent_mcp_stdio_env),
            placeholder = stringResource(R.string.manage_mcp_env_hint),
            singleLine = false,
            minLines = 3,
            maxLines = 6,
        )
        AppTextField(
            value = cwd,
            onValueChange = { cwd = it },
            label = stringResource(R.string.ai_agent_mcp_stdio_cwd),
            placeholder = stringResource(R.string.manage_mcp_cwd_hint),
        )
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = enabled, onCheckedChange = { enabled = it })
        Text(
            text = stringResource(R.string.ai_agent_mcp_enable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.sm),
        )
      }

      if (saveError != null) {
        Text(
            text = saveError!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}
