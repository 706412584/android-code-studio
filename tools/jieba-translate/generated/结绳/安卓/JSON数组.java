package 结绳.安卓;

import org.json.*;
import org.json.*;
import java.io.*;
import org.xmlpull.v1.*;
import java.io.*;
import org.xmlpull.v1.*;

public class JSON数组 {



public void 赋值_op(String JSON文本) {
if(JSON文本 == null || JSON文本.isEmpty()) {
throw new IllegalArgumentException("JSON文本不能为空");
}

try {
return new JSONArray(JSON文本);
} catch (Exception e) {
throw new IllegalArgumentException(JSON_INIT_ERROR, e);
}
}







public Object 取索引_op(int 索引) {
return 取对象(索引);
}







public void 设索引_op(int 索引, Object 值) {
try {
this.put(索引, 值);
} catch (JSONException e) {
e.printStackTrace();
}
}











public JSON对象 取JSON对象(int 索引) {
return this.optJSONObject(索引);
}






public JSON数组 取JSON数组(int 索引) {
return this.optJSONArray(索引);
}







public Object 取对象(int 索引) {
try {
return this.get(索引);
} catch (JSONException e) {
e.printStackTrace();
}
return null;
}






public String 取文本(int 索引) {
return this.optString(索引);
}






public int 取整数(int 索引) {
return this.optInt(索引);
}






public double 取小数(int 索引) {
return this.optDouble(索引);
}






public boolean 取逻辑值(int 索引) {
return this.optBoolean(索引);
}




public void 置入(Object 值) {
this.put(值);
}




public void 移除(int 索引) {
this.remove(索引);
}




public int 长度() {
return this.length();
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

private final static String JSON_INIT_ERROR = "JSON数据文本错误";
}




