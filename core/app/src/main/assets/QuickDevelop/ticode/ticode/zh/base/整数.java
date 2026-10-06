package ticode.zh.base;

import android.os.*;
import java.util.concurrent.*;

public class 整数 extends 基本类型模板类<整数类> {
public static byte 到字节(int 值) {
return (byte)值;
}

public static String 到十六进制(int 值) {
return Integer.toHexString(值);
}

public static String 到八进制(int 值) {
return Integer.toOctalString(值);
}

public static String 到二进制(int 值) {
return Integer.toBinaryString(值);
}
}