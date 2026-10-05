package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class Java字段 extends java.lang.reflect.Field {

String 抛出异常_无法访问 = "没有开放访问权限，无法访问";
String 抛出异常_实例错误 = "错误的类实例";
String 抛出异常_实例为空 = "不是静态字段，类实例不能为空";

public boolean 等于_op(Java字段 另一个字段) {
if (this == null) {
return 另一个字段 == null;
}
return this.equals(另一个字段);
}

public boolean 不等于_op(Java字段 另一个字段) {
if (this == null) {
return 另一个字段 != null;
}
return !this.equals(另一个字段);
}

// 返回本字段所在的类
public Java类 所在类() {
return this.getDeclaringClass();
}

// 返回本字段的名称
public String 名称() {
return this.getName();
}

// 返回本字段的修饰符
public int 修饰符() {
return this.getModifiers();
}

// 返回本字段是否是枚举成员
public boolean 是枚举成员() {
return this.isEnumConstant();
}

// 返回本字段是否是由Java编译器自动生成的字段
public boolean 是合成字段() {
return this.isSynthetic();
}

// 取本字段的类型
public Java类 类型() {
return this.getType();
}

// 取带有泛型的类型
public java.lang.reflect.Type 带泛型类型() {
return this.getGenericType();
}

// 返回本字段是否开放访问权限
public boolean 可访问() {
return this.isAccessible();
}

// 设置本字段是否开放访问权限
public void 可访问(boolean 可访问) {
if(可访问) {
if ((!Modifier.isPublic(this.getModifiers()) || !Modifier.isPublic(this.getDeclaringClass().getModifiers()) ||
Modifier.isFinal(this.getModifiers())) && !this.isAccessible()) {
this.setAccessible(可访问);
}
}
}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public Object 取对象值(Object 所在类实例) {
try {
return this.get(所在类实例);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public boolean 取逻辑值(Object 所在类实例) {
try {
return this.getBoolean(所在类实例);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public byte 取字节值(Object 所在类实例) {
try {
return this.getByte(所在类实例);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public char 取字符值(Object 所在类实例) {
try {
return this.getChar(所在类实例);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public int 取整数值(Object 所在类实例) {
try {
return this.getInt(所在类实例);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public long 取长整数值(Object 所在类实例) {
try {
return this.getLong(所在类实例);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public float 取单精度小数值(Object 所在类实例) {
try {
return this.getFloat(所在类实例);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public double 取小数值(Object 所在类实例) {
try {
return this.getDouble(所在类实例);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 设置本字段的值
public void 置对象值(Object 所在类实例, Object 值) {
try {
this.set(所在类实例, 值);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 设置本字段的值
public void 置逻辑值(Object 所在类实例, boolean 值) {
try {
this.setBoolean(所在类实例, 值);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 设置本字段的值
public void 置字节值(Object 所在类实例, byte 值) {
try {
this.setByte(所在类实例, 值);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 设置本字段的值
public void 置字符值(Object 所在类实例, char 值) {
try {
this.setChar(所在类实例, 值);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 设置本字段的值
public void 置整数值(Object 所在类实例, int 值) {
try {
this.setInt(所在类实例, 值);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 设置本字段的值
public void 置长整数值(Object 所在类实例, long 值) {
try {
this.setLong(所在类实例, 值);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 设置本字段的值
public void 置单精度小数值(Object 所在类实例, float 值) {
try {
this.setFloat(所在类实例, 值);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 设置本字段的值
public void 置小数值(Object 所在类实例, double 值) {
try {
this.setDouble(所在类实例, 值);
} catch(IllegalAccessException e) {
throw new RuntimeException(抛出异常_无法访问);
} catch(IllegalArgumentException e) {
throw new RuntimeException(抛出异常_实例错误);
} catch(NullPointerException e) {
throw new RuntimeException(抛出异常_实例为空);
}
}

// 判断本字段上是否标注了某个注解
public boolean 存在注解(Java类 注解类) {
return this.isAnnotationPresent(注解类);
}

// 从注解类获取标注在本字段上的注解
public java.lang.annotation.Annotation 取注解(Java类 注解类) {
return this.getAnnotation(注解类);
}

// 从注解类获取标注在本字段上的注解
public java.lang.annotation.Annotation[] 取注解数组(Java类 注解类) {
return this.getAnnotationsByType(注解类);
}

// 取标注在本字段上的所有注解
public java.lang.annotation.Annotation[] 取所有注解() {
return this.getAnnotations();
}

}