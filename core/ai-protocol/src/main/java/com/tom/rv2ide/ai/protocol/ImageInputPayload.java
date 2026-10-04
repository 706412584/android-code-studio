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

import org.json.JSONObject;

public final class ImageInputPayload {
    public static final String KIND = "linecode_image_understanding";

    /**
     * 工具结果携带图片时的 kind。
     *
     * <p>与用户附件的 {@link #KIND} 分开，因为二者的语义不同：附件是「用户贴了一张图」，
     * 需要把 prompt 与图片一起发给模型；工具结果图片是「工具读到一张图」，文字说明已经
     * 在 {@code ToolModelMessage.content} 里，图片是附加的可视内容。
     */
    public static final String KIND_TOOL_RESULT = "linecode_tool_result_image";

    private ImageInputPayload() {
    }

    public static String rawInputJson(String prompt, String mimeType, String dataBase64) throws org.json.JSONException {
        return new JSONObject()
                .put("kind", KIND)
                .put("prompt", safe(prompt))
                .put("mime_type", normalizeMimeType(mimeType))
                .put("data_base64", safe(dataBase64))
                .toString();
    }

    /**
     * 构造「工具结果图片」的 rawInputJson，挂到 {@code ToolModelMessage} 上。
     *
     * @param mimeType 图片 MIME 类型
     * @param dataBase64 base64 图片数据（不含 data URL 前缀）
     */
    public static String imageResultJson(String mimeType, String dataBase64) throws org.json.JSONException {
        return new JSONObject()
                .put("kind", KIND_TOOL_RESULT)
                .put("mime_type", normalizeMimeType(mimeType))
                .put("data_base64", safe(dataBase64))
                .toString();
    }

    public static Payload fromRawInputJson(String rawInputJson) {
        if (rawInputJson == null || rawInputJson.trim().length() == 0) {
            return null;
        }
        String raw = rawInputJson.trim();
        if (!raw.startsWith("{")) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(raw);
            if (!KIND.equals(object.optString("kind"))) {
                return null;
            }
            String dataBase64 = object.optString("data_base64").trim();
            if (dataBase64.length() == 0) {
                return null;
            }
            return new Payload(
                    object.optString("prompt"),
                    normalizeMimeType(object.optString("mime_type")),
                    dataBase64
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 解析「工具结果图片」负载；不是该 kind 或数据为空时返回 {@code null}。
     *
     * <p>与 {@link #fromRawInputJson} 分开：那个只认用户附件的 kind，用它来解析工具结果
     * 会返回 null，导致工具图片被静默丢弃。
     */
    public static Payload fromImageResult(String rawInputJson) {
        if (rawInputJson == null || rawInputJson.trim().length() == 0) {
            return null;
        }
        String raw = rawInputJson.trim();
        if (!raw.startsWith("{")) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(raw);
            if (!KIND_TOOL_RESULT.equals(object.optString("kind"))) {
                return null;
            }
            String dataBase64 = object.optString("data_base64").trim();
            if (dataBase64.length() == 0) {
                return null;
            }
            return new Payload(
                    "",
                    normalizeMimeType(object.optString("mime_type")),
                    dataBase64
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String normalizeMimeType(String mimeType) {
        String value = safe(mimeType).toLowerCase(java.util.Locale.ROOT);
        if ("image/jpg".equals(value)) {
            return "image/jpeg";
        }
        if (isSupportedMimeType(value)) {
            return value;
        }
        return "image/png";
    }

    public static boolean isSupportedMimeType(String mimeType) {
        String value = safe(mimeType).toLowerCase(java.util.Locale.ROOT);
        return "image/png".equals(value)
                || "image/jpeg".equals(value)
                || "image/webp".equals(value)
                || "image/gif".equals(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Payload {
        private final String prompt;
        private final String mimeType;
        private final String dataBase64;

        private Payload(String prompt, String mimeType, String dataBase64) {
            this.prompt = prompt == null ? "" : prompt;
            this.mimeType = normalizeMimeType(mimeType);
            this.dataBase64 = dataBase64 == null ? "" : dataBase64;
        }

        public String getPrompt() {
            return prompt;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getDataBase64() {
            return dataBase64;
        }

        public String dataUrl() {
            return "data:" + mimeType + ";base64," + dataBase64;
        }
    }
}
