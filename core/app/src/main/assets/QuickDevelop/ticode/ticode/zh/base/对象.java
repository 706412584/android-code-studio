package ticode.zh.base;

import android.os.*;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;

public abstract class 对象 extends Object {



public String 到文本() {
return String.valueOf(this);
}




public java.lang.Class 取类信息() {
return (java.lang.Class)this.getClass();
}






public boolean 是否相等(Object 被比较目标) {
return this.equals(被比较目标);
}





public int 取哈希值() {
return this.hashCode();
}

}