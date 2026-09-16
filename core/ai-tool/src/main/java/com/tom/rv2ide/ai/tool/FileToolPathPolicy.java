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
