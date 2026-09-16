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

public final class ToolModelMessage extends ModelMessage {
    private final String toolCallId;
    private final String toolName;
    private final boolean toolError;

    public ToolModelMessage(String content) {
        this(content, "", "", false);
    }

    public ToolModelMessage(String content, String toolCallId, String toolName) {
        this(content, toolCallId, toolName, false);
    }

    public ToolModelMessage(String content, String toolCallId, String toolName, boolean toolError) {
        super(content);
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.toolName = toolName == null ? "" : toolName;
        this.toolError = toolError;
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

    @Override
    public String getRole() {
        return "tool";
    }
}
