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


public abstract class AbstractToolCallBuilder {
    protected String id = "";
    protected String name = "";
    protected final StringBuilder arguments = new StringBuilder();

    public boolean hasName() {
        return name.length() > 0;
    }

    public ToolCall build(int index) {
        String resolvedId = id.length() == 0 ? defaultId(index) : id;
        String resolvedArgs = arguments.length() == 0 ? "{}" : arguments.toString();
        return new ToolCall(resolvedId, name, resolvedArgs);
    }

    public ToolCall buildWithId() {
        String resolvedArgs = arguments.length() == 0 ? "{}" : arguments.toString();
        return new ToolCall(id, name, resolvedArgs);
    }

    protected String defaultId(int index) {
        return "call_" + index + "_" + System.currentTimeMillis();
    }
}
