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

package com.tom.rv2ide.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.databinding.ItemAssistantMessageBinding
import com.tom.rv2ide.resources.R.string

/**
 * 悬浮助手面板的消息列表。
 *
 * <p>刻意按位置精确通知而不是 `notifyDataSetChanged`：助手在流式输出期间每收到一个
 * 文本增量就更新末条消息，整表刷新会让列表滚动位置抖动、且反复重建全部 ViewHolder。
 * [Message] 做成不可变 data class，使"内容变了"这件事在代码里一眼可见。
 */
class AssistantMessageAdapter : RecyclerView.Adapter<AssistantMessageAdapter.VH>() {

  /** 消息角色。UI 只区分"谁说的"，不关心协议层的具体类型。 */
  enum class Role {
    USER,
    ASSISTANT,
    /** 工具调用/系统提示等过程信息，弱化显示。 */
    TRACE
  }

  /**
   * 一条消息。
   *
   * @param id 稳定标识：流式更新末条消息时 id 不变，DiffUtil 才能识别为"同一项内容变了"
   * @param text 正文
   */
  data class Message(val id: Long, val role: Role, val text: String)

  private val messages = mutableListOf<Message>()
  private var nextId = 0L

  /** 追加一条消息并返回它的 id；流式更新用该 id 定位。 */
  fun append(role: Role, text: String): Long {
    val id = nextId++
    messages.add(Message(id, role, text))
    notifyItemInserted(messages.size - 1)
    return id
  }

  /**
   * 覆盖指定消息的正文。
   *
   * <p>流式渲染的入口：模型每吐出一段文本就调用一次。id 不存在时静默忽略——
   * 消息可能已被清空（用户点了"新会话"），此时迟到的增量不该让界面崩掉。
   */
  fun update(id: Long, text: String) {
    val index = messages.indexOfFirst { it.id == id }
    if (index < 0) {
      return
    }
    val old = messages[index]
    if (old.text == text) {
      return
    }
    messages[index] = old.copy(text = text)
    notifyItemChanged(index)
  }

  /** 追加到指定消息的正文之后（增量累积用）。 */
  fun appendTo(id: Long, delta: String) {
    val index = messages.indexOfFirst { it.id == id }
    if (index < 0) {
      return
    }
    val old = messages[index]
    messages[index] = old.copy(text = old.text + delta)
    notifyItemChanged(index)
  }

  fun clear() {
    val oldSize = messages.size
    messages.clear()
    if (oldSize > 0) {
      notifyItemRangeRemoved(0, oldSize)
    }
  }

  fun isEmpty(): Boolean = messages.isEmpty()

  /** 末条消息的 id；空列表返回 null。 */
  fun lastId(): Long? = messages.lastOrNull()?.id

  /** 末条消息的角色；空列表返回 null。 */
  fun lastRole(): Role? = messages.lastOrNull()?.role

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    val binding =
        ItemAssistantMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return VH(binding)
  }

  override fun onBindViewHolder(holder: VH, position: Int) {
    holder.bind(messages[position])
  }

  override fun getItemCount(): Int = messages.size

  class VH(private val binding: ItemAssistantMessageBinding) : RecyclerView.ViewHolder(binding.root) {

    fun bind(message: Message) {
      val context = binding.root.context
      val card = binding.messageCard

      binding.messageRole.text =
          context.getString(
              when (message.role) {
                Role.USER -> string.ai_assistant_role_user
                Role.ASSISTANT -> string.ai_assistant_role_assistant
                Role.TRACE -> string.ai_assistant_role_trace
              }
          )
      binding.messageText.text = message.text

      // 角色靠配色区分：用户用主色容器、助手用次级容器、过程信息用最低对比度。
      // 色值必须走 Material 库的 attr：本项目 app 模块的 R.attr 里没有这些主题属性。
      val (container, onContainer) =
          when (message.role) {
            Role.USER ->
                com.google.android.material.R.attr.colorPrimaryContainer to
                    com.google.android.material.R.attr.colorOnPrimaryContainer
            Role.ASSISTANT ->
                com.google.android.material.R.attr.colorSecondaryContainer to
                    com.google.android.material.R.attr.colorOnSecondaryContainer
            Role.TRACE ->
                com.google.android.material.R.attr.colorSurfaceContainerHighest to
                    com.google.android.material.R.attr.colorOnSurfaceVariant
          }

      card.setCardBackgroundColor(
          ColorStateList.valueOf(MaterialColors.getColor(card, container))
      )
      val textColor = MaterialColors.getColor(card, onContainer)
      binding.messageRole.setTextColor(textColor)
      binding.messageText.setTextColor(textColor)
    }
  }
}
