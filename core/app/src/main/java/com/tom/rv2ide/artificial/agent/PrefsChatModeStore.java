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

package com.tom.rv2ide.artificial.agent;

import android.content.Context;
import android.content.SharedPreferences;
import com.tom.rv2ide.ai.agent.prompt.ChatMode;

/**
 * 对话模式的持久化。
 *
 * <p>与 {@link AgentToolSettings} 共用同一个偏好文件（{@code ai_agent_tools}）：
 * 两者都是「agent 怎么跑」的配置，分开存会让「重置 agent 设置」需要清两处。
 *
 * <p>非法值一律回退到 {@link ChatMode#DEFAULT}，不让一个坏值把 AI 变成不可用状态。
 */
public final class PrefsChatModeStore {

  private static final String PREFS_NAME = "ai_agent_tools";

  /** 对话模式键。 */
  public static final String KEY_CHAT_MODE = "chat_mode";

  private final SharedPreferences prefs;

  public PrefsChatModeStore(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** 当前模式；未设置或值非法时返回默认模式。 */
  public ChatMode get() {
    return ChatMode.fromId(prefs.getString(KEY_CHAT_MODE, ChatMode.DEFAULT.getId()));
  }

  /** 设置模式；null 视作默认模式。 */
  public void set(ChatMode mode) {
    ChatMode resolved = mode == null ? ChatMode.DEFAULT : mode;
    prefs.edit().putString(KEY_CHAT_MODE, resolved.getId()).apply();
  }

  /** 直接写 id（供设置界面按字符串处理时使用）。 */
  public void setId(String modeId) {
    set(ChatMode.fromId(modeId));
  }
}
