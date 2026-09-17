/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.agent.prompt;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 提示词模板的存储接口。
 *
 * <p><b>为什么要存储抽象</b>：模板是用户可编辑的数据，需要落盘（改完重启仍在）。
 * 但 {@code ai-agent} 不依赖 Android，因此这里只声明窄接口，由 app 层用偏好设置实现；
 * 单测用内存实现。
 *
 * <p><b>读取失败一律回退到默认模板</b>：用户手工编辑的模板可能损坏（格式错乱、
 * 误删占位符），甚至偏好文件本身可能读不出来。任何一种都不该让 AI 功能整体不可用——
 * 模板是优化手段，不是运行前提。
 */
public interface PromptTemplateStore {

  /**
   * 读取模板；未自定义时返回 null，由调用方回退到 {@link PromptTemplates#defaultFor}。
   *
   * @param templateId 模板 ID
   */
  String read(String templateId);

  /** 写入自定义模板。 */
  void write(String templateId, String content);

  /** 清除自定义，恢复默认。 */
  void reset(String templateId);

  /** 全部被自定义过的模板 ID → 内容。 */
  default Map<String, String> readAll() {
    return Collections.emptyMap();
  }

  /**
   * 取模板内容：有自定义用自定义，否则用默认。
   *
   * <p>这是调用方应当使用的入口——把「回退到默认」的判断收在一处，避免每个调用点
   * 各写一遍判空。
   */
  default String resolve(String templateId) {
    String custom = read(templateId);
    if (custom != null && !custom.trim().isEmpty()) {
      return custom;
    }
    return PromptTemplates.defaultFor(templateId);
  }

  /** 是否被自定义过（设置界面据此显示「已修改」标记与「恢复默认」按钮）。 */
  default boolean isCustomized(String templateId) {
    String custom = read(templateId);
    return custom != null && !custom.trim().isEmpty();
  }

  /** 内存实现；进程内有效。 */
  static PromptTemplateStore inMemory() {
    return new PromptTemplateStore() {

      private final Map<String, String> templates = new HashMap<>();

      @Override
      public synchronized String read(String templateId) {
        return templateId == null ? null : templates.get(templateId);
      }

      @Override
      public synchronized void write(String templateId, String content) {
        if (templateId == null || templateId.isEmpty()) {
          return;
        }
        if (content == null || content.trim().isEmpty()) {
          // 写空等同于恢复默认，避免留下一个「自定义为空」的中间态。
          templates.remove(templateId);
          return;
        }
        templates.put(templateId, content);
      }

      @Override
      public synchronized void reset(String templateId) {
        if (templateId != null) {
          templates.remove(templateId);
        }
      }

      @Override
      public synchronized Map<String, String> readAll() {
        return new HashMap<>(templates);
      }
    };
  }

  /** 全部不自定义：始终使用默认模板。 */
  static PromptTemplateStore defaults() {
    return new PromptTemplateStore() {

      @Override
      public String read(String templateId) {
        return null;
      }

      @Override
      public void write(String templateId, String content) {
        // 不存储。
      }

      @Override
      public void reset(String templateId) {
        // 不存储。
      }
    };
  }
}
