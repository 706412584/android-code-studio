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

import java.util.Collections;
import java.util.List;

public abstract class ModelMessage {
    private final String content;
    private final String reasoningContent;
    private final String rawInputJson;

    protected ModelMessage(String content) {
        this(content, "");
    }

    protected ModelMessage(String content, String reasoningContent) {
        this(content, reasoningContent, "");
    }

    protected ModelMessage(String content, String reasoningContent, String rawInputJson) {
        this.content = content == null ? "" : content;
        this.reasoningContent = reasoningContent == null ? "" : reasoningContent;
        this.rawInputJson = rawInputJson == null ? "" : rawInputJson.trim();
    }

    public final String getContent() {
        return content;
    }

    public final String getReasoningContent() {
        return reasoningContent;
    }

    public final String getRawInputJson() {
        return rawInputJson;
    }

    public List<ToolCall> getToolCalls() {
        return Collections.emptyList();
    }

    public String getToolCallId() {
        return "";
    }

    public String getToolName() {
        return "";
    }

    public boolean isToolError() {
        return false;
    }

    public abstract String getRole();
}
