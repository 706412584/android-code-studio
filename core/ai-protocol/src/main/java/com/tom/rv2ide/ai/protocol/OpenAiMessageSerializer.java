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

package com.tom.rv2ide.ai.protocol;
import com.tom.rv2ide.ai.tool.api.ToolCall;

import com.tom.rv2ide.ai.protocol.ImageInputPayload;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class OpenAiMessageSerializer {

    JSONArray messagesJson(List<ModelMessage> messages) throws Exception {
        return messagesJson(messages, false);
    }

    JSONArray messagesJson(List<ModelMessage> messages, boolean preserveReasoning) throws Exception {
        JSONArray array = new JSONArray();
        for (ModelMessage message : messages) {
            JSONObject object = new JSONObject();
            object.put("role", message.getRole());
            if ("tool".equals(message.getRole())) {
                object.put("tool_call_id", message.getToolCallId());
                // 工具结果的正文始终用字符串形态。图片**不能**塞进 role=tool 的
                // content 数组：实测 newapi/deepseek-v4.1-flash 对
                // {role:"tool", content:[{type:"text"},{type:"image_url"}]} 返回 200，
                // 但模型内部收不到图（reasoning 里明说 "I don't see an image"），
                // 表现为「截图成功却看不见画面」这种静默丢图。
                // 改为把图片作为紧随其后的 user 消息投递后，同一模型能正确描述截图内容。
                object.put("content", toolContentForModel(message));
                array.put(object);
                ImageInputPayload.Payload image =
                        ImageInputPayload.fromImageResult(message.getRawInputJson());
                if (image != null) {
                    array.put(imageCarrierMessage(message, image));
                }
                continue;
            }
            if ("assistant".equals(message.getRole()) && !message.getToolCalls().isEmpty()) {
                object.put("content", message.getContent().length() == 0 ? JSONObject.NULL : message.getContent());
                JSONArray toolCalls = new JSONArray();
                for (ToolCall call : message.getToolCalls()) {
                    JSONObject function = new JSONObject()
                            .put("name", call.getName())
                            .put("arguments", call.getArguments());
                    toolCalls.put(new JSONObject()
                            .put("id", call.getId())
                            .put("type", "function")
                            .put("function", function));
                }
                object.put("tool_calls", toolCalls);
            } else if ("user".equals(message.getRole()) && ImageInputPayload.fromRawInputJson(message.getRawInputJson()) != null) {
                object.put("content", imageContent(message));
            } else {
                object.put("content", message.getContent());
            }
            if (preserveReasoning
                    && "assistant".equals(message.getRole())
                    && message.getReasoningContent().length() > 0) {
                object.put("reasoning_content", message.getReasoningContent());
            }
            array.put(object);
        }
        return array;
    }

    JSONArray messagesJsonForTest(List<ModelMessage> messages) throws Exception {
        return messagesJson(messages);
    }

    /**
     * 把工具结果的图片改挂到一条紧随其后的 user 消息上。
     *
     * <p>为什么需要这条「图片搬运」消息：OpenAI 兼容端点（实测 newapi + DeepSeek）
     * 会接受 {@code role=tool} 内嵌 {@code image_url} 的数组形态并返回 200，
     * 但图片并不会真正进入模型的视觉上下文——模型只会看到工具的文字结果，
     * 于是出现「工具说截图成功，模型却看不见画面」的静默失败。
     * 而 {@code role=user} 携带图片时同一模型能正常识别，因此这里把图片
     * 从工具结果搬到最后一条 user 消息里，文字结果仍留在 {@code role=tool}。
     */
    private static JSONObject imageCarrierMessage(
            ModelMessage message, ImageInputPayload.Payload image) throws Exception {
        String toolName = message.getToolName().length() > 0 ? message.getToolName() : "工具";
        JSONArray content = new JSONArray();
        content.put(
                new JSONObject()
                        .put("type", "text")
                        .put("text", "以下图片来自工具 " + toolName + " 的结果："));
        content.put(
                new JSONObject()
                        .put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", image.dataUrl())));
        return new JSONObject().put("role", "user").put("content", content);
    }

    private static String toolContentForModel(ModelMessage message) {
        String content = message.getContent();
        if (!message.isToolError()) {
            return content == null ? "" : content;
        }
        String label = message.getToolName().length() > 0 ? message.getToolName() : message.getToolCallId();
        String body = content == null ? "" : content;
        return "Tool " + label + " failed:\n" + body;
    }

    private JSONArray imageContent(ModelMessage message) throws Exception {
        ImageInputPayload.Payload payload = ImageInputPayload.fromRawInputJson(message.getRawInputJson());
        if (payload == null) {
            return new JSONArray().put(new JSONObject().put("type", "text").put("text", message.getContent()));
        }
        String prompt = payload.getPrompt().length() == 0 ? message.getContent() : payload.getPrompt();
        return new JSONArray()
                .put(new JSONObject()
                        .put("type", "text")
                        .put("text", prompt == null ? "" : prompt))
                .put(new JSONObject()
                        .put("type", "image_url")
                        .put("image_url", new JSONObject()
                                .put("url", payload.dataUrl())));
    }
}
