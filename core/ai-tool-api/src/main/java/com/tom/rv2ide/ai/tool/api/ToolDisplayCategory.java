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

public enum ToolDisplayCategory {
    READ, WRITE, DELETE, SHELL, AGENT, AGENT_PIPELINE, TODO, IMAGE_GENERATION, PHONE_CONTROL, GENERIC;

    /**
     * Fallback category for dynamic tool name prefixes (phone_*, agentx_*, mcpx_*)
     * when no registered tool info is available.
     */
    public static ToolDisplayCategory fallbackDisplayCategory(String name) {
        if (name == null) {
            return GENERIC;
        }
        if (name.startsWith("phone_")) {
            return PHONE_CONTROL;
        }
        if ("agent".equals(name) || name.startsWith("agentx_")) {
            return AGENT;
        }
        if ("agent_pipeline".equals(name)) {
            return AGENT_PIPELINE;
        }
        return GENERIC;
    }
}
