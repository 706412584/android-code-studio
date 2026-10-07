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

package com.tom.rv2ide.ai.protocol;

import java.util.Collections;
import java.util.List;

public final class ToolModelMessage extends ModelMessage {
    private final String toolCallId;
    private final String toolName;
    private final boolean toolError;

    /**
     * 该次调用期间的步骤进度记录；无步骤时为空列表。
     *
     * <p>仅供 UI 回放工具卡片使用（会话历史里的步骤区）。协议序列化路径**不读它**——
     * 步骤是给用户看的旁路信息，不该进入发给模型的报文（浪费 token 且无意义）。
     */
    private final List<String> steps;

    public ToolModelMessage(String content) {
        this(content, "", "", false);
    }

    public ToolModelMessage(String content, String toolCallId, String toolName) {
        this(content, toolCallId, toolName, false);
    }

    public ToolModelMessage(String content, String toolCallId, String toolName, boolean toolError) {
        this(content, toolCallId, toolName, toolError, null);
    }

    /**
     * 带图片负载的构造。
     *
     * @param rawInputJson 工具结果图片的原始 JSON（见
     *     {@link ImageInputPayload#imageResultJson}）；{@code null} 表示纯文本结果。
     *     两条协议序列化路径据此把结果编码成 image block。
     */
    public ToolModelMessage(
            String content, String toolCallId, String toolName, boolean toolError, String rawInputJson) {
        this(content, toolCallId, toolName, toolError, rawInputJson, Collections.emptyList());
    }

    /** 带步骤记录的构造（会话历史 fold 使用，见 {@code ConversationHistory}）。 */
    public ToolModelMessage(
            String content,
            String toolCallId,
            String toolName,
            boolean toolError,
            String rawInputJson,
            List<String> steps) {
        super(content, "", rawInputJson);
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.toolName = toolName == null ? "" : toolName;
        this.toolError = toolError;
        this.steps = steps == null ? Collections.emptyList() : steps;
    }

    @Override
    public String getToolCallId() {
        return toolCallId;
    }

    @Override
    public String getToolName() {
        return toolName;
    }

    @Override
    public boolean isToolError() {
        return toolError;
    }

    /** 步骤进度记录；无步骤时为空列表。仅供 UI 回放，不参与协议序列化。 */
    public List<String> getSteps() {
        return steps;
    }

    @Override
    public String getRole() {
        return "tool";
    }
}
