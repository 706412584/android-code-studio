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

import ticode.zh.jvm.哈希表;
import ticode.zh.jvm.键值对;

public class 对象操作 {
public static 键值对 键值对(Object 键, Object 值) {
键值对 结果 = new 键值对(键, 值);
return 结果;
}

public static 哈希表 哈希表(键值对[] 值) {
哈希表 结果 = new 哈希表();
for (var v : 值) {
结果.put(v.键, v.值);
}
return 结果;
}

// 结绳 `变体型` 传类型名、用 `new #类型()` 动态实例化；Java 用反射等价实现。
public static Object 新建对象(String 类型) {
try {
return Class.forName(类型).getDeclaredConstructor().newInstance();
} catch (Exception e) {
return null;
}
}

public static 窗口组件 新建窗口组件(String 类型, android.content.Context 环境) {
try {
return (窗口组件) Class.forName(类型).getDeclaredConstructor(android.content.Context.class).newInstance(环境);
} catch (Exception e) {
return null;
}
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