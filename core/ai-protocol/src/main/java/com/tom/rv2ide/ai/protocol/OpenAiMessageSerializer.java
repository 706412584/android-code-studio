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
                object.put("content", toolContentForModel(message));
                array.put(object);
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
