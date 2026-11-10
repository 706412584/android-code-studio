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

import java.io.File;
import java.io.IOException;

public final class FileToolPathPolicy {
    private FileToolPathPolicy() {
    }

    /**
     * 从工具参数里取目标路径：{@code file_path} 优先，其次 {@code path}。
     *
     * <p><b>为什么需要两个名字</b>：本文件工具族的 schema 并不统一——{@code file_read} /
     * {@code file_write} / {@code file_edit} 用 {@code file_path}，而 {@code list_dir} /
     * {@code grep} / {@code glob} 用 {@code path}。模型会跨工具泛化，实测（真机会话
     * {@code 7e0dd4b9}，项目 MyNoActivity23）它 8 次 {@code file_read} 全部发
     * {@code {"path": ...}}，于是每个调用都被解析成「空路径」→ 落到工作区根目录 →
     * 返回一棵目录树 + "To read a file, specify the exact file path"。
     *
     * <p>这不是模型拼错，是同一套工具里参数名不一致导致的必然结果：它看到 {@code list_dir}
     * 用 {@code path}，就认为读文件也是 {@code path}。{@code file_delete} 早就同时接受
     * 两个名字（见 {@code FileDeleteTool.collectPaths}），这里把同样的容忍提升为全族共用。
     *
     * <p><b>注意</b>：路径为空时的语义由调用方决定。{@code file_read} 会把它当「根目录」
     * 列目录，因此调用方应保留原有的非空校验，别让空路径静默变成一次合法的目录读取。
     */
    public static String inputPath(org.json.JSONObject input) {
        if (input == null) {
            return "";
        }
        String filePath = input.optString("file_path", "").trim();
        if (filePath.length() > 0) {
            return filePath;
        }
        return input.optString("path", "").trim();
    }

    public static File resolve(String homePath, String inputPath) throws IOException {
        return resolve(homePath, java.util.Collections.emptyList(), inputPath, false);
    }

    public static File resolve(ToolContext context, String inputPath) throws IOException {
        if (context == null) {
            throw new IOException("Tool context is empty");
        }
        return resolve(context.getHomePath(), context.getExtraWriteRoots(), inputPath, context.isBypassPathProtection());
    }

    private static File resolve(String homePath, java.util.List<String> extraRoots, String inputPath, boolean bypassProtection) throws IOException {
        if (homePath == null || homePath.trim().length() == 0) {
            throw new IOException("Workspace path is empty");
        }
        String rawPath = inputPath == null ? "" : inputPath.trim();
        File root = new File(homePath).getCanonicalFile();
        File target = rawPath.length() == 0
                ? root
                : new File(rawPath).isAbsolute() ? new File(rawPath) : new File(root, rawPath);
        File canonical = target.getCanonicalFile();
        if (bypassProtection) {
            return canonical;
        }
        if (!isInside(root, canonical)) {
            File allowedRoot = matchingExtraRoot(extraRoots, canonical);
            if (allowedRoot == null) {
                throw new IOException("Path is outside the current workspace and authorized Skills directory: " + rawPath);
            }
        }
        return canonical;
    }

    public static String displayPath(String homePath, File file) throws IOException {
        File root = new File(homePath).getCanonicalFile();
        File target = file.getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (targetPath.equals(rootPath)) {
            return ".";
        }
        if (targetPath.startsWith(rootPath + File.separator)) {
            return targetPath.substring(rootPath.length() + 1);
        }
        return targetPath;
    }

    public static boolean isInside(File root, File target) {
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
    }

    private static File matchingExtraRoot(java.util.List<String> extraRoots, File target) throws IOException {
        if (extraRoots == null || extraRoots.isEmpty()) {
            return null;
        }
        for (String rootPath : extraRoots) {
            if (rootPath == null || rootPath.trim().length() == 0) {
                continue;
            }
            File root = new File(rootPath.trim()).getCanonicalFile();
            if (isInside(root, target)) {
                return root;
            }
        }
        return null;
    }
}
