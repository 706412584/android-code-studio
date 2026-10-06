package ticode.zh.jvm;


import ticode.zh.android.安卓环境;

import static ticode.zh.android.数组操作.取数组长度;

public class 反射操作 {

// 设置某个字段的值，如果是静态字段，可省略类实例
public static boolean 设置字段值(String 字段所在类类名, Object 类实例, String 字段名, Object 值) {
try {
java.lang.Class 类1 = java.lang.Class.forName(字段所在类类名);
设置字段值2(类1,类实例,字段名,值);
return true;
} catch(Exception e) {
return false;
}
}

// 设置某个字段的值，如果是静态字段，可省略类实例
public static boolean 设置字段值2(java.lang.Class 字段所在类, Object 类实例, String 字段名, Object 值) {
try {
java.lang.reflect.Field 字段 = 字段所在类.getDeclaredField(字段名);
设置字段值3(类实例,字段,值);
return true;
} catch(Exception e) {
return false;
}
}

// 设置某个字段的值，如果是静态字段，可省略类实例
public static boolean 设置字段值3(Object 类实例, java.lang.reflect.Field 字段, Object 值) {
try {
字段.setAccessible(true);
字段.set(类实例, 值);
return true;
} catch(Exception e) {
return false;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public static Object 获取字段值(String 字段所在类类名, Object 类实例, String 字段名) {
try {
java.lang.Class 类1 = java.lang.Class.forName(字段所在类类名);
return 获取字段值2(类1,类实例,字段名);
} catch(Exception e) {
return null;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public static Object 获取字段值2(java.lang.Class 字段所在类, Object 类实例, String 字段名) {
try {
java.lang.reflect.Field 字段 = 字段所在类.getDeclaredField(字段名);
return 获取字段值3(类实例,字段);
} catch(Exception e) {
return null;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public static Object 获取字段值3(Object 类实例, java.lang.reflect.Field 字段) {
try {
字段.setAccessible(true);
return 字段.get(类实例);
} catch(Exception e) {
return null;
}
}

// 执行某个没有参数的方法，如果是静态方法，可省略类实例
public static Object 执行无参方法(String 方法所在类类名, Object 类实例, String 方法名) {
try {
java.lang.Class 类1 = java.lang.Class.forName(方法所在类类名);
return 执行无参方法2(类1,类实例,方法名);
} catch(Exception e) {
return null;
}
}

// 执行某个没有参数的方法，如果是静态方法，可省略类实例
public static Object 执行无参方法2(java.lang.Class 方法所在类, Object 类实例, String 方法名) {
try {
java.lang.reflect.Method 方法1 = 方法所在类.getDeclaredMethod(方法名);
return 执行方法3(类实例,方法1,null);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public static Object 执行方法(String 方法所在类类名, Object 类实例, String 方法名, java.lang.Class[] 参数类型, Object[] 参数) {
try {
java.lang.Class 类1 = java.lang.Class.forName(方法所在类类名);
return 执行方法2(类1,类实例,方法名,参数类型,参数);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public static Object 执行方法2(java.lang.Class 方法所在类, Object 类实例, String 方法名, java.lang.Class[] 参数类型, Object[] 参数) {
try {
java.lang.reflect.Method 方法1 = 方法所在类.getDeclaredMethod(方法名,参数类型);
return 执行方法3(类实例,方法1,参数);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public static Object 执行方法3(Object 类实例, java.lang.reflect.Method 方法1, Object[] 参数) {
try {
方法1.setAccessible(true);
return 方法1.invoke(类实例,参数);
} catch(Exception e) {
return null;
}
}

// 创建某个类的实例
public static Object 创建实例(String 欲创建实例类类名, java.lang.Class[] 参数类型, Object[] 参数) {
try {
java.lang.Class 类1 = java.lang.Class.forName(欲创建实例类类名);
return 创建实例2(类1,参数类型,参数);
} catch(Exception e) {
return null;
}
}

// 创建某个类的实例
public static Object 创建实例2(java.lang.Class 欲创建实例类, java.lang.Class[] 参数类型, Object[] 参数) {
try {
java.lang.reflect.Constructor 构造方法 = 欲创建实例类.getDeclaredConstructor(参数类型);
构造方法.setAccessible(true);
return 构造方法.newInstance(参数);
} catch(Exception e) {
return null;
}
}

// 根据字段的类型寻找类中此类型的所有字段
public static java.lang.reflect.Field[] 根据类型寻找字段(String 字段所在类类名, String 字段类型) {
try {
java.lang.Class 类1 = java.lang.Class.forName(字段所在类类名);
return 根据类型寻找字段2(类1,字段类型);
} catch(Exception e) {
return null;
}
}

// 根据字段的类型寻找类中此类型的所有字段
public static java.lang.reflect.Field[] 根据类型寻找字段2(java.lang.Class 字段所在类, String 字段类型) {
try {
java.util.List<java.lang.reflect.Field> list = new java.util.ArrayList<>();
java.lang.reflect.Field[] 字段集 = 字段所在类.getDeclaredFields();
int i;
for (i = 0; i < (字段集).length; i++) {
java.lang.reflect.Field 字段 = 字段集[i];
if (字段 != null && 字段.getType().getName().equals(字段类型)) {
list.add(字段);
}
}
return list.toArray(new java.lang.reflect.Field[0]);
} catch(Exception e) {
return null;
}
}

// 根据字段的类型寻找类中此类型的所有字段
public static java.lang.reflect.Field[] 根据类型寻找字段3(java.lang.Class 字段所在类, java.lang.Class 字段类型) {
try {
return 根据类型寻找字段2(字段所在类,字段类型.getName());
} catch(Exception e) {
return null;
}
}







public static Dex类加载器 加载Dex文件(android.content.Context 环境, String Dex文件路径, String so库搜寻目录) {
return new Dex类加载器(Dex文件路径, 环境.getCodeCacheDir().getAbsolutePath(), so库搜寻目录, 环境.getClassLoader());
}

}