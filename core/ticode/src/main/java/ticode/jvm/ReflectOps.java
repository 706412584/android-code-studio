package ticode.jvm;


import ticode.android.AndroidEnv;

import static ticode.android.ArrayOps.取数组长度;

public class ReflectOps {

// 设置某个字段的值，如果是静态字段，可省略类实例
public static boolean 设置字段值(String 字段所在类类名, Object 类实例, String 字段名, Object 值) {
try {
JavaClass 类1 = 字段所在类类名;
设置字段值2(类1,类实例,字段名,值);
return true;
} catch(Exception e) {
return false;
}
}

// 设置某个字段的值，如果是静态字段，可省略类实例
public static boolean 设置字段值2(JavaClass 字段所在类, Object 类实例, String 字段名, Object 值) {
try {
JavaField 字段 = 字段所在类.getDeclaredField(字段名);
设置字段值3(类实例,字段,值);
return true;
} catch(Exception e) {
return false;
}
}

// 设置某个字段的值，如果是静态字段，可省略类实例
public static boolean 设置字段值3(Object 类实例, JavaField 字段, Object 值) {
try {
字段.可访问 = true;
字段.置对象值(类实例, 值);
return true;
} catch(Exception e) {
return false;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public static Object 获取字段值(String 字段所在类类名, Object 类实例, String 字段名) {
try {
JavaClass 类1 = 字段所在类类名;
return 获取字段值2(类1,类实例,字段名);
} catch(Exception e) {
return null;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public static Object 获取字段值2(JavaClass 字段所在类, Object 类实例, String 字段名) {
try {
JavaField 字段 = 字段所在类.getDeclaredField(字段名);
return 获取字段值3(类实例,字段);
} catch(Exception e) {
return null;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public static Object 获取字段值3(Object 类实例, JavaField 字段) {
try {
字段.可访问 = true;
return 字段.取对象值(类实例);
} catch(Exception e) {
return null;
}
}

// 执行某个没有参数的方法，如果是静态方法，可省略类实例
public static Object 执行无参方法(String 方法所在类类名, Object 类实例, String 方法名) {
try {
JavaClass 类1 = 方法所在类类名;
return 执行无参方法2(类1,类实例,方法名);
} catch(Exception e) {
return null;
}
}

// 执行某个没有参数的方法，如果是静态方法，可省略类实例
public static Object 执行无参方法2(JavaClass 方法所在类, Object 类实例, String 方法名) {
try {
JavaMethod 方法1 = 方法所在类.取方法(方法名);
return 执行方法3(类实例,方法1,null);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public static Object 执行方法(String 方法所在类类名, Object 类实例, String 方法名, JavaClass[] 参数类型, Object[] 参数) {
try {
JavaClass 类1 = 方法所在类类名;
return 执行方法2(类1,类实例,方法名,参数类型,参数);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public static Object 执行方法2(JavaClass 方法所在类, Object 类实例, String 方法名, JavaClass[] 参数类型, Object[] 参数) {
try {
JavaMethod 方法1 = 方法所在类.取方法(方法名,参数类型);
return 执行方法3(类实例,方法1,参数);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public static Object 执行方法3(Object 类实例, JavaMethod 方法1, Object[] 参数) {
try {
方法1.可访问 = true;
return 方法1.执行(类实例,参数);
} catch(Exception e) {
return null;
}
}

// 创建某个类的实例
public static Object 创建实例(String 欲创建实例类类名, JavaClass[] 参数类型, Object[] 参数) {
try {
JavaClass 类1 = 欲创建实例类类名;
return 创建实例2(类1,参数类型,参数);
} catch(Exception e) {
return null;
}
}

// 创建某个类的实例
public static Object 创建实例2(JavaClass 欲创建实例类, JavaClass[] 参数类型, Object[] 参数) {
try {
JavaConstructor 构造方法 = 欲创建实例类.取构造方法(参数类型);
构造方法.可访问 = true;
return 构造方法.创建实例(参数);
} catch(Exception e) {
return null;
}
}

// 根据字段的类型寻找类中此类型的所有字段
public static JavaField[] 根据类型寻找字段(String 字段所在类类名, String 字段类型) {
try {
JavaClass 类1 = 字段所在类类名;
return 根据类型寻找字段2(类1,字段类型);
} catch(Exception e) {
return null;
}
}

// 根据字段的类型寻找类中此类型的所有字段
public static JavaField[] 根据类型寻找字段2(JavaClass 字段所在类, String 字段类型) {
try {
java.util.List<java.lang.reflect.Field> list = new java.util.ArrayList<>();
JavaField[] 字段集 = 字段所在类.取所有字段();
int i;
for (i = 0; i < (字段集).length; i++) {
JavaField 字段 = 字段集[i];
if (字段 != null && 字段.类型.完整类名 == 字段类型) {
list.add(字段);
}
}
return list.toArray(new java.lang.reflect.Field[0]);
} catch(Exception e) {
return null;
}
}

// 根据字段的类型寻找类中此类型的所有字段
public static JavaField[] 根据类型寻找字段3(JavaClass 字段所在类, JavaClass 字段类型) {
try {
return 根据类型寻找字段2(字段所在类,字段类型.完整类名);
} catch(Exception e) {
return null;
}
}







public static DexClassLoader2 加载Dex文件(AndroidEnv 环境, String Dex文件路径, String so库搜寻目录) {
return new dalvik.system.DexClassLoader(Dex文件路径, 环境.getCodeCacheDir().getAbsolutePath(), so库搜寻目录, 环境.getClassLoader());
}

}