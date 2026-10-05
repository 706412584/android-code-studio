package ticode.zh.android;

import android.os.Environment;
import android.content.pm.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.文件;

public class 数据包 extends android.os.Bundle {

public void 置入(String 键名, Object 数据) {
if(数据 instanceof Integer){
this.putInt(键名, (int)数据);
} else if (数据 instanceof Boolean){
this.putBoolean(键名, (boolean)数据);
} else if (数据 instanceof Float){
this.putFloat(键名, (float)数据);
} else if (数据 instanceof Long){
this.putLong(键名, (long)数据);
} else if (数据 instanceof String){
this.putString(键名, (String)数据);
} else if (数据 instanceof Character){
this.putChar(键名,(char)数据);
} else if (数据 instanceof java.io.Serializable){
this.putSerializable(键名, (java.io.Serializable)数据);
} else{
this.putString(键名, 数据.toString());
}
}

public String 取文本(String 键名) {
return this.getString(键名);
}

public int 取整数(String 键名) {
return this.getInt(键名);
}

public long 取长整数(String 键名) {
return this.getLong(键名);
}

public char 取字符(String 键名) {
return this.getChar(键名);
}

public boolean 取逻辑值(String 键名) {
return this.getBoolean(键名);
}

public Object 取序列化对象(String 键名) {
return this.getSerializable(键名);
}
}