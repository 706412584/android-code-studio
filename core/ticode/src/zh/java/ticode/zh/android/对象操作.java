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

import ticode.zh.base.异常;
import ticode.zh.base.数字;
import ticode.zh.base.文本;
import ticode.zh.base.长整数;
import ticode.zh.jvm.UUID;
import ticode.zh.jvm.哈希表;
import ticode.zh.jvm.键值对;
import ticode.zh.jvm.集合;

public class 对象操作 {
public static 键值对 键值对(Object 键, Object 值) {
键值对 结果 = new 键值对(键, 值);
return 结果;
}

public static 哈希表 哈希表(键值对[] 值) {
哈希表 结果;
while (值 -> v) {
结果[v.键] = v.值;
}
return 结果;
}

public static Object 新建对象(Object 类型) {
return new 类型();
}

public static 窗口组件 新建窗口组件(Object 类型, 安卓环境 环境) {
return new 类型(环境);
}





public static Object 读入对象(String 路径) {
try {
ObjectInputStream ois = new ObjectInputStream(new FileInputStream(路径));
return ois.readObject();
} catch (IOException e) {
e.printStackTrace();
} catch (ClassNotFoundException e) {
e.printStackTrace();
}
return null;
}





public static void 写出对象(Object 欲写出对象, String 欲写到路径) {
try {
ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(欲写到路径));
oos.writeObject(欲写出对象);
} catch (FileNotFoundException e) {
e.printStackTrace();
} catch (IOException e) {
e.printStackTrace();
}
}
}