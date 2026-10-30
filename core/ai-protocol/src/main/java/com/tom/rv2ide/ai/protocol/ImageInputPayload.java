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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
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

    /** 单图负载：与旧版线格式完全一致。 */
    public static String rawInputJson(String prompt, String mimeType, String dataBase64) throws org.json.JSONException {
        return new JSONObject()
                .put("kind", KIND)
                .put("prompt", safe(prompt))
                .put("mime_type", normalizeMimeType(mimeType))
                .put("data_base64", safe(dataBase64))
                .toString();
    }

    /**
     * 多图负载。
     *
     * <p><b>为什么不是「把单图字段改成数组」</b>：会话日志里已经存着一批单图格式的用户消息，
     * 改动线格式会让那些历史图片在续接会话时静默消失。因此数组放在**新增的** {@code images}
     * 字段里，同时仍把第一张写进旧的 {@code mime_type} / {@code data_base64}——
     * 旧读取端（以及没升级的序列化路径）看到的行为与从前完全一致。
     *
     * @param images 按用户选择顺序排列；空列表等价于「没有图片」，调用方应改走纯文本路径
     */
    public static String rawInputJson(String prompt, List<ImagePart> images) throws org.json.JSONException {
        if (images == null || images.isEmpty()) {
            return rawInputJson(prompt, "", "");
        }
        ImagePart first = images.get(0);
        JSONArray array = new JSONArray();
        for (ImagePart part : images) {
            array.put(new JSONObject()
                    .put("mime_type", normalizeMimeType(part.getMimeType()))
                    .put("data_base64", safe(part.getDataBase64())));
        }
        return new JSONObject()
                .put("kind", KIND)
                .put("prompt", safe(prompt))
                .put("mime_type", normalizeMimeType(first.getMimeType()))
                .put("data_base64", safe(first.getDataBase64()))
                .put("images", array)
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
            JSONArray images = object.optJSONArray("images");
            List<ImagePart> parts = readParts(images);
            if (parts.isEmpty()) {
                // 旧格式（无 images 数组）：按单图处理。
                if (dataBase64.length() == 0) {
                    return null;
                }
                parts = Collections.singletonList(
                        new ImagePart(normalizeMimeType(object.optString("mime_type")), dataBase64));
            }
            return new Payload(object.optString("prompt"), parts);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 读 {@code images} 数组；缺字段、类型不符或全为空时返回空列表。 */
    private static List<ImagePart> readParts(JSONArray array) {
        List<ImagePart> parts = new ArrayList<>();
        if (array == null) {
            return parts;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String data = item.optString("data_base64").trim();
            if (data.isEmpty()) {
                continue;
            }
            parts.add(new ImagePart(normalizeMimeType(item.optString("mime_type")), data));
        }
        return parts;
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

    /** 一张待发送的图片。 */
    public static final class ImagePart {
        private final String mimeType;
        private final String dataBase64;

        public ImagePart(String mimeType, String dataBase64) {
            this.mimeType = normalizeMimeType(mimeType);
            this.dataBase64 = dataBase64 == null ? "" : dataBase64;
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

    public static final class Payload {
        private final String prompt;
        private final List<ImagePart> images;

        private Payload(String prompt, List<ImagePart> images) {
            this.prompt = prompt == null ? "" : prompt;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
        }

        private Payload(String prompt, String mimeType, String dataBase64) {
            this(prompt, Collections.singletonList(new ImagePart(mimeType, dataBase64)));
        }

        public String getPrompt() {
            return prompt;
        }

        /** 全部图片，按用户选择顺序；至少一张（解析不出图时整条负载为 null）。 */
        public List<ImagePart> getImages() {
            return images;
        }

        /** 第一张图片的 MIME。保留是为了让只关心单图的旧调用点不必改。 */
        public String getMimeType() {
            return images.get(0).getMimeType();
        }

        /** 第一张图片的 base64。同上。 */
        public String getDataBase64() {
            return images.get(0).getDataBase64();
        }

        public String dataUrl() {
            return images.get(0).dataUrl();
        }
    }
}
