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

import com.tom.rv2ide.ai.tool.api.ToolArgs;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class FileWriteTool extends BaseTool {
    public static final String NAME = "file_write";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Write content to a file. Automatically creates the file or directory if it does not exist.";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.WRITE;
    }

    @Override
    public ToolDisplayCategory getDisplayCategory() {
        return ToolDisplayCategory.WRITE;
    }

    @Override
    public boolean shouldRecordDiff() {
        return true;
    }

    @Override
    public JSONObject getParameters() throws org.json.JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("file_path", new JSONObject().put("type", "string").put("description", "Absolute or relative file path"))
                        .put("content", new JSONObject().put("type", "string").put("description", "Content to write")))
                .put("required", new org.json.JSONArray().put("file_path").put("content"));
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        try {
            String path = input.optString("file_path");
            ToolArgs.requireNonEmpty(path, "file_path");
            File file = FileToolPathPolicy.resolve(context, path);
            if (file.exists() && file.isDirectory()) {
                return error(ToolMessages.format(ToolMessages.FILE_WRITE_IS_DIRECTORY, path));
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return error(ToolMessages.format(ToolMessages.FILE_WRITE_MKDIR_FAILED, parent.getPath()));
            }
            boolean existed = file.exists();
            byte[] bytes = input.optString("content").getBytes(StandardCharsets.UTF_8);
            FileOutputStream output = new FileOutputStream(file, false);
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
            int lineCount = input.optString("content").split("\n", -1).length;
            return ok(ToolMessages.format(existed ? ToolMessages.FILE_WRITE_UPDATED : ToolMessages.FILE_WRITE_CREATED, path, lineCount));
        } catch (Exception e) {
            return error(ToolMessages.format(ToolMessages.FILE_WRITE_FAILED, e.getMessage()));
        }
    }

}
