package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class Java类 extends java.lang.Class {

public boolean 等于_op(Java类 另一个类) {
if (this == null) {
return 另一个类 == null;
}
return this.equals(另一个类);
}

public boolean 不等于_op(Java类 另一个类) {
if (this == null) {
return 另一个类 != null;
}
return !this.equals(另一个类);
}

public void 赋值_op(String 完整类名) {
try {
Class clazz = Class.forName(完整类名);
return clazz;
} catch (ClassNotFoundException e) {
throw new RuntimeException("找不到类：" + 完整类名);
}
}

public static Java类 取指定Java类(Object 类型) {
return 类型.class;
}

// 返回本类类名
public String 类名() {
return this.getSimpleName();
}















public String 完整类名() {
return this.getName();
}

// 返回本类的规范名称
public String 规范名称() {
return this.getCanonicalName();
}





public String 描述文本() {
return this.toGenericString();
}

// 返回本类是否是一个Java接口
public boolean 是接口() {
return this.isInterface();
}

// 返回本类是否是一个Java注解
public boolean 是注解() {
return this.isAnnotation();
}

// 返回本类是否是枚举类
public boolean 是枚举() {
return this.isEnum();
}

// 如果本类是枚举类，则返回本类所有枚举成员，否则返回 空
public Object[] 枚举成员() {
return this.getEnumConstants();
}

// 返回本类对象所代表是否为数组类型
public boolean 是数组() {
return this.isArray();
}

// 返回本类对象所代表是否为Java基本数据类型
public boolean 是基本类型() {
return this.isPrimitive();
}

// 返回本类是否是由Java编译器自动生成的类
public boolean 是合成类() {
return this.isSynthetic();
}

// 返回本类是否是匿名类
public boolean 是匿名类() {
return this.isAnonymousClass();
}

// 返回本类是否是方法中定义的局部类
public boolean 是局部类() {
return this.isLocalClass();
}

// 返回本类是否是内部类
public boolean 是内部类() {
return this.isMemberClass();
}

// 取本类的修饰符
public int 修饰符() {
return this.getModifiers();
}

// 取本类声明的泛型参数
public Java泛型变量[] 泛型参数() {
return this.getTypeParameters();
}

// 返回本类的父类，如果本类是 Object类 接口 基本类型 void 则返回空，如果本类代表数组类型，则返回Object类
public Java类 父类() {
return this.getSuperclass();
}

// 获取带有泛型的父类
public Java类型 带泛型父类() {
return this.getGenericSuperclass();
}

// 获取本类实现的接口，如果本类是接口，则返回本接口扩展的接口
public Java类[] 实现接口() {
return this.getInterfaces();
}

// 获取带有泛型的接口
public Java类型[] 带泛型实现接口() {
return this.getGenericInterfaces();
}

// 如果本类对象代表数组类，则返回数组的节点类，否则返回空
public Java类 数组节点类() {
return this.getComponentType();
}

// 如果本类是别的类里的成员类，则返回本类的外部类，否则返回 空
public Java类 外部类() {
return this.getEnclosingClass();
}

// 使用公开无参构造方法创建一个本类的实例
public Object 创建实例() {
return 取公开构造方法().创建实例();
}

// 等价于 属于 关键字，用于判断某个实例是否是本类(或本类的子类)的实例
public boolean 从属于(Object 判断对象) {
return this.isInstance(判断对象);
}

// 判断参数一所表示的类是否是本类(或本类的子类)
public boolean 类从属于(Java类 判断类) {
return this.isAssignableFrom(判断类);
}

// 获取本类的类加载器
public Java类加载器 取类加载器() {
return this.getClassLoader();
}

// 获取本类以及从父类继承来的公开内部类
public Java类[] 取所有公开内部类() {
return this.getClasses();
}

// 取本类所有内部类
public Java类[] 取所有内部类() {
return this.getDeclaredClasses();
}

// 获取本类以及从父类继承来的公开字段
public Java字段[] 取所有公开字段() {
return this.getFields();
}

// 从字段名获取公开字段
public Java字段 取公开字段(String 字段名) {
try {
return this.getField(字段名);
} catch(NoSuchFieldException e) {
throw new RuntimeException("找不到该字段：" + 字段名);
}
}

// 获取本类所有字段
public Java字段[] 取所有字段() {
return this.getDeclaredFields();
}

// 从字段名获取字段
public Java字段 取字段(String 字段名) {
try {
return this.getDeclaredField(字段名);
} catch(NoSuchFieldException e) {
throw new RuntimeException("找不到该字段：" + 字段名);
}
}

// 获取本类的公开构造方法
public Java构造方法[] 取所有公开构造方法() {
return this.getConstructors();
}

// 从参数类型获取公开构造方法
public Java构造方法 取公开构造方法(Java类[] 参数类型) {
if(参数类型 == null) {
参数类型 = new Class[0];
}
try {
return this.getConstructor(参数类型);
} catch(NoSuchMethodException e) {
throw new RuntimeException("找不到该构造方法");
}
}

// 获取本类所有构造方法
public Java构造方法[] 取所有构造方法() {
return this.getDeclaredConstructors();
}

// 从参数类型获取构造方法
public Java构造方法 取构造方法(Java类[] 参数类型) {
if(参数类型 == null) {
参数类型 = new Class[0];
}
try {
return this.getDeclaredConstructor(参数类型);
} catch(NoSuchMethodException e) {
throw new RuntimeException("找不到该构造方法");
}
}

// 获取本类以及从父类继承来的公开方法
public Java方法[] 取所有公开方法() {
return this.getMethods();
}

// 从方法名以及参数类型获取公开方法
public Java方法 取公开方法(String 方法名, Java类[] 参数类型) {
if(参数类型 == null) {
参数类型 = new Class[0];
}
try {
return this.getMethod(方法名,参数类型);
} catch(NoSuchMethodException e) {
throw new RuntimeException("找不到该方法");
}
}

// 获取本类所有方法
public Java方法[] 取所有方法() {
return this.getDeclaredMethods();
}

// 从参数类型获取方法
public Java方法 取方法(String 方法名, Java类[] 参数类型) {
if(参数类型 == null) {
参数类型 = new Class[0];
}
try {
return this.getDeclaredMethod(方法名,参数类型);
} catch(NoSuchMethodException e) {
throw new RuntimeException("找不到该方法");
}
}

// 判断本类上是否标注了某个注解
public boolean 存在注解(Java类 注解类) {
return this.isAnnotationPresent(注解类);
}

// 从注解类获取标注在本类上的注解
public Java注解 取注解(Java类 注解类) {
return this.getAnnotation(注解类);
}

// 从注解类获取标注在本类上的注解
public Java注解[] 取注解数组(Java类 注解类) {
return this.getAnnotationsByType(注解类);
}

// 取标注在本类上的所有注解
public Java注解[] 取所有注解() {
return this.getAnnotations();
}

}