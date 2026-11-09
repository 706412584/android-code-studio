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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.artificial.agent.McpServers
import com.tom.rv2ide.artificial.agent.ModelCatalogFetcher
import com.tom.rv2ide.artificial.agent.ProviderConfigStore
import com.tom.rv2ide.artificial.agent.compose.theme.AIEditorTheme
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.resources.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 助手设置的管理页面宿主（分区式）。
 *
 * <p><b>为什么一个 Activity 承载多个分区</b>：AI 设置里的服务商、MCP、记忆、Skill…
 * 都是「列表 + 编辑」的同一形态。给每个分区各建一个 Activity 会让 manifest 膨胀、
 * 主题/insets 配置重复。这里按 [EXTRA_SECTION] 分区渲染，单一宿主、单一编译单元。
 *
 * <p>数据仍来自 ACS 各自的存储（[ProviderConfigStore] / [McpServers]），UI 复用 ACS
 * 本地 Compose 原子——不引 assistant:ui 模块。
 */
class AssistantManageActivity : EdgeToEdgeIDEActivity() {

  private val section: String
    get() = intent?.getStringExtra(EXTRA_SECTION) ?: SECTION_PROVIDERS

  override fun bindLayout(): View = android.widget.FrameLayout(this)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val section = this.section
    setContent {
      AIEditorTheme(followHostFrame = false) {
        when (section) {
          SECTION_MCP -> McpSection(this@AssistantManageActivity)
          else -> ProviderSection(this@AssistantManageActivity)
        }
      }
    }
  }

  companion object {
    const val EXTRA_SECTION = "assistant_manage_section"
    const val SECTION_PROVIDERS = "providers"
    const val SECTION_MCP = "mcp"

    /** 打开指定分区的管理页面。非 Activity 上下文自动补 NEW_TASK。 */
    fun start(context: Context, section: String) {
      val intent = Intent(context, AssistantManageActivity::class.java)
      intent.putExtra(EXTRA_SECTION, section)
      if (context !is android.app.Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }
  }
}

/** 服务商分区。 */
@androidx.compose.runtime.Composable
private fun ProviderSection(activity: AssistantManageActivity) {
  val reload = remember { mutableStateOf(0) }
  val store = remember { ProviderConfigStore(activity) }
  val records = remember(reload.value) { store.load().map { ManageProviderItem.of(it) } }
  val presets = remember { com.tom.rv2ide.artificial.agent.ProviderPresets.all() }

  // onSave / onFetchModels 都不是 Composable lambda，文案先在作用域内取好。
  val saveFailedText = stringResource(R.string.manage_save_failed)
  val unknownErrorText = stringResource(R.string.manage_unknown_error)
  val resources = activity.resources

  ProviderManageScreen(
      records = records,
      presets = presets,
      onSave = { item ->
        val error =
            withContext(Dispatchers.IO) {
              try {
                val config = item.toConfig()
                store.upsert(config)
                // 主模型改动后同步「当前选择」，避免界面显示 B、请求用 A。
                syncCurrentSelection(activity, config)
                null
              } catch (e: Exception) {
                e.message ?: saveFailedText
              }
            }
        if (error == null) {
          reload.value++
          ManageChangeSignal.markChanged()
        }
        error
      },
      onDelete = { id ->
        withContext(Dispatchers.IO) { store.delete(id) }
        reload.value++
        ManageChangeSignal.markChanged()
      },
      onFetchModels = { item ->
        withContext(Dispatchers.IO) {
          try {
            ModelCatalogFetcher.fetch(item.toConfig()) to ""
          } catch (e: Exception) {
            emptyList<String>() to
                resources.getString(R.string.manage_fetch_failed, e.message ?: unknownErrorText)
          }
        }
      },
      onBack = { activity.finish() },
  )
}

/** MCP 分区。 */
@androidx.compose.runtime.Composable
private fun McpSection(activity: AssistantManageActivity) {
  val reload = remember { mutableStateOf(0) }
  val store = remember { McpServers(activity) }
  val servers = remember(reload.value) { store.all().map { McpServerEntry.of(it) } }
  val saveFailedText = stringResource(R.string.manage_save_failed)

  McpManageScreen(
      servers = servers,
      onSave = { originalId, item ->
        withContext(Dispatchers.IO) {
          try {
            val server = item.toServer()
            // McpServers 没有 upsert：先移除旧条目再追加（顺序变化可接受）。
            // 移除要用**编辑前的主键**：用户在编辑页改了 URL/启动命令就等于换了主键，
            // 按新主键移除会找不到旧记录，于是留下重复条目。新建时 originalId 为 null。
            if (originalId != null) store.remove(originalId)
            store.add(server)
            null
          } catch (e: Exception) {
            e.message ?: saveFailedText
          }
        }
        .also {
          reload.value++
          ManageChangeSignal.markChanged()
        }
      },
      onDelete = { id ->
        withContext(Dispatchers.IO) { store.remove(id) }
        reload.value++
        ManageChangeSignal.markChanged()
      },
      onBack = { activity.finish() },
  )
}

/** 同步「当前正在用的模型」为刚保存记录的主模型（仅当该记录是当前服务商）。 */
private fun syncCurrentSelection(
    activity: AssistantManageActivity,
    config: com.tom.rv2ide.artificial.agent.ProviderConfig,
) {
  val agents = Agents(activity)
  if (agents.getProvider() != config.getId()) return
  val main = com.tom.rv2ide.artificial.agent.ContextSizeParser.stripSuffix(config.getMainModel())
  if (main.isNotEmpty()) agents.setAgent(main)
}
