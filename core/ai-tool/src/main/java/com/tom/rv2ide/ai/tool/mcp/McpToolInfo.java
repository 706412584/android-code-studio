/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
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

package com.tom.rv2ide.ai.tool.mcp;

import org.json.JSONObject;

/**
 * 一个 MCP server 提供的工具的描述。
 *
 * <p>不可变：工具列表会被缓存并跨请求复用，可变对象会让「谁改了这个 schema」无从追查。
 */
public final class McpToolInfo {

  private final String name;
  private final String description;
  private final JSONObject inputSchema;

  public McpToolInfo(String name, String description, JSONObject inputSchema) {
    this.name = name == null ? "" : name;
    this.description = description == null ? "" : description;
    this.inputSchema = inputSchema;
  }

  /** server 侧的原始工具名。 */
  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  /**
   * 参数的 JSON Schema；server 未提供时返回一个宽松的空 schema。
   *
   * <p>返回空 schema 而不是 null：调用方需要把它直接塞进 tools 定义，
   * 判空会散落到每一处。空 schema（{@code {"type":"object"}}）在协议上是合法的。
   */
  public JSONObject getInputSchema() {
    if (inputSchema != null) {
      return inputSchema;
    }
    JSONObject fallback = new JSONObject();
    try {
      fallback.put("type", "object");
    } catch (org.json.JSONException e) {
      // 常量 key，不可达。
    }
    return fallback;
  }
}
