package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class Java方法参数 extends java.lang.reflect.Parameter {

public boolean 等于_op(Java方法参数 另一个参数) {
if (this == null) {
return 另一个参数 == null;
}
return this.equals(另一个参数);
}

public boolean 不等于_op(Java方法参数 另一个参数) {
if (this == null) {
return 另一个参数 != null;
}
return !this.equals(另一个参数);
}

// 返回本参数是否有名称
public boolean 存在名称() {
return this.isNamePresent();
}

// 返回本参数的修饰符
public int 修饰符() {
return this.getModifiers();
}

// 返回参数名称
public String 名称() {
return this.getName();
}

// 返回本参数是否为隐藏参数
public boolean 是隐藏参数() {
return this.isImplicit();
}

// 返回本参数是否为Java编译器自动生成的参数
public boolean 是合成参数() {
return this.isSynthetic();
}

// 返回本参数是否为可变参数
public boolean 是可变参数() {
return this.isVarArgs();
}

// 返回本参数的类型
public Java类 类型() {
return this.getType();
}

// 返回泛型类型
public java.lang.reflect.Type 带泛型类型() {
return this.getParameterizedType();
}

// 判断本参数上是否标注了某个注解
public boolean 存在注解(Java类 注解类) {
return this.isAnnotationPresent(注解类);
}

// 从注解类获取标注在本参数上的注解
public java.lang.annotation.Annotation 取注解(Java类 注解类) {
return this.getAnnotation(注解类);
}

// 从注解类获取标注在本参数上的注解
public java.lang.annotation.Annotation[] 取注解数组(Java类 注解类) {
return this.getAnnotationsByType(注解类);
}

// 取标注在本参数上的所有注解
public java.lang.annotation.Annotation[] 取所有注解() {
return this.getAnnotations();
}

}