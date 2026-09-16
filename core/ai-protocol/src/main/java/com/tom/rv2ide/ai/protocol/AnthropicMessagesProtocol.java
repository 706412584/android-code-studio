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

import com.tom.rv2ide.ai.protocol.ModelCompletionException;
import com.tom.rv2ide.ai.protocol.ModelCompletionResponse;
import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.protocol.ImageInputPayload;
import com.tom.rv2ide.ai.protocol.ModelRequestOptions;
import com.tom.rv2ide.ai.protocol.ModelStreamCallback;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.AiBehaviorSettings;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelContextParser;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AnthropicMessagesProtocol extends AbstractHttpModelProtocol {
    @Override
    public boolean supportsNativeTools(ModelConfig model) {
        return true;
    }

    @Override
    public ModelCompletionResponse complete(ModelConfig config, List<ModelMessage> messages) throws ModelCompletionException {
        String raw = "";
        try {
            JSONObject body = new JSONObject();
            body.put("model", ModelContextParser.apiModelId(config));
            body.put("max_tokens", 4096);
            body.put("messages", messagesJson(messages));

            String system = systemPrompt(messages);
            if (system.length() > 0) {
                body.put("system", system);
            }

            HashMap<String, String> headers = new HashMap<>();
            headers.put("x-api-key", config.getApiKey());
            headers.put("anthropic-version", "2023-06-01");
            raw = postJson(endpoint(config.getBaseUrl(), "/v1/messages"), body, headers);
            JSONObject response = new JSONObject(raw);
            return extractResponse(response.optJSONArray("content"));
        } catch (ModelCompletionException e) {
            throw e;
        } catch (Exception e) {
            logParseError("parse_anthropic_complete", raw, e);
            throw new ModelCompletionException("Anthropic Messages protocol parse failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ModelCompletionResponse stream(
            ModelConfig config,
            List<ModelMessage> messages,
            ModelStreamCallback callback,
            ModelCancellationToken cancellationToken,
            ModelRequestOptions options
    ) throws ModelCompletionException {
        ModelRequestOptions requestOptions = options == null ? ModelRequestOptions.defaults() : options;
        JSONObject body;
        HashMap<String, String> headers = new HashMap<>();
        headers.put("x-api-key", config.getApiKey());
        headers.put("anthropic-version", "2023-06-01");
        try {
            body = buildRequestBody(config, messages, requestOptions);
        } catch (Exception e) {
            throw new ModelCompletionException("Anthropic request build failed: " + e.getMessage(), e);
        }

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        HashMap<Integer, ToolUseBuilder> toolUseBuilders = new HashMap<>();
        final int[] usageInputTokens = new int[1];
        final int[] usageOutputTokens = new int[1];
        // 部分内容保留（cc-haha commit buffer）
        com.tom.rv2ide.ai.protocol.AssistantCommitBuffer commitBuffer = new com.tom.rv2ide.ai.protocol.AssistantCommitBuffer();

        try {
        postJsonSse(endpoint(config.getBaseUrl(), "/v1/messages"), body, headers, cancellationToken, (eventType, data) -> {
            handleSseEvent(data, callback, text, reasoning, toolUseBuilders, usageInputTokens, usageOutputTokens, commitBuffer);
        });

        return new ModelCompletionResponse(
                text.toString(),
                reasoning.toString(),
                buildToolCalls(toolUseBuilders),
                usageInputTokens[0],
                usageOutputTokens[0]
        );
        } catch (ModelCompletionException e) {
            throw attachPartial(e, commitBuffer);
        } catch (Exception e) {
            throw attachPartial(
                    new ModelCompletionException("Anthropic Messages protocol stream parse failed: " + e.getMessage(), e),
                    commitBuffer);
        }
    }

    /** 流中断且未越过工具边界时，把已收到内容挂到异常上供编排层提交。 */
    private static ModelCompletionException attachPartial(ModelCompletionException e,
                                                          com.tom.rv2ide.ai.protocol.AssistantCommitBuffer buffer) {
        if (e.hasPartial() || buffer == null || !buffer.hasPartial()) {
            return e;
        }
        return e.withPartial(buffer.text(), buffer.reasoning(), buffer.crossedToolBoundary());
    }

    private JSONObject buildRequestBody(
            ModelConfig config,
            List<ModelMessage> messages,
            ModelRequestOptions requestOptions
    ) throws Exception {
        String effort = requestOptions.getReasoningEffort();
        boolean thinkingEnabled = AiBehaviorSettings.isReasoningEnabled(effort);
        effort = AiBehaviorSettings.concreteReasoningEffort(effort);
        int thinkingBudget = thinkingEnabled ? thinkingBudget(effort) : 0;
        JSONObject body = new JSONObject();
        body.put("model", ModelContextParser.apiModelId(config));
        body.put("max_tokens", thinkingEnabled ? Math.max(4096, thinkingBudget + 1024) : 4096);
        body.put("messages", messagesJson(messages));
        body.put("stream", true);
        if (!requestOptions.getTools().isEmpty()) {
            body.put("tools", toolsJson(requestOptions.getTools()));
        }
        if (thinkingEnabled) {
            body.put("thinking", new JSONObject()
                    .put("type", "enabled")
                    .put("budget_tokens", thinkingBudget));
        }

        String system = systemPrompt(messages);
        if (system.length() > 0) {
            body.put("system", system);
        }
        return body;
    }

    private void handleSseEvent(
            String data,
            ModelStreamCallback callback,
            StringBuilder text,
            StringBuilder reasoning,
            HashMap<Integer, ToolUseBuilder> toolUseBuilders,
            int[] usageInputTokens,
            int[] usageOutputTokens,
            com.tom.rv2ide.ai.protocol.AssistantCommitBuffer commitBuffer
    ) throws Exception {
        if ("[DONE]".equals(data.trim())) {
            return;
        }
        JSONObject event = new JSONObject(data);
        if (event.has("error")) {
            throw new ModelCompletionException("Anthropic stream error: " + event.opt("error"));
        }
        String type = event.optString("type");

        if ("message_start".equals(type)) {
            JSONObject message = event.optJSONObject("message");
            JSONObject usage = message == null ? null : message.optJSONObject("usage");
            if (usage != null) {
                usageInputTokens[0] = Math.max(usageInputTokens[0], usage.optInt("input_tokens", 0));
            }
            return;
        }

        if ("message_delta".equals(type)) {
            JSONObject usage = event.optJSONObject("usage");
            if (usage != null) {
                usageOutputTokens[0] = Math.max(usageOutputTokens[0], usage.optInt("output_tokens", 0));
            }
            return;
        }

        if ("content_block_start".equals(type)) {
            JSONObject block = event.optJSONObject("content_block");
            if (block != null) {
                String blockType = block.optString("type");
                if ("redacted_thinking".equals(blockType)) {
                    appendDelta(reasoning, "[redacted thinking]", true, callback);
                    commitBuffer.appendReasoning("[redacted thinking]");
                } else if ("tool_use".equals(blockType)) {
                    // 副作用边界：工具调用块开始，此后断流不可重发
                    commitBuffer.markToolUseStarted();
                    startToolUse(toolUseBuilders, event.optInt("index", toolUseBuilders.size()), block);
                }
            }
            return;
        }

        if (!"content_block_delta".equals(type)) {
            return;
        }

        JSONObject delta = event.optJSONObject("delta");
        if (delta == null) {
            return;
        }
        String deltaType = delta.optString("type");
        if ("thinking_delta".equals(deltaType)) {
            String value = delta.optString("thinking");
            appendDelta(reasoning, value, true, callback);
            commitBuffer.appendReasoning(value);
        } else if ("text_delta".equals(deltaType)) {
            String value = delta.optString("text");
            appendDelta(text, value, false, callback);
            commitBuffer.appendText(value);
        } else if ("input_json_delta".equals(deltaType)) {
            appendToolUseInput(toolUseBuilders, event.optInt("index", toolUseBuilders.size()), delta.optString("partial_json"));
        }
    }

    private JSONArray messagesJson(List<ModelMessage> messages) throws Exception {
        JSONArray array = new JSONArray();
        for (int i = 0; i < messages.size(); i++) {
            ModelMessage message = messages.get(i);
            if ("system".equals(message.getRole())) {
                continue;
            }
            if ("tool".equals(message.getRole())) {
                JSONArray content = new JSONArray();
                while (i < messages.size() && "tool".equals(messages.get(i).getRole())) {
                    content.put(toolResultBlock(messages.get(i)));
                    i++;
                }
                i--;
                array.put(new JSONObject()
                        .put("role", "user")
                        .put("content", content));
                continue;
            }
            JSONObject object = new JSONObject();
            object.put("role", message.getRole());
            if ("assistant".equals(message.getRole()) && !message.getToolCalls().isEmpty()) {
                object.put("content", assistantContentBlocks(message));
            } else if ("user".equals(message.getRole()) && ImageInputPayload.fromRawInputJson(message.getRawInputJson()) != null) {
                object.put("content", imageContentBlocks(message));
            } else {
                object.put("content", message.getContent());
            }
            array.put(object);
        }
        return array;
    }

    JSONArray messagesJsonForTest(List<ModelMessage> messages) throws Exception {
        return messagesJson(messages);
    }

    private JSONArray imageContentBlocks(ModelMessage message) throws Exception {
        ImageInputPayload.Payload payload = ImageInputPayload.fromRawInputJson(message.getRawInputJson());
        if (payload == null) {
            return new JSONArray().put(new JSONObject()
                    .put("type", "text")
                    .put("text", message.getContent()));
        }
        String prompt = payload.getPrompt().length() == 0 ? message.getContent() : payload.getPrompt();
        return new JSONArray()
                .put(new JSONObject()
                        .put("type", "text")
                        .put("text", prompt == null ? "" : prompt))
                .put(new JSONObject()
                        .put("type", "image")
                        .put("source", new JSONObject()
                                .put("type", "base64")
                                .put("media_type", payload.getMimeType())
                                .put("data", payload.getDataBase64())));
    }

    private JSONArray assistantContentBlocks(ModelMessage message) throws Exception {
        JSONArray blocks = new JSONArray();
        if (message.getContent().trim().length() > 0) {
            blocks.put(new JSONObject()
                    .put("type", "text")
                    .put("text", message.getContent()));
        }
        for (ToolCall call : message.getToolCalls()) {
            blocks.put(new JSONObject()
                    .put("type", "tool_use")
                    .put("id", call.getId())
                    .put("name", call.getName())
                    .put("input", toolInputJson(call.getArguments())));
        }
        return blocks;
    }

    private JSONObject toolResultBlock(ModelMessage message) throws Exception {
        JSONObject block = new JSONObject()
                .put("type", "tool_result")
                .put("tool_use_id", message.getToolCallId())
                .put("content", message.getContent());
        if (message.isToolError()) {
            block.put("is_error", true);
        }
        return block;
    }

    private JSONArray toolsJson(List<ToolInfo> tools) throws Exception {
        JSONArray array = new JSONArray();
        for (ToolInfo tool : tools) {
            array.put(new JSONObject()
                    .put("name", tool.getName())
                    .put("description", tool.getDescription())
                    .put("input_schema", tool.getParameters()));
        }
        return array;
    }

    private String systemPrompt(List<ModelMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ModelMessage message : messages) {
            if ("system".equals(message.getRole())) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(message.getContent());
            }
        }
        return builder.toString();
    }

    private ModelCompletionResponse extractResponse(JSONArray content) {
        if (content == null) {
            return new ModelCompletionResponse("");
        }
        StringBuilder builder = new StringBuilder();
        ArrayList<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) {
                continue;
            }
            String type = block.optString("type");
            if ("text".equals(type)) {
                builder.append(block.optString("text"));
            } else if ("tool_use".equals(type)) {
                JSONObject input = block.optJSONObject("input");
                toolCalls.add(new ToolCall(
                        block.optString("id"),
                        block.optString("name"),
                        input == null ? "{}" : input.toString()
                ));
            }
        }
        return new ModelCompletionResponse(builder.toString(), "", toolCalls);
    }

    private void appendDelta(
            StringBuilder target,
            String delta,
            boolean thinking,
            ModelStreamCallback callback
    ) {
        if (delta == null || delta.length() == 0) {
            return;
        }
        target.append(delta);
        if (callback == null) {
            return;
        }
        if (thinking) {
            callback.onReasoningDelta(delta);
        } else {
            callback.onTextDelta(delta);
        }
    }

    private JSONObject toolInputJson(String arguments) throws Exception {
        if (arguments == null || arguments.trim().length() == 0) {
            return new JSONObject();
        }
        String value = arguments.trim();
        if (value.startsWith("{")) {
            return new JSONObject(value);
        }
        JSONObject object = new JSONObject();
        object.put("value", value);
        return object;
    }

    private void startToolUse(Map<Integer, ToolUseBuilder> builders, int index, JSONObject block) {
        ToolUseBuilder builder = builders.get(index);
        if (builder == null) {
            builder = new ToolUseBuilder();
            builders.put(index, builder);
        }
        builder.id = block.optString("id");
        builder.name = block.optString("name");
        JSONObject input = block.optJSONObject("input");
        if (input != null && input.length() > 0 && builder.arguments.length() == 0) {
            builder.arguments.append(input.toString());
        }
    }

    private void appendToolUseInput(Map<Integer, ToolUseBuilder> builders, int index, String partialJson) {
        if (partialJson == null || partialJson.length() == 0) {
            return;
        }
        ToolUseBuilder builder = builders.get(index);
        if (builder == null) {
            builder = new ToolUseBuilder();
            builders.put(index, builder);
        }
        builder.arguments.append(partialJson);
    }

    private List<ToolCall> buildToolCalls(Map<Integer, ToolUseBuilder> builders) {
        ArrayList<Integer> indexes = new ArrayList<>(builders.keySet());
        indexes.sort(Integer::compareTo);
        ArrayList<ToolCall> calls = new ArrayList<>();
        for (Integer index : indexes) {
            ToolUseBuilder builder = builders.get(index);
            if (builder == null || !builder.hasName()) {
                continue;
            }
            calls.add(builder.build(index));
        }
        return calls;
    }

    private static final class ToolUseBuilder extends AbstractToolCallBuilder {
        @Override
        protected String defaultId(int index) {
            return "toolu_" + index + "_" + System.currentTimeMillis();
        }
    }
}
