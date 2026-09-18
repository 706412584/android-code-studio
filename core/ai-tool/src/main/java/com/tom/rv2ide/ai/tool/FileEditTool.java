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

public final class FileEditTool extends BaseTool {
    public static final String NAME = "file_edit";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Edit file contents. Search and replace via old_string/new_string.";
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
                        .put("old_string", new JSONObject().put("type", "string").put("description", "Original text to search for; must be unique unless replace_all is true"))
                        .put("new_string", new JSONObject().put("type", "string").put("description", "Replacement text"))
                        .put("replace_all", new JSONObject()
                                .put("type", "boolean")
                                .put("description", "If true, replace every occurrence of old_string. Default false: require a unique match and replace only once.")))
                .put("required", new org.json.JSONArray().put("file_path").put("old_string").put("new_string"));
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        try {
            String path = input.optString("file_path");
            String oldString = input.optString("old_string");
            String newString = input.optString("new_string");
            boolean replaceAll = input.optBoolean("replace_all", false);
            ToolArgs.requireNonEmpty(path, "file_path");
            if (oldString.length() == 0) {
                return error(ToolMessages.FILE_EDIT_OLD_STRING_EMPTY);
            }
            File file = FileToolPathPolicy.resolve(context, path);
            if (!file.exists()) {
                return error(ToolMessages.format(ToolMessages.FILE_EDIT_NOT_FOUND, FileToolPathPolicy.displayPath(context.getHomePath(), file)));
            }
            if (file.isDirectory()) {
                return error(ToolMessages.format(ToolMessages.FILE_EDIT_IS_DIRECTORY, path));
            }
            String content = FileIo.readUtf8(file);

            // 行尾归一化后再匹配。
            //
            // 模型给出的 old_string 几乎总是 LF（它在训练数据里见到的就是 LF），
            // 而工程里的文件常常是 CRLF（Windows 创建、或模板自带）。此时
            // content.contains(oldString) 恒为 false，工具返回「No matching text found」——
            // 但模型无法从这条消息推断出真正原因是行尾差异，只会反复重试同一段文本，
            // 或者改用 file_write 整文件覆盖（丢掉未预期改动之外的内容）。
            //
            // 实测案例：Renderer.h 是 CRLF，模型三次 file_edit 全部失败，
            // 而同一文件的 file_write 全部成功。
            //
            // 做法：把内容与待匹配串都折成 LF 来做「查找与替换」，最后再把结果按
            // 原文件的行尾风格写回。这样：
            // - 匹配不再受行尾影响
            // - 写入后文件的行尾风格保持不变（不会因为一次编辑就把整个文件转成 LF）
            boolean crlf = content.indexOf('\r') >= 0;
            String normalizedContent = crlf ? content.replace("\r\n", "\n") : content;
            String normalizedOld = oldString.replace("\r\n", "\n");
            String normalizedNew = newString.replace("\r\n", "\n");

            if (!normalizedContent.contains(normalizedOld)) {
                return error(ToolMessages.FILE_EDIT_NO_MATCH);
            }
            int count = countOccurrences(normalizedContent, normalizedOld);
            if (count > 1 && !replaceAll) {
                return error(ToolMessages.format(ToolMessages.FILE_EDIT_MULTIPLE_MATCHES, count));
            }
            String nextNormalized = replaceAll
                    ? normalizedContent.replace(normalizedOld, normalizedNew)
                    : replaceFirst(normalizedContent, normalizedOld, normalizedNew);
            int replaced = replaceAll ? count : 1;
            // 还原原文件的行尾风格。
            String next = crlf ? nextNormalized.replace("\n", "\r\n") : nextNormalized;
            FileOutputStream output = new FileOutputStream(file, false);
            try {
                output.write(next.getBytes(StandardCharsets.UTF_8));
            } finally {
                output.close();
            }
            return ok(ToolMessages.format(ToolMessages.FILE_EDIT_SUCCESS, FileToolPathPolicy.displayPath(context.getHomePath(), file), replaced));
        } catch (Exception e) {
            return error(ToolMessages.format(ToolMessages.FILE_EDIT_FAILED, e.getMessage()));
        }
    }

    private static String replaceFirst(String content, String oldString, String newString) {
        int index = content.indexOf(oldString);
        if (index < 0) {
            return content;
        }
        return content.substring(0, index) + newString + content.substring(index + oldString.length());
    }

    private int countOccurrences(String content, String value) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

}
