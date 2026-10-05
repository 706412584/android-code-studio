package ticode.zh.android;

import java.io.*;
import java.util.*;
import java.lang.reflect.*;
import android.view.*;
import android.util.*;
import android.net.*;
import android.database.*;
import android.provider.*;
import android.content.*;
import android.content.res.*;
import android.os.*;
import android.system.*;
import android.graphics.*;
import android.app.*;
import java.util.regex.*;
import java.net.*;
import java.math.*;

public class ROOT操作 {
public static boolean 是否ROOT() {
return 文件操作.文件是否存在("/system/bin/su") || 文件操作.文件是否存在("/system/xbin/su");
}

public static boolean 获取ROOT() {
try {
return Runtime.getRuntime().exec("su -c exit").waitFor() == 0;
} catch (Throwable e) {
}
return false;
}

public static String 执行命令(String 命令, String[] 环境变量, String 工作目录) {
try {
Process process = Runtime.getRuntime().exec(命令, 环境变量, 工作目录 == null ? null : new File(工作目录));
process.waitFor();
InputStream es = process.getErrorStream();
InputStream is = es.available() > 0 ? es : process.getInputStream();
byte[] buf = new byte[Math.max(0, is.available() - 1)];
is.read(buf);
is.close();
return new String(buf);
} catch (Throwable e) {
return e.toString();
}
}

public static String 执行ROOT命令(String 命令, String[] 环境变量, String 工作目录) {
return 执行命令("su -c " + 命令, 环境变量, 工作目录);
}

public static String 执行二进制文件(String 文件路径, String[] 环境变量, String 工作目录) {
return 执行ROOT命令("chmod 777 " + 文件路径, 环境变量, 工作目录) + 执行ROOT命令(文件路径, 环境变量, 工作目录);
}

public static String 免ROOT执行二进制文件(android.content.Context 环境, String 文件路径, String[] 环境变量, String 工作目录) {
Object 私有二进制文件路径 = 环境.取内部私有缓存目录路径() + "/" + 文件操作.取文件名(文件路径);
文件操作.复制文件(文件路径, 私有二进制文件路径);
return 执行命令("chmod 777 " + 私有二进制文件路径, 环境变量, 工作目录) + 执行命令(私有二进制文件路径, 环境变量, 工作目录);
}
}