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

public final class ReasoningRequestContext {
    private final boolean enabled;
    private final String effort;
    private final boolean preserveReasoning;
    private final String baseUrl;
    private final String modelId;
    private final int thinkingBudget;

    public ReasoningRequestContext(boolean enabled, String effort, boolean preserveReasoning,
                                   String baseUrl, String modelId, int thinkingBudget) {
        this.enabled = enabled;
        this.effort = effort;
        this.preserveReasoning = preserveReasoning;
        this.baseUrl = baseUrl;
        this.modelId = modelId;
        this.thinkingBudget = thinkingBudget;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getEffort() {
        return effort;
    }

    public boolean isPreserveReasoning() {
        return preserveReasoning;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModelId() {
        return modelId;
    }

    public int getThinkingBudget() {
        return thinkingBudget;
    }
}
