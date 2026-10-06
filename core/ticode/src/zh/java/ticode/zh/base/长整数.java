package ticode.zh.base;

import android.os.*;
import java.util.concurrent.*;

public abstract class 长整数 extends 基本类型模板类<长整数类> {

public static String 到十六进制(long 值) {
return Long.toHexString(值);
}

public static String 到八进制(long 值) {
return Long.toOctalString(值);
}

public static String 到二进制(long 值) {
return Long.toBinaryString(值);
}

}