package ticode.base;

import android.os.*;
import java.util.concurrent.*;

public class JString implements CharSequence {
public int 长度() {
return this.length();
}

public char 取字符(int 索引) {
return this.charAt(索引);
}
}