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

/**
 * 文件类工具面向模型的提示文案。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 把这些文案放在 feature-tool 模块的
 * res/values/strings.xml 中，工具通过 context.getString(R.string.xxx) 取用，
 * 这使工具层依赖 Android 的 Context。这些文案的读者是<b>模型</b>而非用户，
 * 不需要本地化，因此改为 Java 常量，让工具层保持纯 Java、可脱离 Android 测试。
 *
 * <p>占位符沿用上游的 %1$s / %1$d 编号格式，由 {@link #format} 统一处理。
 */
public final class ToolMessages {

  private ToolMessages() {}

  /** 按上游的编号占位符格式化，语义与 String.format 一致。 */
  public static String format(String template, Object... args) {
    return String.format(template, args);
  }

  /** 对应上游 strings.xml 的 tool_file_read_not_found。 */
  public static final String FILE_READ_NOT_FOUND =
      "File not found: %1$s";

  /** 对应上游 strings.xml 的 tool_file_read_empty_dir。 */
  public static final String FILE_READ_EMPTY_DIR =
      "(empty directory)";

  /** 对应上游 strings.xml 的 tool_file_read_dir_content。 */
  public static final String FILE_READ_DIR_CONTENT =
      "Directory %1$s:\\n%2$s";

  /** 对应上游 strings.xml 的 tool_file_read_dir_specify_file。 */
  public static final String FILE_READ_DIR_SPECIFY_FILE =
      "\\n\\nTo read a file, specify the exact file path.";

  /** 对应上游 strings.xml 的 tool_file_read_exceed_1mb。 */
  public static final String FILE_READ_EXCEED_1MB =
      "File %1$s is %2$dKB, exceeding the 1MB read limit.\\nUse start_kb/end_kb to specify a smaller range, e.g.: {\\\"file_path\\\":\\\"%3$s\\\",\\\"start_kb\\\":0,\\\"end_kb\\\":50}";

  /** 对应上游 strings.xml 的 tool_file_read_exceed_50kb。 */
  public static final String FILE_READ_EXCEED_50KB =
      "File %1$s is %2$dKB, exceeding the 50KB single-read limit.\\nUse start_kb and end_kb to specify the range, e.g.: {\\\"file_path\\\":\\\"%3$s\\\",\\\"start_kb\\\":0,\\\"end_kb\\\":50}";

  /** 对应上游 strings.xml 的 tool_file_read_start_out_of_range。 */
  public static final String FILE_READ_START_OUT_OF_RANGE =
      "start_kb=%1$d exceeds file size (file is %2$dKB)";

  /** 对应上游 strings.xml 的 tool_file_read_range_info。 */
  public static final String FILE_READ_RANGE_INFO =
      "\\n\\n… (total %1$d lines, showing KB %2$d-%3$d / total %4$dKB)";

  /** 对应上游 strings.xml 的 tool_file_read_failed。 */
  public static final String FILE_READ_FAILED =
      "Failed to read file: %1$s";

  /** 对应上游 strings.xml 的 tool_file_read_dir_truncated。 */
  public static final String FILE_READ_DIR_TRUNCATED =
      "… (too many directory items, truncated)\\n";

  /** 对应上游 strings.xml 的 tool_file_edit_old_string_empty。 */
  public static final String FILE_EDIT_OLD_STRING_EMPTY =
      "old_string cannot be empty";

  /** 对应上游 strings.xml 的 tool_file_edit_not_found。 */
  public static final String FILE_EDIT_NOT_FOUND =
      "File not found: %1$s";

  /** 对应上游 strings.xml 的 tool_file_edit_is_directory。 */
  public static final String FILE_EDIT_IS_DIRECTORY =
      "Path is a directory, cannot edit: %1$s\\nTo edit a file, specify the exact file path.";

  /** 对应上游 strings.xml 的 tool_file_edit_no_match。 */
  public static final String FILE_EDIT_NO_MATCH =
      "No matching text found";

  /** 对应上游 strings.xml 的 tool_file_edit_multiple_matches。 */
  public static final String FILE_EDIT_MULTIPLE_MATCHES =
      "old_string matched %1$d places. Provide a more unique old_string, or set replace_all=true to replace every occurrence.";

