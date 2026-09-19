/*
 * This file is part of AndroidCodeStudio.
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

package com.tom.rv2ide.ai.tool.skill;

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 写入 / 删除 skill：让 AI 能自己沉淀踩过的坑。
 *
 * <p><b>为什么需要这个工具</b>：内置 skill 只能覆盖通用知识，而每个项目、每台设备都会
 * 撞上自己的坑（「这台设备上 sdkmanager 装的 CMake 都是 x86-64」「这个仓库必须先跑
 * codegen」）。没有写入能力时，这类知识只能靠用户手工整理；有了它，模型在踩坑的当下
 * 就能记下来，下次不必重踩——这是能力自增长的那一环。
 *
 * <p><b>与 memory 的分工</b>：{@code memory_update} 存**短事实**（一句话），按当前请求
 * 做相关度检索后注入提示词；skill 存**长指导**（多段说明、步骤、命令），提示词里只占
 * 一行，模型判断相关时才用 {@code skill} 工具读全文。判断标准：一句话能说清就存记忆，
 * 需要解释「怎么做」就写 skill。
 *
 * <p><b>与内置 skill 的关系</b>：写入目标目录是用户 skill 目录，与内置（assets 播种）
 * 的目录是同一个。重名时后写的覆盖先前的——因为这是**显式**的写入动作，用户或模型
 * 明确要求改这条，静默保留旧内容反而会让人以为没生效。
 */
public final class SkillWriteTool extends BaseTool {

  /** 正文长度上限，与 {@link Skill#MAX_DESCRIPTION_CHARS} 不同：这是正文，允许长。 */
  public static final int MAX_BODY_CHARS = SkillRegistry.MAX_BODY_CHARS;

  /** 一次调用最多写多少条（配合批量场景，避免模型一口气灌几十条）。 */
  static final int MAX_BATCH = 10;

  private static final String DESCRIPTION =
      "Create, update, or delete a skill — a long-form instruction document loaded on demand. "
          + "Use this to record durable know-how that needs explanation: environment quirks, "
          + "multi-step procedures, project-specific build commands. The system prompt lists "
          + "skills by name with a one-line summary; the full body is loaded only when relevant. "
          + "For short one-sentence facts prefer memory_update instead. "
          + "Actions: write (default), delete, list.";

  private final SkillRegistry registry;

  public SkillWriteTool(SkillRegistry registry) {
    this.registry = registry == null ? SkillRegistry.empty() : registry;
  }

  @Override
  public String getName() {
    return ToolNames.SKILL_WRITE;
  }

  @Override
  public String getDescription() {
    return DESCRIPTION;
  }

