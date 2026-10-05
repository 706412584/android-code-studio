package ticode.android;

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
import android.os.*;
import android.app.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.io.*;
import java.net.*;
import java.math.*;
import java.io.*;

import ticode.base.JException;
import ticode.base.TextBox;
import ticode.jvm.JCollection;
import ticode.jvm.JHashMap;
import ticode.jvm.KeyValuePair;
import ticode.jvm.UUID;

public class CommonOps {





public static void 发送调试信息(Object 信息) {
if (信息 instanceof Exception) {
tdr.util.TDRSender.sendCrash((Exception) 信息);
} else {
tdr.util.TDRSender.sendLogcatLine(String.valueOf(信息));
}
}

public static void 运行报错(String 错误信息) {
throw new RuntimeException(错误信息);
}

public static Object 调试输出(Object 内容) {
//开启日志过滤后，结绳只会显示TieApp标签的日志信息
return android.util.Log.i("TieApp", String.valueOf(内容));
}

public static Object 调试输出2(String 格式, Object[] 参数) {
return android.util.Log.i("TieApp", String.format(格式, 参数));
}
}