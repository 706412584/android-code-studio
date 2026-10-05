package ticode.zh.base;

import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.正则表达式;

public class 对象 extends Object {



public String 到文本() {
return String.valueOf(this);
}




public Java类 取类信息() {
return this.getClass();
}






public boolean 是否相等(Object 被比较目标) {
return this.equals(被比较目标);
}





public int 取哈希值() {
return this.hashCode();
}

}