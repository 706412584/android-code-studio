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
            // 做法：把内容与待匹配串都折成 LF 来做「查找与替换」，然后把结果按
            // **原文件对应位置**的行尾风格还原。这样：
            // - 匹配不再受行尾影响
            // - 不会因为一次编辑就把整个文件的行尾风格改掉
            //
            // **不能用「全文是否含 \r」判定风格**：那样对混合行尾的文件
            // （同一文件里既有 LF 又有 CRLF——例如模型上次用 file_write 把 LF 内容
            // 写进了 CRLF 文件）会把所有 LF 行都改成 CRLF，git 视角整个文件都变了。
            // 也不能对含裸 CR 的文件做 replace("\n", "\r\n")——会把 \r 越加越多。
            String normalizedContent = content.replace("\r\n", "\n");
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
            String next = restoreLineEndings(content, normalizedContent, nextNormalized);
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

  /**
   * 按原文件的行尾风格还原编辑结果。
   *
   * <p><b>为什么逐行还原而不是「判断全文风格后统一转换」</b>：一个文件里可能同时存在
   * LF 与 CRLF 行（模型用 file_write 把 LF 内容写进 CRLF 文件就会造成这种混合）。
   * 按「全文是否含 CR」判定后统一转换，会把本来正确的那些行的行尾也改掉——
   * git 视角下整个文件都变了，用户看到一条巨大的 diff，而实际只改了一行。
   *
   * <p>做法：原文件第 N 行的行尾风格，应用到结果里第 N 行。行数不匹配时（编辑增删了行）
   * 对多出来的行沿用原文件最后一行的风格——这是最保守的选择，至少不会改掉已有的行。
   *
   * @param original 原文件内容（用于提取每行的行尾风格）
   * @param normalizedOriginal 原文件折成 LF 后的内容（用于对齐行号）
   * @param edited 编辑后、仍是 LF 的内容
   */
  private static String restoreLineEndings(
      String original, String normalizedOriginal, String edited) {
    if (original.indexOf('\r') < 0) {
      return edited;
    }
    boolean[] crlfPerLine = lineEndingStyles(original, normalizedOriginal);
    if (crlfPerLine.length == 0) {
      return edited;
    }
    String[] lines = edited.split("\n", -1);
    StringBuilder out = new StringBuilder(edited.length() + 64);
    for (int i = 0; i < lines.length; i++) {
      out.append(lines[i]);
      if (i == lines.length - 1) {
        break;
      }
      boolean crlf = crlfPerLine[Math.min(i, crlfPerLine.length - 1)];
      out.append(crlf ? "\r\n" : "\n");
    }
    return out.toString();
  }

  /**
   * 逐行提取原文件的行尾风格。
   *
   * <p>对每一行判断它在原文件里以 CRLF 还是 LF 结尾。按行遍历而不是先 split 再比对：
   * split 会丢掉分隔符本身，而要判断的正是分隔符。
   */
  private static boolean[] lineEndingStyles(String original, String normalizedOriginal) {
    int lineCount = 0;
    for (int i = 0; i < normalizedOriginal.length(); i++) {
      if (normalizedOriginal.charAt(i) == '\n') {
        lineCount++;
      }
    }
    if (lineCount == 0) {
      return new boolean[0];
    }
    boolean[] crlf = new boolean[lineCount];
    int line = 0;
    int raw = 0;
    for (int i = 0; i < normalizedOriginal.length() && line < lineCount; i++) {
      if (normalizedOriginal.charAt(i) != '\n') {
        raw++;
        continue;
      }
      if (raw < original.length() && original.charAt(raw) == '\r') {
        crlf[line] = true;
        raw++;
      }
      raw++;
      line++;
    }
    return crlf;
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
