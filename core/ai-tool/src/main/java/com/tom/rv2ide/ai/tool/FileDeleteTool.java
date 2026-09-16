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
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public final class FileDeleteTool extends BaseTool {
    public static final String NAME = "file_delete";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Delete a file or directory. A deletion reason is required; user confirmation is requested before execution.";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.WRITE;
    }

    @Override
    public ToolDisplayCategory getDisplayCategory() {
        return ToolDisplayCategory.DELETE;
    }

    @Override
    public boolean needsConfirmation() {
        return true;
    }

    @Override
    public JSONObject getParameters() throws org.json.JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("reason", new JSONObject().put("type", "string").put("description", "Deletion reason, shown to the user for confirmation"))
                        .put("paths", new JSONObject()
                                .put("type", "array")
                                .put("items", new JSONObject().put("type", "string"))
                                .put("description", "List of file or directory paths to delete")))
                .put("required", new org.json.JSONArray().put("reason").put("paths"));
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        ArrayList<String> paths = paths(input);
        String reason = input.optString("reason").trim();
        if (reason.length() == 0) {
            return error(ToolMessages.FILE_DELETE_REASON_EMPTY);
        }
        if (paths.isEmpty()) {
            return error(ToolMessages.FILE_DELETE_PATHS_EMPTY);
        }

        ArrayList<String> deleted = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        for (String path : paths) {
            try {
                File target = FileToolPathPolicy.resolve(context, path);
                if (!target.exists()) {
                    errors.add(ToolMessages.format(ToolMessages.FILE_DELETE_PATH_NOT_FOUND, path));
                    continue;
                }
                deleteRecursive(target);
                deleted.add(FileToolPathPolicy.displayPath(context.getHomePath(), target));
            } catch (Exception e) {
                errors.add(ToolMessages.format(ToolMessages.FILE_DELETE_ITEM_FAILED, path, e.getMessage()));
            }
        }

        StringBuilder builder = new StringBuilder();
        if (!deleted.isEmpty()) {
            builder.append(ToolMessages.format(ToolMessages.FILE_DELETE_SUCCESS, deleted.size()));
            for (String path : deleted) {
                builder.append("- ").append(path).append('\n');
            }
        }
        if (!errors.isEmpty()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(ToolMessages.format(ToolMessages.FILE_DELETE_PARTIAL_FAIL, errors.size()));
            for (String error : errors) {
                builder.append("- ").append(error).append('\n');
            }
        }
        boolean isError = errors.size() > 0 && deleted.isEmpty();
        String content = builder.length() == 0 ? ToolMessages.FILE_DELETE_NONE : builder.toString().trim();
        return isError ? error(content) : ok(content);
    }

    private ArrayList<String> paths(JSONObject input) {
        ArrayList<String> values = new ArrayList<>();
        JSONArray array = input.optJSONArray("paths");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i).trim();
                if (value.length() > 0) {
                    values.add(value);
                }
            }
        }
        String filePath = input.optString("file_path").trim();
        if (filePath.length() > 0) {
            values.add(filePath);
        }
        String path = input.optString("path").trim();
        if (path.length() > 0) {
            values.add(path);
        }
        return values;
    }

    private void deleteRecursive(File file) throws java.io.IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete()) {
            throw new java.io.IOException("Unable to delete " + file.getPath());
        }
    }

}
