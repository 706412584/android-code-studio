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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 工具元数据接口，供 AI 协议层引用工具信息而不依赖 BaseTool 具体实现。
 * BaseTool 实现此接口；AI 模块只依赖 ToolInfo。
 */
public interface ToolInfo {
    String getName();
    String getDescription();
    ToolCategory getCategory();
    boolean needsConfirmation();
    /**
     * 追加到系统提示词的工具说明片段，可为 null。
     *
     * <p><b>与上游的差异</b>：LineCode Pro 的签名为
     * {@code promptSupplement(String executionMode, boolean isSsh)}。本移植不含 SSH 后端，
     * 故去掉 {@code isSsh} 参数；执行模式信息已包含在 {@code executionMode} 中。
     */
    String promptSupplement(String executionMode);
    JSONObject getParameters() throws JSONException;
    JSONObject toJson() throws JSONException;

    /**
     * 声明本工具使用的 ToolCall 卡片视图实现类；返回 null 时按
     * {@link ToolDisplayCategory} 回退到默认视图。
     */
    default Class<? extends ToolCallCardView> getToolCallViewClass() {
        return null;
    }

    /** 将 ToolInfo 集合序列化为 OpenAI tools 格式的 JSONArray */
    static org.json.JSONArray toJsonArray(java.util.Collection<? extends ToolInfo> tools) throws JSONException {
        org.json.JSONArray array = new org.json.JSONArray();
        if (tools == null) {
            return array;
        }
        for (ToolInfo tool : tools) {
            array.put(tool.toJson());
        }
        return array;
    }
}