  @Override
  public ToolCategory getCategory() {
    // 会写文件（skill 正文），与 file_write 同级，受权限模式管辖。
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject entry = new JSONObject();
    entry.put("type", "object");
    entry.put(
        "properties",
        new JSONObject()
            .put(
                "name",
                new JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "Skill identifier: letters, digits, hyphen, underscore only. "
                            + "Prefer a short topic name like native-cmake or release-signing."))
            .put(
                "description",
                new JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "One line shown in the system prompt so the model knows when to load "
                            + "this skill. Be specific about the trigger condition."))
            .put(
                "body",
                new JSONObject()
                    .put("type", "string")
                    .put("description", "Full Markdown body, loaded on demand.")));

    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "One of: write (default), delete, list"))
                .put(
                    "skills",
                    new JSONObject()
                        .put("type", "array")
                        .put("items", entry)
                        .put("description", "Skills to write; used when action is write"))
                .put(
                    "name",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "Skill name to delete; used when action is delete")))
        .put("required", new JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "write").trim().toLowerCase(java.util.Locale.ROOT);
    switch (action) {
      case "list":
        return list();
      case "delete":
        return delete(input.optString("name", "").trim());
      case "write":
        return write(input, context);
      default:
        // 未知动作要明确拒绝并列出合法值，否则模型会反复重试同一个错拼。
        return error("未知的 action: " + action + "。可用值：write / delete / list。");
    }
  }

  private ToolResult write(JSONObject input, ToolContext context) {
    JSONArray array = input.optJSONArray("skills");
    if (array == null || array.length() == 0) {
      return error("action=write 时需要提供 skills 数组（至少一条）。");
    }
    if (array.length() > MAX_BATCH) {
      return error("单次最多写 " + MAX_BATCH + " 条 skill（当前 " + array.length() + " 条）。");
    }

    File root = registry.getRoot();
    if (root == null) {
      // 没有来源目录意味着注册表是 empty() 造的，无处可写。明确报错而不是静默丢弃。
      return error("当前没有配置 skill 目录，无法写入。");
    }
    if (!root.isDirectory() && !root.mkdirs()) {
      return error("无法创建 skill 目录：" + root.getAbsolutePath());
    }

    List<String> written = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject raw = array.optJSONObject(i);
      if (raw == null) {
        continue;
      }
      String name = raw.optString("name", "").trim();
      String description = raw.optString("description", "").trim();
      String body = raw.optString("body", "");

      // 名字会进文件名与提示词，必须严格校验——含路径分隔符的名字能写到目录之外。
      if (!Skill.isValidName(name)) {
        rejected.add(name.isEmpty() ? "(空名字)" : name + "：名字只能用字母、数字、连字符、下划线");
        continue;
      }
      if (body.trim().isEmpty()) {
        rejected.add(name + "：正文不能为空");
        continue;
      }
      if (body.length() > MAX_BODY_CHARS) {
        rejected.add(name + "：正文超过 " + MAX_BODY_CHARS + " 字符");
        continue;
      }

      try {
        writeOne(root, name, description, body);
        written.add(name);
        if (context != null) {
          context.reportProgress("写入 skill " + name);
        }
      } catch (IOException e) {
        rejected.add(name + "：写入失败 " + e.getMessage());
      }
    }

    // 写完立刻重载：否则本次运行内看不到新 skill，模型会以为写入没成功而重复写。
    int total = registry.reload();

    StringBuilder sb = new StringBuilder();
    if (!written.isEmpty()) {
      sb.append("已写入 ").append(written.size()).append(" 个 skill：");
      sb.append(join(written)).append('\n');
      sb.append("（当前共 ").append(total).append(" 个 skill）");
    }
    if (!rejected.isEmpty()) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append("未写入：\n");
      for (String reason : rejected) {
        sb.append("- ").append(reason).append('\n');
      }
    }
    return written.isEmpty() ? error(sb.toString().trim()) : ok(sb.toString().trim());
  }

  /**
   * 写一个 skill 文件。
   *
   * <p>目录形式（{@code <root>/<name>/SKILL.md}）而不是单文件：这样 skill 将来可以带
   * 附件（脚本、示例文件），而不必改布局。{@link SkillRegistry} 两种都支持。
   */
  private void writeOne(File root, String name, String description, String body)
      throws IOException {
    File dir = new File(root, name);
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new IOException("无法创建目录 " + dir.getAbsolutePath());
    }
    File target = new File(dir, "SKILL.md");

    // frontmatter 的值若含换行会把格式写坏，压成单行。
    String safeDescription = description.replaceAll("\\s+", " ").trim();

    try (Writer writer =
        new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
      writer.write("---\n");
      writer.write("name: " + name + "\n");
      if (!safeDescription.isEmpty()) {
        writer.write("description: " + safeDescription + "\n");
      }
      writer.write("---\n");
      writer.write(body.trim());
      writer.write("\n");
    }
  }

  private ToolResult delete(String name) {
    if (name.isEmpty()) {
      return error("action=delete 时需要提供 name。可用 action=list 查看全部名字。");
    }
    if (!Skill.isValidName(name)) {
      return error("非法名字：" + name);
    }
    File root = registry.getRoot();
    if (root == null) {
      return error("当前没有配置 skill 目录。");
    }

    // 两种布局都试：子目录（SKILL.md）与单文件（<name>.md）。
    boolean removed = deleteRecursively(new File(root, name));
    File single = new File(root, name + ".md");
    if (single.isFile() && single.delete()) {
      removed = true;
    }
    if (!removed) {
      return error("找不到名为「" + name + "」的 skill。用 action=list 查看全部名字。");
    }
    int total = registry.reload();
    return ok("已删除 skill「" + name + "」。（剩余 " + total + " 个）");
  }

  private static boolean deleteRecursively(File file) {
    if (!file.exists()) {
      return false;
    }
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursively(child);
        }
      }
    }
    return file.delete();
  }

  private ToolResult list() {
    List<Skill> all = registry.all();
    if (all.isEmpty()) {
      return ok("当前没有 skill。可用 action=write 新建一个。");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("已有 skill（共 ").append(all.size()).append(" 个）：\n");
    for (Skill skill : all) {
      sb.append("- ").append(skill.toPromptLine()).append('\n');
    }
    sb.append("\n用 skill 工具并传入 name 可读完整内容；用本工具 action=write 可新增或覆盖。");
    return ok(sb.toString());
  }

  private static String join(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append("、");
      }
      sb.append(values.get(i));
    }
    return sb.toString();
  }
}
