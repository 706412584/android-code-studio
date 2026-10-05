package 结绳.JVM;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

public class 反射操作 {

// 设置某个字段的值，如果是静态字段，可省略类实例
public boolean 设置字段值(String 字段所在类类名, Object 类实例, String 字段名, Object 值) {
try {
Java类 类1 = 字段所在类类名;
设置字段值2(类1,类实例,字段名,值);
return true;
} catch(Exception e) {
return false;
}
}

// 设置某个字段的值，如果是静态字段，可省略类实例
public boolean 设置字段值2(Java类 字段所在类, Object 类实例, String 字段名, Object 值) {
try {
Java字段 字段 = 字段所在类.取字段(字段名);
设置字段值3(类实例,字段,值);
return true;
} catch(Exception e) {
return false;
}
}

// 设置某个字段的值，如果是静态字段，可省略类实例
public boolean 设置字段值3(Object 类实例, Java字段 字段, Object 值) {
try {
字段.可访问 = true;
字段.置对象值(类实例, 值);
return true;
} catch(Exception e) {
return false;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public Object 获取字段值(String 字段所在类类名, Object 类实例, String 字段名) {
try {
Java类 类1 = 字段所在类类名;
return 获取字段值2(类1,类实例,字段名);
} catch(Exception e) {
return null;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public Object 获取字段值2(Java类 字段所在类, Object 类实例, String 字段名) {
try {
Java字段 字段 = 字段所在类.取字段(字段名);
return 获取字段值3(类实例,字段);
} catch(Exception e) {
return null;
}
}

// 获取某个字段的值，如果是静态字段，可省略类实例
public Object 获取字段值3(Object 类实例, Java字段 字段) {
try {
字段.可访问 = true;
return 字段.取对象值(类实例);
} catch(Exception e) {
return null;
}
}

// 执行某个没有参数的方法，如果是静态方法，可省略类实例
public Object 执行无参方法(String 方法所在类类名, Object 类实例, String 方法名) {
try {
Java类 类1 = 方法所在类类名;
return 执行无参方法2(类1,类实例,方法名);
} catch(Exception e) {
return null;
}
}

// 执行某个没有参数的方法，如果是静态方法，可省略类实例
public Object 执行无参方法2(Java类 方法所在类, Object 类实例, String 方法名) {
try {
Java方法 方法1 = 方法所在类.取方法(方法名);
return 执行方法3(类实例,方法1);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public Object 执行方法(String 方法所在类类名, Object 类实例, String 方法名, Java类[] 参数类型, Object[] 参数) {
try {
Java类 类1 = 方法所在类类名;
return 执行方法2(类1,类实例,方法名,参数类型,参数);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public Object 执行方法2(Java类 方法所在类, Object 类实例, String 方法名, Java类[] 参数类型, Object[] 参数) {
try {
Java方法 方法1 = 方法所在类.取方法(方法名,参数类型);
return 执行方法3(类实例,方法1,参数);
} catch(Exception e) {
return null;
}
}

// 执行某个方法，如果是静态方法，可省略类实例
public Object 执行方法3(Object 类实例, Java方法 方法1, Object[] 参数) {
try {
方法1.可访问 = true;
return 方法1.执行(类实例,参数);
} catch(Exception e) {
return null;
}
}

// 创建某个类的实例
public Object 创建实例(String 欲创建实例类类名, Java类[] 参数类型, Object[] 参数) {
try {
Java类 类1 = 欲创建实例类类名;
return 创建实例2(类1,参数类型,参数);
} catch(Exception e) {
return null;
}
}

// 创建某个类的实例
public Object 创建实例2(Java类 欲创建实例类, Java类[] 参数类型, Object[] 参数) {
try {
Java构造方法 构造方法 = 欲创建实例类.取构造方法(参数类型);
构造方法.可访问 = true;
return 构造方法.创建实例(参数);
} catch(Exception e) {
return null;
}
}

// 根据字段的类型寻找类中此类型的所有字段
public Java字段[] 根据类型寻找字段(String 字段所在类类名, String 字段类型) {
try {
Java类 类1 = 字段所在类类名;
return 根据类型寻找字段2(类1,字段类型);
} catch(Exception e) {
return null;
}
}

// 根据字段的类型寻找类中此类型的所有字段
public Java字段[] 根据类型寻找字段2(Java类 字段所在类, String 字段类型) {
try {
java.util.List<java.lang.reflect.Field> list = new java.util.ArrayList<>();
Java字段[] 字段集 = 字段所在类.取所有字段();
int i;
while (i) {
Java字段 字段 = 字段集[i];
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
public Java字段[] 根据类型寻找字段3(Java类 字段所在类, Java类 字段类型) {
try {
return 根据类型寻找字段2(字段所在类,字段类型.完整类名);
} catch(Exception e) {
return null;
}
}







public Dex类加载器 加载Dex文件(安卓环境 环境, String Dex文件路径, String so库搜寻目录) {
return new dalvik.system.DexClassLoader(Dex文件路径, 环境.getCodeCacheDir().getAbsolutePath(), so库搜寻目录, 环境.getClassLoader());
}

}

//本类指代一个dex文件
