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
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Stable object key order for request bodies and tool schemas; array order remains semantic. */
public final class StableJson {
    private StableJson() { }

    public static String stringify(Object value) throws JSONException {
        StringBuilder result = new StringBuilder();
        try {
            write(result, value);
        } catch (java.io.IOException e) {
            // StringBuilder 不会真的抛 IOException；包一层只是签名需要。
            throw new IllegalStateException(e);
        }
        return result.toString();
    }

    /**
     * 把 JSON **流式写出**到 {@code out}，不构造整包字符串。
     *
     * <p><b>为什么要它</b>：请求体里可能带着图片的 base64（单张数 MB、多张叠加），
     * 旧的 {@code stringify} 会先递归地为每个子节点生成字符串再拼成一个**整包大 String**，
     * 峰值比 payload 本身还高。直接写入 {@link Appendable}（如 HTTP 输出的 Writer）后，
     * 内存里不再出现那份整包字符串，峰值降到接近 payload 本身。
     *
     * <p>输出与 {@link #stringify} 逐字节一致（对象键按字典序，数组保序）。
     */
    public static void write(Appendable out, Object value) throws JSONException, java.io.IOException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                String key = keys.get(i);
                out.append(JSONObject.quote(key)).append(':');
                write(out, object.get(key));
            }
            out.append('}');
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            out.append('[');
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                write(out, array.get(i));
            }
            out.append(']');
            return;
        }
        if (value == null || value == JSONObject.NULL) {
            out.append("null");
        } else if (value instanceof Number) {
            out.append(JSONObject.numberToString((Number) value));
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else {
            out.append(JSONObject.quote(value.toString()));
        }
    }
}
