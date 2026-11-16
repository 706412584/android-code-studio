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

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** {@link StableJson#write(Appendable, Object)} 与 {@link StableJson#stringify(Object)} 输出必须一致。 */
class StableJsonWriteTest {

  @Test
  void writeMatchesStringifyForNestedStructure() throws Exception {
    JSONObject object =
        new JSONObject()
            .put("z", JSONObject.NULL)
            .put("a", new JSONArray().put("x").put(2).put(true))
            .put("m", new JSONObject().put("b", 1).put("a", "q\"uote\\slash"));
    StringBuilder out = new StringBuilder();
    StableJson.write(out, object);
    assertEquals(StableJson.stringify(object), out.toString());
  }

  @Test
  void writeMatchesStringifyForLargePayload() throws Exception {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 20000; i++) {
      big.append('A');
    }
    JSONObject object = new JSONObject().put("data", big.toString());
    StringBuilder out = new StringBuilder();
    StableJson.write(out, object);
    assertEquals(StableJson.stringify(object), out.toString());
  }
}
