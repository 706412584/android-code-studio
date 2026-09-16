/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from LineCode Pro (https://github.com/LangLang03/LineCodePro),
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

package com.tom.rv2ide.ai.tool.api;


/**
 * 工具调用卡片视图契约。
 * <p>由各 ToolCall 显示视图实现（:tool-ui 模块），工具通过
 * {@link ToolInfo#getToolCallViewClass()} 声明自己使用的实现类。</p>
 */
public interface ToolCallCardView {
    void bind(ToolCall call, ToolResult result);

    void setToolReviewListener(ToolReviewListener listener);

    void setProjectPath(String projectPath);

    /**
     * 内容增量更新钩子：仅当工具结果内容变化而结构（卡片类型/状态/参数）未变时被调用，
     * 由各实现类覆写以只更新内容文本，避免流式输出时整棵视图树重建。
     * 默认回退到完整绑定，保持既有行为。
     */
    default void updateContent(ToolCall call, ToolResult result) {
        bind(call, result);
    }
}