  /** 对应上游 strings.xml 的 tool_file_edit_success。 */
  public static final String FILE_EDIT_SUCCESS =
      "Successfully edited %1$s (%2$d match(es) replaced)";

  /** 对应上游 strings.xml 的 tool_file_edit_failed。 */
  public static final String FILE_EDIT_FAILED =
      "Failed to edit file: %1$s";

  /** 对应上游 strings.xml 的 tool_file_write_is_directory。 */
  public static final String FILE_WRITE_IS_DIRECTORY =
      "Path is a directory, cannot write file: %1$s\\nTo create a file, specify the full file path.";

  /** 对应上游 strings.xml 的 tool_file_write_mkdir_failed。 */
  public static final String FILE_WRITE_MKDIR_FAILED =
      "Failed to create parent directory: %1$s";

  /** 对应上游 strings.xml 的 tool_file_write_updated。 */
  public static final String FILE_WRITE_UPDATED =
      "Successfully updated file %1$s (%2$d lines)";

  /** 对应上游 strings.xml 的 tool_file_write_created。 */
  public static final String FILE_WRITE_CREATED =
      "Successfully created file %1$s (%2$d lines)";

  /** 对应上游 strings.xml 的 tool_file_write_failed。 */
  public static final String FILE_WRITE_FAILED =
      "Failed to write file: %1$s";

  /** 对应上游 strings.xml 的 tool_file_delete_reason_empty。 */
  public static final String FILE_DELETE_REASON_EMPTY =
      "Deletion reason cannot be empty";

  /** 对应上游 strings.xml 的 tool_file_delete_paths_empty。 */
  public static final String FILE_DELETE_PATHS_EMPTY =
      "paths cannot be empty";

  /** 对应上游 strings.xml 的 tool_file_delete_path_not_found。 */
  public static final String FILE_DELETE_PATH_NOT_FOUND =
      "Path not found: %1$s";

  /** 对应上游 strings.xml 的 tool_file_delete_item_failed。 */
  public static final String FILE_DELETE_ITEM_FAILED =
      "Failed to delete %1$s: %2$s";

  /** 对应上游 strings.xml 的 tool_file_delete_success。 */
  public static final String FILE_DELETE_SUCCESS =
      "Successfully deleted %1$d item(s):\\n";

  /** 对应上游 strings.xml 的 tool_file_delete_partial_fail。 */
  public static final String FILE_DELETE_PARTIAL_FAIL =
      "Failed %1$d item(s):\\n";

  /** 对应上游 strings.xml 的 tool_file_delete_none。 */
  public static final String FILE_DELETE_NONE =
      "No files were deleted";

  /** 对应上游 strings.xml 的 tool_glob_root_not_found。 */
  public static final String GLOB_ROOT_NOT_FOUND =
      "Search root directory does not exist or is not a directory: %1$s";

  /** 对应上游 strings.xml 的 tool_glob_no_match。 */
  public static final String GLOB_NO_MATCH =
      "No files matching \\\"%1$s\\\" found in %2$s.";

  /** 对应上游 strings.xml 的 tool_glob_found。 */
  public static final String GLOB_FOUND =
      "Found %1$d matching file(s) in %2$s:\\n";

  /** 对应上游 strings.xml 的 tool_glob_truncated。 */
  public static final String GLOB_TRUNCATED =
      "… (too many results, truncated)\\n";

  /** 对应上游 strings.xml 的 tool_glob_failed。 */
  public static final String GLOB_FAILED =
      "Search failed: %1$s";

  /** 对应上游 strings.xml 的 tool_list_dir_not_found。 */
  public static final String LIST_DIR_NOT_FOUND =
      "Directory not found: %1$s";

  /** 对应上游 strings.xml 的 tool_list_dir_not_directory。 */
  public static final String LIST_DIR_NOT_DIRECTORY =
      "Path is not a directory: %1$s";

  /** 对应上游 strings.xml 的 tool_list_dir_empty。 */
  public static final String LIST_DIR_EMPTY =
      "Directory %1$s:\\n(empty directory)";

  /** 对应上游 strings.xml 的 tool_list_dir_content。 */
  public static final String LIST_DIR_CONTENT =
      "Directory %1$s:\\n";

  /** 对应上游 strings.xml 的 tool_list_dir_failed。 */
  public static final String LIST_DIR_FAILED =
      "Failed to list directory: %1$s";

}