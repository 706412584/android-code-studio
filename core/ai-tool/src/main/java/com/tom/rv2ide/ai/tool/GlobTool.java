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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.json.JSONObject;

public final class GlobTool extends BaseTool {
    public static final String NAME = "glob";
    private static final int MAX_RESULTS = 1000;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Search for matching files. Supports * ** ? wildcards.";
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
                        .put("pattern", new JSONObject().put("type", "string").put("description", "File match pattern, e.g. *.java, app/src/**/*.java"))
                        .put("path", new JSONObject().put("type", "string").put("description", "Search root directory, optional, defaults to the home directory")))
                .put("required", new org.json.JSONArray().put("pattern"));
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        try {
            String pattern = input.optString("pattern");
            ToolArgs.requireNonEmpty(pattern, "pattern");
            File root = FileToolPathPolicy.resolve(context, input.optString("path"));
            if (!root.exists() || !root.isDirectory()) {
                return error(ToolMessages.format(ToolMessages.GLOB_ROOT_NOT_FOUND, input.optString("path", ".")));
            }
            ArrayList<String> results = new ArrayList<>();
            Pattern compiled = Pattern.compile(globToRegex(pattern));
            search(root, "", pattern, compiled, results);
            String displayRoot = FileToolPathPolicy.displayPath(context.getHomePath(), root);
            if (results.isEmpty()) {
                return ok(ToolMessages.format(ToolMessages.GLOB_NO_MATCH, pattern, displayRoot));
            }
            StringBuilder builder = new StringBuilder();
            builder.append(ToolMessages.format(ToolMessages.GLOB_FOUND, results.size(), displayRoot));
            for (String result : results) {
                builder.append(result).append('\n');
            }
            if (results.size() >= MAX_RESULTS) {
                builder.append(ToolMessages.GLOB_TRUNCATED);
            }
            return ok(builder.toString().trim());
        } catch (Exception e) {
            return error(ToolMessages.format(ToolMessages.GLOB_FAILED, e.getMessage()));
        }
    }

    private void search(File dir, String parentPath, String pattern, Pattern compiled, ArrayList<String> results) {
        if (results.size() >= MAX_RESULTS) {
            return;
        }
        File[] items = dir.listFiles();
        if (items == null) {
            return;
        }
        Arrays.sort(items, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File item : items) {
            if (results.size() >= MAX_RESULTS) {
                return;
            }
            String name = item.getName();
            String relative = parentPath.length() == 0 ? name : parentPath + "/" + name;
            if (item.isDirectory()) {
                if (!name.startsWith(".") && !"node_modules".equals(name)) {
                    search(item, relative, pattern, compiled, results);
                }
            } else if (compiled.matcher(relative).matches()
                    || (pattern.indexOf('/') < 0 && compiled.matcher(name).matches())) {
                results.add(relative);
            }
        }
    }

    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append('$');
        return regex.toString();
    }

}
