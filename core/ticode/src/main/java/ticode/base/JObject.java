package ticode.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.jvm.JRegex;
import ticode.jvm.JavaClass;

public class JObject extends Object {



public String 到文本() {
return String.valueOf(this);
}




public JavaClass 取类信息() {
return this.getClass();
}






public boolean 是否相等(Object 被比较目标) {
return this.equals(被比较目标);
}





public int 取哈希值() {
return this.hashCode();
}

}