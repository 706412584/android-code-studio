package ticode.android;

import org.json.*;
import java.io.*;
import org.xmlpull.v1.*;

public class JsonObject extends org.json.JSONObject {



public void 赋值_op(String JSON文本) {
if(JSON文本 == null || JSON文本.isEmpty()) {
throw new IllegalArgumentException("JSON文本不能为空");
}

try {
return new JSONObject(JSON文本);
} catch (Exception e) {
throw new IllegalArgumentException(JSON_INIT_ERROR, e);
}
}










public Object 取索引_op(String 键名) {
return 取对象(键名);
}












public void 设索引_op(String 键名, Object 值) {
try {
this.put(键名, 值);
} catch (JSONException e) {
e.printStackTrace();
}
}




public boolean 是_op(String 键名) {
return this.has(键名);
}










public JsonObject 取JSON对象(String 键名) {
try {
return this.getJSONObject(键名);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}








public JsonArray 取JSON数组(String 键名) {
try {
return this.getJSONArray(键名);
} catch (JSONException e) {
return null;
}
}










public Object 取对象(String 键名) {
try {
return this.get(键名);
} catch (JSONException e) {
e.printStackTrace();
}
return null;
}








public String 取文本(String 键名) {
try {
return this.getString(键名);
} catch (Exception e) {
e.printStackTrace();
return null;
}
}








public int 取整数(String 键名, int 默认值) {
try {
return this.getInt(键名);
} catch (Exception e) {
e.printStackTrace();
return 默认值;
}
}








public long 取长整数(String 键名, int 默认值) {
try {
return this.getLong(键名);
} catch (Exception e) {
e.printStackTrace();
return 默认值;
}
}








public double 取小数(String 键名, double 默认值) {
try {
return this.getDouble(键名);
} catch (Exception e) {
e.printStackTrace();
return 默认值;
}
}








public boolean 取逻辑值(String 键名, boolean 默认值) {
try {
return this.getBoolean(键名);
} catch (Exception e) {
e.printStackTrace();
return 默认值;
}
}












public void 置入(String 键名, Object 值) {
try {
this.put(键名, 值);
} catch (JSONException e) {
e.printStackTrace();
}
}












public void 移除(String 键名) {
this.remove(键名);
}




public boolean 是否存在(String 键名) {
return this.has(键名);
}




public String 到文本(int 缩进空格数) {
if (缩进空格数 == 0) {
return this.toString();
}
try {
return this.toString(缩进空格数);
} catch (JSONException e) {
e.printStackTrace();
return null;
}
}




public void 写出到文件(String 文件路径) {
Object 结果 = 到文本(3);
写出文本文件(文件路径, 结果);
}




public int 长度() {
return this.length();
}




public String[] 键名() {
java.util.List<String> list = new java.util.ArrayList<>();
java.util.Iterator<String> it = this.keys();
while (it.hasNext()) {
list.add(it.next());
}
return list.toArray(new String[0]);
}

private final static String JSON_INIT_ERROR = "JSON数据文本错误";
}