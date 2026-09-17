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
import com.tom.rv2ide.ai.agent.prompt.PromptTemplateStore;
import java.util.HashMap;
import java.util.Map;

/**
 * 用偏好设置实现 {@link PromptTemplateStore}。
 *
 * <p>每个模板一个键（前缀 + 模板 ID），而不是把所有模板塞进一个 JSON：模板文本较长且
 * 含换行，JSON 序列化会引入转义问题；分开存也让用户能通过偏好文件单独检查某个模板。
 *
 * <p>键名不存在的模板即为「未自定义」，由 {@link PromptTemplateStore#resolve} 回退到默认。
 */
public final class PrefsPromptTemplateStore implements PromptTemplateStore {

  private static final String PREFS_NAME = "ai_prompt_templates";

  /** 键前缀。加前缀是为了与将来的其它偏好共存于同一文件而不冲突。 */
  private static final String KEY_PREFIX = "tpl_";

  private final SharedPreferences prefs;

  public PrefsPromptTemplateStore(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  @Override
  public String read(String templateId) {
    if (templateId == null || templateId.isEmpty()) {
      return null;
    }
    return prefs.getString(KEY_PREFIX + templateId, null);
  }

  @Override
  public void write(String templateId, String content) {
    if (templateId == null || templateId.isEmpty()) {
      return;
    }
    if (content == null || content.trim().isEmpty()) {
      // 写空等同于恢复默认：不留下「自定义为空」的中间态，
      // 否则 isCustomized 会返回 true 但 resolve 又走默认，UI 显示矛盾。
      prefs.edit().remove(KEY_PREFIX + templateId).apply();
      return;
    }
    prefs.edit().putString(KEY_PREFIX + templateId, content).apply();
  }

  @Override
  public void reset(String templateId) {
    if (templateId != null && !templateId.isEmpty()) {
      prefs.edit().remove(KEY_PREFIX + templateId).apply();
    }
  }

  @Override
  public Map<String, String> readAll() {
    Map<String, String> result = new HashMap<>();
    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (key.startsWith(KEY_PREFIX) && value instanceof String) {
        result.put(key.substring(KEY_PREFIX.length()), (String) value);
      }
    }
    return result;
  }
}
