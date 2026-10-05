package ticode.zh.base;

import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.正则表达式;

public class 基本类型模板类<T1> {



public String 到文本() {
return String.valueOf(this);
}




public T1 到对象() {
return (T1) this;
}
}