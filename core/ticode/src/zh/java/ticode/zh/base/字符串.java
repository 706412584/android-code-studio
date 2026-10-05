package ticode.zh.base;

import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.正则表达式;

public class 字符串 implements CharSequence {
public int 长度() {
return this.length();
}

public char 取字符(int 索引) {
return this.charAt(索引);
}
}