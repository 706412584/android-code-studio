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
   * @param diffId 该消息对应一次可回滚的文件改动；null 表示不可回滚（多数消息如此）
   * @param reverted 该改动是否已被撤销。撤销后按钮要变成不可再点的状态——
   *   否则用户会重复点击并收到「已经回滚过了」的错误
   */
  data class Message(
      val id: Long,
      val role: Role,
      val text: String,
      val diffId: String? = null,
      val reverted: Boolean = false,
  )

  private val messages = mutableListOf<Message>()
  private var nextId = 0L

  /**
   * 撤销按钮的点击回调；由视图层注入（回滚需要工作区上下文才能安全执行）。
   *
   * <p>同时传消息 id 与 diffId：调用方需要前者把该条标记为已撤销，后者去执行回滚。
   * 让按钮自己按 diffId 反查消息会引入一次线性查找，而绑定处本来就知道是哪一条。
   */
  private var onRevert: ((Long, String) -> Unit)? = null

  fun setOnRevertClickListener(listener: (Long, String) -> Unit) {
    this.onRevert = listener
  }

  /** 追加一条消息并返回它的 id；流式更新用该 id 定位。 */
  fun append(role: Role, text: String): Long = append(role, text, null)

  /** 追加一条可回滚的消息。 */
  fun append(role: Role, text: String, diffId: String?): Long {
    val id = nextId++
    messages.add(Message(id, role, text, diffId))
    notifyItemInserted(messages.size - 1)
    return id
  }

  /**
   * 把某条消息标记为已撤销。
   *
   * <p>只改状态不删消息：撤销是用户可见的动作，消息凭空消失会让人以为列表出了问题。
   */
  fun markReverted(id: Long) {
    val index = messages.indexOfFirst { it.id == id }
    if (index < 0) {
      return
    }
    val old = messages[index]
    if (old.reverted) {
      return
    }
    messages[index] = old.copy(reverted = true)
    notifyItemChanged(index)
  }

  /** 按 id 取消息；不存在返回 null。 */
  fun find(id: Long): Message? = messages.firstOrNull { it.id == id }

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
    val message = messages[position]
    holder.bind(message, onRevert)
  }

  override fun getItemCount(): Int = messages.size

  class VH(private val binding: ItemAssistantMessageBinding) : RecyclerView.ViewHolder(binding.root) {

    fun bind(message: Message, onRevert: ((Long, String) -> Unit)?) {
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

      // 撤销按钮：只有携带 diffId 的消息才显示。已撤销时改为禁用并换文案——
      // 让按钮消失会让用户怀疑自己是否点到了，禁用态能明确传达「已经生效了」。
      val diffId = message.diffId
      if (diffId == null) {
        binding.messageRevert.visibility = android.view.View.GONE
      } else {
        binding.messageRevert.visibility = android.view.View.VISIBLE
        binding.messageRevert.isEnabled = !message.reverted
        binding.messageRevert.setText(
            if (message.reverted) string.ai_assistant_revert_done
            else string.ai_assistant_revert
        )
        binding.messageRevert.setOnClickListener {
          onRevert?.invoke(message.id, diffId)
        }
      }
    }
  }
}
