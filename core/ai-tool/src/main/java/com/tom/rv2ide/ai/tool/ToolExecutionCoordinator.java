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

package com.tom.rv2ide.ai.tool;
import com.tom.rv2ide.ai.tool.api.ToolCall;

import java.util.ArrayList;
import java.util.List;

public final class ToolExecutionCoordinator {
    private final ToolRegistry toolRegistry;

    public ToolExecutionCoordinator() {
        this(null);
    }

    public ToolExecutionCoordinator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public ToolExecutionPlan createPlan(List<ToolCall> toolCalls) {
        ArrayList<ToolCall> concurrentTasks = new ArrayList<>();
        ArrayList<ToolCall> sequentialTasks = new ArrayList<>();
        if (toolCalls == null) {
            return new ToolExecutionPlan(concurrentTasks, sequentialTasks);
        }
        for (ToolCall toolCall : toolCalls) {
            if (isConcurrencySafe(toolCall)) {
                concurrentTasks.add(toolCall);
            } else {
                sequentialTasks.add(toolCall);
            }
        }
        return new ToolExecutionPlan(concurrentTasks, sequentialTasks);
    }

    private boolean isConcurrencySafe(ToolCall toolCall) {
        if (toolCall == null || toolRegistry == null) {
            return false;
        }
        BaseTool tool = toolRegistry.get(toolCall.getName());
        return tool != null && tool.isConcurrencySafe();
    }

    public static final class ToolExecutionPlan {
        private final List<ToolCall> concurrentTasks;
        private final List<ToolCall> sequentialTasks;

        ToolExecutionPlan(List<ToolCall> concurrentTasks, List<ToolCall> sequentialTasks) {
            this.concurrentTasks = concurrentTasks;
            this.sequentialTasks = sequentialTasks;
        }

        public List<ToolCall> getConcurrentTasks() {
            return concurrentTasks;
        }

        public List<ToolCall> getSequentialTasks() {
            return sequentialTasks;
        }
    }
}
