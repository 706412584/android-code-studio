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
import java.lang.System;
import java.util.Stack;
import android.app.*;
import java.util.regex.*;
import java.net.*;
import java.math.*;

import ticode.base.JException;
import ticode.base.TextBox;
import ticode.jvm.JCollection;
import ticode.jvm.JHashMap;
import ticode.jvm.KeyValuePair;
import ticode.jvm.UUID;

public class ObjectOps {
public static KeyValuePair 键值对(Object 键, Object 值) {
KeyValuePair 结果 = new KeyValuePair(键, 值);
return 结果;
}

public static JHashMap 哈希表(KeyValuePair[] 值) {
JHashMap 结果;
while (值 -> v) {
结果[v.键] = v.值;
}
return 结果;
}

public static Object 新建对象(Object 类型) {
return new 类型();
}

public static WindowComponent 新建窗口组件(Object 类型, AndroidEnv 环境) {
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