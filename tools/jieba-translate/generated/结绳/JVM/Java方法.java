package 结绳.JVM;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

public class Java方法 {

public boolean 等于_op(Java方法 另一个方法) {
if (this == null) {
return 另一个方法 == null;
}
return this.equals(另一个方法);
}

public boolean 不等于_op(Java方法 另一个方法) {
if (this == null) {
return 另一个方法 != null;
}
return !this.equals(另一个方法);
}

// 返回本方法所在的类
public Java类 所在类() {
return this.getDeclaringClass();
}

// 返回本方法的名称
public String 名称() {
return this.getName();
}

// 返回本方法的修饰符
public int 修饰符() {
return this.getModifiers();
}

// 返回本方法是否为Java编译器自动生成的方法
public boolean 是合成方法() {
return this.isSynthetic();
}

// 返回本方法是否有可变参数
public boolean 有可变参数() {
return this.isVarArgs();
}

// 返回本方法是否开放访问权限
public boolean 可访问() {
return this.isAccessible();
}

// 设置本方法是否开放访问权限
public void 可访问(boolean 可访问) {
if(可访问) {
if ((!Modifier.isPublic(this.getModifiers()) || !Modifier.isPublic(this.getDeclaringClass().getModifiers()))
&& !this.isAccessible()) {
this.setAccessible(可访问);
}
}
}

// 取本方法声明的泛型参数
public Java泛型变量[] 泛型参数() {
return this.getTypeParameters();
}

// 取本方法的所有参数的类型
public Java类[] 参数类型() {
return this.getParameterTypes();
}

// 返回本方法的参数数量
public int 参数数量() {
return this.getParameterCount();
}

// 返回带有泛型的参数类型
public Java类型[] 带泛型参数类型() {
return this.getGenericParameterTypes();
}

// 返回本方法的所有参数
public Java方法参数[] 参数() {
return this.getParameters();
}

// 返回本方法声明可能抛出的异常
public Java类[] 异常类型() {
return this.getExceptionTypes();
}

// 返回本方法声明可能抛出的异常
public Java类型[] 带泛型异常类型() {
return this.getGenericExceptionTypes();
}

// 返回本方法的所有参数的注解
public Java注解[][] 参数注解() {
return this.getParameterAnnotations();
}

// 判断本方法上是否标注了某个注解
public boolean 存在注解(Java类 注解类) {
return this.isAnnotationPresent(注解类);
}

// 从注解类获取标注在本方法上的注解
public Java注解 取注解(Java类 注解类) {
return this.getAnnotation(注解类);
}

// 从注解类获取标注在本方法上的注解
public Java注解[] 取注解数组(Java类 注解类) {
return this.getAnnotationsByType(注解类);
}

// 取标注在本方法上的所有注解
public Java注解[] 取所有注解() {
return this.getAnnotations();
}

// 返回本方法是否为Java编译器生成的桥接方法
public boolean 是桥接方法() {
return this.isBridge();
}

// 返回本方法是否为接口的默认实现方法
public boolean 是默认实现方法() {
return this.isDefault();
}

// 如果本方法所在类是注解类，本方法是注解属性，则调用此方法获取本注解属性默认值
public Object 注解属性默认值() {
return this.getDefaultValue();
}

// 返回本方法的返回类型
public Java类 返回类型() {
return this.getReturnType();
}

// 返回本方法的返回类型
public Java类型 带泛型返回类型() {
return this.getGenericReturnType();
}

// 执行本方法
public Object 执行(Object 所在类实例, Object[] 参数) {
try {
return this.invoke(所在类实例,参数);
} catch(IllegalAccessException e) {
throw new RuntimeException("没有开放访问权限，无法访问");
} catch(IllegalArgumentException e) {
throw new RuntimeException("参数错误：" + e.getMessage());
} catch(java.lang.reflect.InvocationTargetException e) {
throw new RuntimeException("执行方法失败：" + e.getMessage());
} catch(NullPointerException e) {
throw new RuntimeException("不是静态字段，类实例不能为空");
}
}

}

