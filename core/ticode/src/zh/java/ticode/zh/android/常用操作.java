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
import java.lang.System;
import java.util.Stack;
import android.app.*;
import java.util.regex.*;
import java.net.*;
import java.math.*;

import ticode.zh.base.异常;
import ticode.zh.base.数字;
import ticode.zh.base.文本;
import ticode.zh.base.长整数;
import ticode.zh.jvm.UUID;
import ticode.zh.jvm.哈希表;
import ticode.zh.jvm.键值对;
import ticode.zh.jvm.集合;

public class 常用操作 {






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