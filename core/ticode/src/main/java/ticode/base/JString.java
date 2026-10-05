package ticode.base;

import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.jvm.JRegex;
import ticode.jvm.JavaClass;

public class JString extends CharSequence {
public int 长度() {
return this.length();
}

public char 取字符(int 索引) {
return this.charAt(索引);
}
}