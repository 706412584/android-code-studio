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
import com.tom.rv2ide.ai.tool.api.ToolResult;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import java.io.File;
import java.util.Arrays;
import org.json.JSONObject;

public final class ListDirectoryTool extends BaseTool {
    public static final String NAME = "list_dir";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "List the immediate files and folders under a directory.";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.READ;
    }

    @Override
    public ToolDisplayCategory getDisplayCategory() {
        return ToolDisplayCategory.READ;
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public JSONObject getParameters() throws org.json.JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("path", new JSONObject().put("type", "string").put("description", "Absolute or relative directory path, optional, defaults to the home directory")));
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        try {
            File dir = FileToolPathPolicy.resolve(context, input.optString("path"));
            if (!dir.exists()) {
                return error(ToolMessages.format(ToolMessages.LIST_DIR_NOT_FOUND, input.optString("path", ".")));
            }
            if (!dir.isDirectory()) {
                return error(ToolMessages.format(ToolMessages.LIST_DIR_NOT_DIRECTORY, input.optString("path", ".")));
            }
            File[] items = dir.listFiles();
            if (items == null || items.length == 0) {
                return ok(ToolMessages.format(ToolMessages.LIST_DIR_EMPTY, FileToolPathPolicy.displayPath(context.getHomePath(), dir)));
            }
            Arrays.sort(items, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            });
            StringBuilder builder = new StringBuilder();
            builder.append(ToolMessages.format(ToolMessages.LIST_DIR_CONTENT, FileToolPathPolicy.displayPath(context.getHomePath(), dir)));
            for (File item : items) {
                builder.append(item.isDirectory() ? "[DIR]  " : "[FILE] ")
                        .append(item.getName())
                        .append(item.isDirectory() ? "/" : "")
                        .append('\n');
            }
            return ok(builder.toString().trim());
        } catch (Exception e) {
            return error(ToolMessages.format(ToolMessages.LIST_DIR_FAILED, e.getMessage()));
        }
    }

    /** 将绝对路径转换为相对于工作区的展示路径。 */
    private static String displayPath(String workspacePath, String path) {
        if (path == null || path.trim().length() == 0) return "";
        String value = path.trim().replace('\\', '/');
        if (value.startsWith("file://")) {
            value = value.substring("file://".length());
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() == 0) return "";
        if (!value.startsWith("/")) {
            while (value.startsWith("./")) {
                value = value.substring(2);
            }
            return value;
        }
        String root = workspacePath == null ? "" : workspacePath.trim().replace('\\', '/');
        while (root.length() > 1 && root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.length() == 0 || !root.startsWith("/")) return value;
        if (value.equals(root)) return ".";
        String prefix = root + "/";
        if (value.startsWith(prefix)) {
            String relative = value.substring(prefix.length());
            while (relative.startsWith("./")) {
                relative = relative.substring(2);
            }
            return relative;
        }
        return value;
    }

}
