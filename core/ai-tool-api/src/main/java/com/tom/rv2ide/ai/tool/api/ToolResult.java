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

package com.tom.rv2ide.ai.tool.api;


public final class ToolResult {
    private final String toolCallId;
    private final String toolName;
    private final String content;
    private final boolean error;
    private final String diffId;
    private final String reviewState;
    private final String reviewMessage;
    /** 可选的图片负载（base64，不含 data URL 前缀）；无图片时为空串。 */
    private final String imageBase64;
    /** 图片负载的 MIME 类型；无图片时为空串。 */
    private final String imageMimeType;

    /** 工具结果内容最大字符数（50KB），超过此限制时执行中间截断。 */
    public static final int MAX_TOOL_RESULT_CHARS = 50 * 1024;

    /** 中间截断时保留的首/尾各半字符数。 */
    private static final int TRUNCATION_HALF = MAX_TOOL_RESULT_CHARS / 2;

    public ToolResult(
            String toolCallId,
            String toolName,
            String content,
            boolean error,
            String diffId,
            String reviewState,
            String reviewMessage
    ) {
        this(toolCallId, toolName, content, error, diffId, reviewState, reviewMessage, "", "");
    }

    public ToolResult(
            String toolCallId,
            String toolName,
            String content,
            boolean error,
            String diffId,
            String reviewState,
            String reviewMessage,
            String imageBase64,
            String imageMimeType
    ) {
        this.toolCallId = Strings.nullToEmpty(toolCallId);
        this.toolName = Strings.nullToEmpty(toolName);
        this.content = Strings.nullToEmpty(content);
        this.error = error;
        this.diffId = Strings.nullToEmpty(diffId);
        this.reviewState = Strings.nullToEmpty(reviewState);
        this.reviewMessage = Strings.nullToEmpty(reviewMessage);
        this.imageBase64 = Strings.nullToEmpty(imageBase64);
        this.imageMimeType = Strings.nullToEmpty(imageMimeType);
    }

    public static ToolResult of(String toolCallId, String toolName, String content, boolean error) {
        return new ToolResult(toolCallId, toolName, content, error, "", "", "");
    }

    /**
     * 构造一个携带图片负载的成功结果。
     *
     * <p>用于 {@code file_read} 读取图片：{@code content} 是给模型的文字说明，
     * 图片本身以 base64 单独承载，由协议层编码成各家的 image block。
     *
     * @param toolName 工具名
     * @param content 文字说明（可为空）
     * @param imageMimeType 图片 MIME 类型，如 {@code image/png}
     * @param imageBase64 base64 编码的图片数据（不含 data URL 前缀）
     */
    public static ToolResult withImage(
            String toolName, String content, String imageMimeType, String imageBase64) {
        return new ToolResult("", toolName, content, false, "", "", "", imageBase64, imageMimeType);
    }

    public static ToolResult success(String output) {
        return new ToolResult("", "", output, false, "", "", "");
    }

    public static ToolResult error(String error) {
        return new ToolResult("", "", error, true, "", "", "");
    }

    public static ToolResult withReview(String output, String toolCallId, String toolName,
                                         String diffId, String reviewState, String reviewMessage) {
        return new ToolResult(toolCallId, toolName, output, false, diffId, reviewState, reviewMessage);
    }

    public static ToolResult withReview(String toolCallId, String toolName, String content,
                                         boolean error, String diffId, String reviewState, String reviewMessage) {
        return new ToolResult(toolCallId, toolName, content, error, diffId, reviewState, reviewMessage);
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return error;
    }

    public String getDiffId() {
        return diffId;
    }

    public String getReviewState() {
        return reviewState;
    }

    public String getReviewMessage() {
        return reviewMessage;
    }

    /** 图片负载的 base64 数据；无图片时为空串。 */
    public String getImageBase64() {
        return imageBase64;
    }

    /** 图片负载的 MIME 类型；无图片时为空串。 */
    public String getImageMimeType() {
        return imageMimeType;
    }

    /** 是否携带图片负载。 */
    public boolean hasImage() {
        return imageBase64.length() > 0;
    }

    public ToolResult withCall(String nextToolCallId, String nextToolName) {
        return new ToolResult(
                nextToolCallId, nextToolName, content, error, diffId, reviewState, reviewMessage,
                imageBase64, imageMimeType);
    }

    public ToolResult withDiffId(String nextDiffId) {
        return new ToolResult(
                toolCallId, toolName, content, error, nextDiffId, reviewState, reviewMessage,
                imageBase64, imageMimeType);
    }

    public ToolResult withReview(String nextReviewState, String nextReviewMessage) {
        return new ToolResult(
                toolCallId, toolName, content, error, diffId, nextReviewState, nextReviewMessage,
                imageBase64, imageMimeType);
    }

    /**
     * 对内容执行中间截断：当内容超过 {@link #MAX_TOOL_RESULT_CHARS} 时，
     * 保留首 TRUNCATION_HALF 字符 + 截断标记 + 尾 TRUNCATION_HALF 字符。
     * 不超过限制时原样返回。
     *
     * @param content 原始内容
     * @return 截断后的内容，或原始内容（如未超限）
     */
    public static String truncateContent(String content) {
        if (content == null || content.length() <= MAX_TOOL_RESULT_CHARS) {
            return content;
        }
        int truncated = content.length() - MAX_TOOL_RESULT_CHARS;
        return content.substring(0, TRUNCATION_HALF)
                + "\n... (" + truncated + " chars truncated) ...\n"
                + content.substring(content.length() - TRUNCATION_HALF);
    }
}
