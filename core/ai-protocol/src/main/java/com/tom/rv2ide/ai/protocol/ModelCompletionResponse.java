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
import com.tom.rv2ide.ai.tool.api.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelCompletionResponse {
    private final String text;
    private final String reasoningContent;
    private final List<ToolCall> toolCalls;
    private final int inputTokens;
    private final int outputTokens;

    public ModelCompletionResponse(String text) {
        this(text, "", Collections.emptyList(), 0, 0);
    }

    public ModelCompletionResponse(String text, String reasoningContent) {
        this(text, reasoningContent, Collections.emptyList(), 0, 0);
    }

    public ModelCompletionResponse(String text, String reasoningContent, List<ToolCall> toolCalls) {
        this(text, reasoningContent, toolCalls, 0, 0);
    }

    public ModelCompletionResponse(String text, String reasoningContent, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
        this.text = text == null ? "" : text;
        this.reasoningContent = reasoningContent == null ? "" : reasoningContent;
        this.toolCalls = toolCalls == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        this.inputTokens = Math.max(0, inputTokens);
        this.outputTokens = Math.max(0, outputTokens);
    }

    public String getText() {
        return text;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 服务器观测到的本次请求 input token 数（真实 usage，codex 式触发统计）。
     * 协议无法提供时为 0，调用方应回退到本地估算。
     */
    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }
}
