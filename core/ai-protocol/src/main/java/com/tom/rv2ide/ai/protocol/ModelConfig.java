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

import com.tom.rv2ide.ai.tool.api.Strings;
import org.json.JSONException;
import org.json.JSONObject;

public final class ModelConfig {
    public static final int DEFAULT_TOOL_CALL_LIMIT = 200;
    public static final int UNLIMITED_TOOL_CALLS = -1;
    public static final boolean DEFAULT_COMPRESSION_MODEL_AUTO = true;
    /** contextSize 字段未设置时的哨兵值，表示沿用旧 {@code {id}[{大小}]} 后缀解析或默认值。 */
    public static final int CONTEXT_SIZE_UNSET = 0;
    /** 服务商分组槽位角色（cc-haha 语义：一个服务商 4 个模型槽位）。 */
    public static final String SLOT_MAIN = "main";
    public static final String SLOT_HAIKU = "haiku";
    public static final String SLOT_SONNET = "sonnet";
    public static final String SLOT_OPUS = "opus";

    private final String id;
    private final String name;
    private final ModelProtocolType protocolType;
    private final String providerLabel;
    private final String baseUrl;
    private final String apiKey;
    private final String modelId;
    private final int toolCallLimit;
    private final boolean compressionModelEnabled;
    private final boolean compressionModelAuto;
    private final String compressionModelId;
    private final int contextSize;
    private final String groupId;
    private final String slotRole;

    /**
     * 显式带上 contextSize 的构造函数。新代码请优先使用 {@link Builder}。
     *
     * @param contextSize 上下文窗口 tokens 数；传 {@link #CONTEXT_SIZE_UNSET} 表示未设置，
     *                    解析时会回退到旧的 {@code modelId[大小]} 后缀或默认值，保证向后兼容。
     */
    public ModelConfig(
            String id,
            String name,
            ModelProtocolType protocolType,
            String providerLabel,
            String baseUrl,
            String apiKey,
            String modelId,
            int toolCallLimit,
            boolean compressionModelEnabled,
            boolean compressionModelAuto,
            String compressionModelId,
            int contextSize
    ) {
        this(id, name, protocolType, providerLabel, baseUrl, apiKey, modelId, toolCallLimit,
                compressionModelEnabled, compressionModelAuto, compressionModelId, contextSize, "", "");
    }

    public ModelConfig(
            String id,
            String name,
            ModelProtocolType protocolType,
            String providerLabel,
            String baseUrl,
            String apiKey,
            String modelId,
            int toolCallLimit,
            boolean compressionModelEnabled,
            boolean compressionModelAuto,
            String compressionModelId,
            int contextSize,
            String groupId,
            String slotRole
    ) {
        this.id = Strings.nullToEmpty(id);
        this.name = Strings.nullToEmpty(name);
        this.protocolType = protocolType == null ? ModelProtocolType.OPENAI_COMPATIBLE : protocolType;
        this.providerLabel = providerLabel == null ? this.protocolType.getLabel() : providerLabel;
        this.baseUrl = Strings.nullToEmpty(baseUrl);
        this.apiKey = Strings.nullToEmpty(apiKey);
        this.modelId = Strings.nullToEmpty(modelId);
        this.toolCallLimit = normalizeToolCallLimit(toolCallLimit);
        this.compressionModelEnabled = compressionModelEnabled && this.protocolType.supportsDedicatedCompression();
        this.compressionModelAuto = compressionModelAuto;
        this.compressionModelId = Strings.nullToEmpty(compressionModelId).trim();
        this.contextSize = contextSize < 0 ? CONTEXT_SIZE_UNSET : contextSize;
        this.groupId = Strings.nullToEmpty(groupId);
        this.slotRole = Strings.nullToEmpty(slotRole);
    }

