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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.agent.conversation;

import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.SimpleHttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 会话标题生成器。
 *
 * <p><b>为什么需要它</b>：{@link ConversationReducer} 的优先级链早就预留了
 * {@code customTitle > aiTitle > deriveFromUserMessage}，但 aiTitle **在生产代码里
 * 零调用**——列表标题因此永远停在「首条用户消息截断」上（实测反馈）。
 *
 * <p>本类提供两个阶段，与 cc-haha 的 `titleService` 同构：
 * <ul>
 *   <li>{@link #derivePlaceholder(String)} —— 零成本的占位标题（首句截断），
 *       在第 1 条用户消息时就能显示，不依赖任何网络。</li>
 *   <li>{@link #generate(String, ModelConfig)} —— 调模型生成 3-7 词的精炼标题。
 *       任何失败（网络/密钥/解析）都静默返回 {@code null}，调用方保留现有标题即可。</li>
 * </ul>
 *
 * <p><b>纯 Java，零 Android 依赖</b>：只用了 {@code org.json} 与 ai-protocol 的
 * {@link SimpleHttpClient}（后者已是本模块的既有依赖），因此可在 JVM 上单测——
 * 用桩实现 {@link HttpPoster} 替代真实 HTTP 即可。
 */
public final class TitleGenerator {

  /** 标题最大长度（字符）。超过即丢弃该候选，不强行截断——截断会把模型的话拦腰截断。 */
  static final int TITLE_MAX_LEN = 50;

  /** 占位标题的最大长度。列表项是单行显示，太长会撑爆。 */
  static final int PLACEHOLDER_MAX_LEN = 40;

  /** 送进模型的对话文本上限。标题只需要语义轮廓，全文会浪费配额且拖慢首条标题。 */
  static final int TITLE_INPUT_MAX_LEN = 2000;

  /** 生成请求的 token 上限。思考模型会把预算全烧在推理上、正文为空（#1340 同类问题）。 */
  static final int TITLE_MAX_OUTPUT_TOKENS = 2048;

  /** 标题生成请求的协议路径。OpenAI 兼容与 Anthropic 各自不同。 */
  private static final String OPENAI_CHAT_PATH = "/chat/completions";

  /** 请求超时：标题是旁路，不该让用户为一次生成等太久。 */
  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int READ_TIMEOUT_MS = 20000;

  /** 生成提示词：只要求一个 JSON 字段，不要解释、不要续写对话。 */
  private static final String SYSTEM_PROMPT =
      "You generate a concise title (3-7 words) for a conversation. "
          + "Return only JSON with a single \"title\" field. "
          + "Do not answer, continue, or summarize the conversation itself. "
          + "Match the language of the user's first message. "
          + "Keep product names, file names, model names, and code identifiers in their original form.";

  /** HTTP 发送函数——单测里用桩实现替换。 */
  interface HttpPoster {
    String post(String url, String body, ModelConfig config) throws Exception;
  }

  /** 真实发送：OpenAI 兼容协议（本项目唯一在用的生成路径）。 */
  private static final HttpPoster OPENAI_POSTER =
      (url, body, config) -> {
        String apiKey = config.getApiKey() == null ? "" : config.getApiKey().trim();
        java.util.Map<String, String> headers =
            apiKey.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.singletonMap("Authorization", "Bearer " + apiKey);
        return SimpleHttpClient.postJson(url, body, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, headers);
      };

  private TitleGenerator() {}

  /**
   * 从首条用户消息派生占位标题。
   *
   * <p>取第一句（句号/问号/叹号结尾），压成单行，限长。空内容返回 {@code ""}。
   *
   * @param raw 用户消息原文
   * @return 可直接显示的标题；为空表示没有可用占位
   */
  public static String derivePlaceholder(String raw) {
    if (raw == null) {
      return "";
    }
    String clean = raw.trim().replaceAll("\\s+", " ");
    if (clean.isEmpty()) {
      return "";
    }
    // 首句：找第一个句末标点（句号/问号/叹号，中英文都算）。
    // 不要求标点后跟空白——「你好。第二句」这种紧挨着的也要能切开。
    String firstSentence = clean;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("^(.*?[.!?。！？])").matcher(clean);
    if (m.find()) {
      firstSentence = m.group(1);
    }
    return firstSentence.length() > PLACEHOLDER_MAX_LEN
        ? firstSentence.substring(0, PLACEHOLDER_MAX_LEN - 1) + "…"
        : firstSentence;
  }

  /**
   * 累积的对话轮次（供标题生成用）。user 文本必有，assistant 文本可空（首轮占位场景）。
   */
  public static final class Turn {
    public final String userText;
    public final String assistantText;

    public Turn(String userText, String assistantText) {
      this.userText = userText == null ? "" : userText;
      this.assistantText = assistantText == null ? "" : assistantText;
    }
  }

  /**
   * 把若干轮对话拼成送进模型的文本（带角色标记，限长）。
   *
   * <p>角色标记不可省：纯拼接会让模型分不清哪句是谁说的，标题容易跑偏到助手的话上。
   */
  public static String buildConversationInput(List<Turn> turns) {
    if (turns == null || turns.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Turn turn : turns) {
      if (!turn.userText.trim().isEmpty()) {
        sb.append("user: ").append(turn.userText).append('\n');
      }
      if (!turn.assistantText.trim().isEmpty()) {
        sb.append("assistant: ").append(turn.assistantText).append('\n');
      }
    }
    String text = sb.toString().trim();
    return text.length() > TITLE_INPUT_MAX_LEN
        ? text.substring(0, TITLE_INPUT_MAX_LEN)
        : text;
  }

  /**
   * 调模型生成标题。
   *
   * @param conversationText {@link #buildConversationInput} 的输出
   * @param config 当前会话的模型配置（复用其 baseUrl / apiKey / modelId）
   * @return 生成的标题；任何失败返回 {@code null}（调用方保留现有标题）
   */
  public static String generate(String conversationText, ModelConfig config) {
    return generate(conversationText, config, OPENAI_POSTER);
  }

  /** 可注入 poster 的版本——单测用。 */
  static String generate(String conversationText, ModelConfig config, HttpPoster poster) {
    if (conversationText == null || conversationText.trim().isEmpty() || config == null) {
      return null;
    }
    String baseUrl = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
    while (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    if (baseUrl.isEmpty()) {
      return null;
    }
    try {
      JSONObject message = new JSONObject();
      message.put("role", "user");
      message.put("content", "Generate a title for the following conversation:\n\n<conversation>\n"
          + conversationText + "\n</conversation>");

      JSONObject body = new JSONObject();
      body.put("model", config.getModelId());
      body.put("max_tokens", TITLE_MAX_OUTPUT_TOKENS);
      body.put("messages", new JSONArray()
          .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
          .put(message));

      String responseBody = poster.post(baseUrl + OPENAI_CHAT_PATH, body.toString(), config);
      return extractTitle(responseBody);
    } catch (Throwable e) {
      // 标题是旁路：网络失败/密钥错/格式不符都不能让对话流程失败。
      return null;
    }
  }

  /**
   * 从模型响应里解析标题。
   *
   * <p>容错两类输出：标准 chat completion（{@code choices[0].message.content}）
   * 与模型直接把 JSON 写在文本里（有些服务商会去掉一层包装）。
   */
  static String extractTitle(String responseBody) {
    if (responseBody == null || responseBody.trim().isEmpty()) {
      return null;
    }
    try {
      JSONObject root = new JSONObject(responseBody);
      String content = null;
      JSONArray choices = root.optJSONArray("choices");
      if (choices != null && choices.length() > 0) {
        JSONObject first = choices.optJSONObject(0);
        if (first != null) {
          JSONObject message = first.optJSONObject("message");
          if (message != null) {
            content = message.optString("content", null);
          }
        }
      }
      if (content == null) {
        content = root.optString("content", null);
      }
      if (content == null) {
        return null;
      }
      return parseTitleText(content);
    } catch (JSONException e) {
      return null;
    }
  }

  /**
   * 解析模型返回的文本为标题。
   *
   * <p>模型可能返回 {@code {"title":"..."}}、带 markdown 围栏的 JSON、或纯文本。
   * 任一候选能解析出合法标题即返回；含标记符号/超长的一律丢弃。
   */
  static String parseTitleText(String text) {
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    // 优先按 JSON 解析（含去围栏）。
    List<String> candidates = new ArrayList<>();
    candidates.add(trimmed);
    String fenced = stripCodeFence(trimmed);
    if (!fenced.equals(trimmed)) {
      candidates.add(fenced);
    }
    int firstBrace = trimmed.indexOf('{');
    int lastBrace = trimmed.lastIndexOf('}');
    if (firstBrace >= 0 && lastBrace > firstBrace) {
      candidates.add(trimmed.substring(firstBrace, lastBrace + 1));
    }
    for (String candidate : candidates) {
      String title = parseTitleJson(candidate);
      if (title != null) {
        String normalized = normalizeTitle(title);
        if (normalized != null) {
          return normalized;
        }
        // normalizeTitle 拒绝（含标记符号/超长）时**继续尝试下一个候选**，
        // 不能直接返回 null——否则带围栏的首个候选会挡住后面能解析成功的。
      }
    }
    // 纯文本：若不含结构化标记，直接当标题。
    if (!looksLikeStructuredFragment(trimmed)) {
      return normalizeTitle(trimmed);
    }
    return null;
  }

  private static String stripCodeFence(String text) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
    return m.find() ? m.group(1).trim() : text;
  }

  private static String parseTitleJson(String candidate) {
    // 只接受「以 { 开头」的候选：JSONTokener 会把「好的，标题是：{...}」的前缀
    // 当成字符串返回，若接受这种 String 结果，前缀就会被误当成标题。
    String trimmed = candidate.trim();
    if (!trimmed.startsWith("{")) {
      return null;
    }
    try {
      Object parsed = new org.json.JSONTokener(trimmed).nextValue();
      if (parsed instanceof JSONObject) {
        return ((JSONObject) parsed).optString("title", null);
      }
    } catch (JSONException ignored) {
      // 不是合法 JSON，由调用方尝试下一个候选。
    }
    return null;
  }

  private static String normalizeTitle(String title) {
    if (title == null) {
      return null;
    }
    String clean = title.trim().replaceAll("\\s+", " ");
    if (clean.isEmpty() || clean.length() > TITLE_MAX_LEN || looksLikeStructuredFragment(clean)) {
      return null;
    }
    return clean;
  }

  private static boolean looksLikeStructuredFragment(String text) {
    return text.contains("```")
        || text.contains("{")
        || text.contains("}")
        || text.matches(".*\\\\?\"title\\\\?\"\\s*:.*");
  }
}
