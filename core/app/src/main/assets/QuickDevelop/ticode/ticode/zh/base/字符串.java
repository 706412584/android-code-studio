package ticode.zh.base;

import android.os.*;
import java.util.concurrent.*;

public abstract class 字符串 implements CharSequence {
public int 长度() {
return this.length();
}

public char 取字符(int 索引) {
return this.charAt(索引);
}
}