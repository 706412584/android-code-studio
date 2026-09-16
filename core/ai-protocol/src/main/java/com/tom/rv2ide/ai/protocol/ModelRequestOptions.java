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

import com.tom.rv2ide.ai.protocol.AiBehaviorSettings;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelRequestOptions {
    private final String reasoningEffort;
    private final boolean preserveReasoning;
    private final List<ToolInfo> tools;

    public ModelRequestOptions(String reasoningEffort, boolean preserveReasoning) {
        this(reasoningEffort, preserveReasoning, Collections.emptyList());
    }

    public ModelRequestOptions(String reasoningEffort, boolean preserveReasoning, List<ToolInfo> tools) {
        this.reasoningEffort = AiBehaviorSettings.normalizeReasoningEffort(reasoningEffort);
        this.preserveReasoning = preserveReasoning;
        ArrayList<ToolInfo> ordered = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
        ordered.sort(java.util.Comparator.comparing(ToolInfo::getName));
        this.tools = Collections.unmodifiableList(ordered);
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public boolean isPreserveReasoning() {
        return preserveReasoning;
    }

    public List<ToolInfo> getTools() {
        return tools;
    }

    public static ModelRequestOptions defaults() {
        return new ModelRequestOptions(AiBehaviorSettings.REASONING_MEDIUM, false);
    }
}