    private ModelConfig(Builder builder) {
        this.id = Strings.nullToEmpty(builder.id);
        this.name = Strings.nullToEmpty(builder.name);
        this.protocolType = builder.protocolType == null ? ModelProtocolType.OPENAI_COMPATIBLE : builder.protocolType;
        this.providerLabel = builder.providerLabel == null ? this.protocolType.getLabel() : builder.providerLabel;
        this.baseUrl = Strings.nullToEmpty(builder.baseUrl);
        this.apiKey = Strings.nullToEmpty(builder.apiKey);
        this.modelId = Strings.nullToEmpty(builder.modelId);
        this.toolCallLimit = normalizeToolCallLimit(builder.toolCallLimit);
        this.compressionModelEnabled = builder.compressionModelEnabled && this.protocolType.supportsDedicatedCompression();
        this.compressionModelAuto = builder.compressionModelAuto;
        this.compressionModelId = Strings.nullToEmpty(builder.compressionModelId).trim();
        this.contextSize = builder.contextSize < 0 ? CONTEXT_SIZE_UNSET : builder.contextSize;
        this.groupId = Strings.nullToEmpty(builder.groupId);
        this.slotRole = Strings.nullToEmpty(builder.slotRole);
    }

    public static Builder builder(String id, String name, ModelProtocolType protocolType,
                                   String providerLabel, String baseUrl, String apiKey, String modelId) {
        return new Builder(id, name, protocolType, providerLabel, baseUrl, apiKey, modelId);
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private final ModelProtocolType protocolType;
        private String providerLabel;
        private final String baseUrl;
        private final String apiKey;
        private final String modelId;
        private int toolCallLimit = DEFAULT_TOOL_CALL_LIMIT;
        private boolean compressionModelEnabled = false;
        private boolean compressionModelAuto = DEFAULT_COMPRESSION_MODEL_AUTO;
        private String compressionModelId = "";
        private int contextSize = CONTEXT_SIZE_UNSET;
        private String groupId = "";
        private String slotRole = "";

        private Builder(String id, String name, ModelProtocolType protocolType,
                        String providerLabel, String baseUrl, String apiKey, String modelId) {
            this.id = id;
            this.name = name;
            this.protocolType = protocolType;
            this.providerLabel = providerLabel;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.modelId = modelId;
        }

        public Builder providerLabel(String providerLabel) {
            this.providerLabel = providerLabel;
            return this;
        }

        public Builder toolCallLimit(int toolCallLimit) {
            this.toolCallLimit = toolCallLimit;
            return this;
        }

        public Builder compressionModelEnabled(boolean compressionModelEnabled) {
            this.compressionModelEnabled = compressionModelEnabled;
            return this;
        }

        public Builder compressionModelAuto(boolean compressionModelAuto) {
            this.compressionModelAuto = compressionModelAuto;
            return this;
        }

        public Builder compressionModelId(String compressionModelId) {
            this.compressionModelId = compressionModelId;
            return this;
        }

        public Builder contextSize(int contextSize) {
            this.contextSize = contextSize;
            return this;
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder slotRole(String slotRole) {
            this.slotRole = slotRole;
            return this;
        }

        public ModelConfig build() {
            return new ModelConfig(this);
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ModelProtocolType getProtocolType() {
        return protocolType;
    }

    public String getProviderLabel() {
        return providerLabel;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModelId() {
        return modelId;
    }

    public int getToolCallLimit() {
        return toolCallLimit;
    }

    public boolean isCompressionModelEnabled() {
        return compressionModelEnabled;
    }

    public boolean isCompressionModelAuto() {
        return compressionModelAuto;
    }

    public String getCompressionModelId() {
        return compressionModelId;
    }

    /**
     * 显式配置的上下文窗口大小（tokens）。返回 {@link #CONTEXT_SIZE_UNSET} 表示未设置，
     * 此时调用方应回退到 {@link ModelContextParser#parse(String)} 解析旧 modelId 后缀。
     */
    public int getContextSize() {
        return contextSize;
    }

    /** 服务商分组 id；空串表示独立的单模型配置（旧数据）。 */
    public String getGroupId() {
        return groupId;
    }

    /** 槽位角色（main/haiku/sonnet/opus）；空串按 main 处理。 */
    public String getSlotRole() {
        return slotRole;
    }

    public String getEffectiveSlotRole() {
        return slotRole.length() == 0 ? SLOT_MAIN : slotRole;
    }

    public String getEffectiveCompressionModelId() {
        if (!compressionModelEnabled || compressionModelAuto || compressionModelId.length() == 0) {
            return modelId;
        }
        return compressionModelId;
    }

    public ModelConfig withId(String nextId) {
        return new ModelConfig(nextId, name, protocolType, providerLabel, baseUrl, apiKey, modelId, toolCallLimit,
                compressionModelEnabled, compressionModelAuto, compressionModelId, contextSize, groupId, slotRole);
    }

    public ModelConfig withModelId(String nextModelId) {
        return new ModelConfig(id, name, protocolType, providerLabel, baseUrl, apiKey, nextModelId, toolCallLimit,
                compressionModelEnabled, compressionModelAuto, compressionModelId, contextSize, groupId, slotRole);
    }

    /** 返回一个更新了 contextSize 的副本。 */
    public ModelConfig withContextSize(int nextContextSize) {
        return new ModelConfig(id, name, protocolType, providerLabel, baseUrl, apiKey, modelId, toolCallLimit,
                compressionModelEnabled, compressionModelAuto, compressionModelId, nextContextSize, groupId, slotRole);
    }

    public ModelConfig withGroup(String nextGroupId, String nextSlotRole) {
        return new ModelConfig(id, name, protocolType, providerLabel, baseUrl, apiKey, modelId, toolCallLimit,
                compressionModelEnabled, compressionModelAuto, compressionModelId, contextSize,
                nextGroupId, nextSlotRole);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("protocolType", protocolType.name());
        object.put("providerLabel", providerLabel);
        object.put("baseUrl", baseUrl);
        object.put("apiKey", apiKey);
        object.put("modelId", modelId);
        object.put("toolCallLimit", toolCallLimit);
        object.put("compressionModelEnabled", compressionModelEnabled);
        object.put("compressionModelAuto", compressionModelAuto);
        object.put("compressionModelId", compressionModelId);
        object.put("contextSize", contextSize);
        object.put("groupId", groupId);
        object.put("slotRole", slotRole);
        return object;
    }

    public static ModelConfig fromJson(JSONObject object) {
        String protocolValue = object.optString("protocolType");
        if (protocolValue.length() == 0) {
            protocolValue = object.optString("provider");
        }
        String providerLabel = object.optString("providerLabel");
        if (providerLabel.length() == 0) {
            providerLabel = ModelProtocolType.fromStorage(protocolValue).getLabel();
        }
        String modelId = object.optString("modelId");
        JSONObject localModel = object.optJSONObject("localModel");
        if (modelId.length() == 0 && localModel != null) {
            modelId = localModel.optString("fileName", localModel.optString("localPath"));
        }
        int toolCallLimit = DEFAULT_TOOL_CALL_LIMIT;
        if (object.has("toolCallLimit")) {
            toolCallLimit = object.optInt("toolCallLimit", DEFAULT_TOOL_CALL_LIMIT);
        } else if (object.has("tool_call_limit")) {
            toolCallLimit = object.optInt("tool_call_limit", DEFAULT_TOOL_CALL_LIMIT);
        }
        boolean compressionModelEnabled = object.optBoolean(
                "compressionModelEnabled",
                object.optBoolean("compression_model_enabled", false)
        );
        boolean compressionModelAuto = object.optBoolean(
                "compressionModelAuto",
                object.optBoolean("compression_model_auto", DEFAULT_COMPRESSION_MODEL_AUTO)
        );
        String compressionModelId = object.optString("compressionModelId", object.optString("compression_model_id"));
        int contextSize = object.optInt("contextSize", object.optInt("context_size", CONTEXT_SIZE_UNSET));
        if (contextSize < 0) {
            contextSize = CONTEXT_SIZE_UNSET;
        }
        return new ModelConfig(
                object.optString("id"),
                object.optString("name"),
                ModelProtocolType.fromStorage(protocolValue),
                providerLabel,
                object.optString("baseUrl"),
                object.optString("apiKey"),
                modelId,
                toolCallLimit,
                compressionModelEnabled,
                compressionModelAuto,
                compressionModelId,
                contextSize,
                object.optString("groupId", ""),
                object.optString("slotRole", "")
        );
    }

    public static int normalizeToolCallLimit(int limit) {
        if (limit == UNLIMITED_TOOL_CALLS) {
            return UNLIMITED_TOOL_CALLS;
        }
        return Math.max(0, limit);
    }
}
