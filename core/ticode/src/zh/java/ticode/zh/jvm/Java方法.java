package ticode.zh.jvm;


public class Java方法 {

public boolean 等于_op(java.lang.reflect.Method 另一个方法) {return false; }

public boolean 不等于_op(java.lang.reflect.Method 另一个方法) {return false; }

// 返回本方法所在的类
public java.lang.Class 所在类() {return null; }

// 返回本方法的名称
public String 名称() {return null; }

// 返回本方法的修饰符
public int 修饰符() {return 0; }

// 返回本方法是否为Java编译器自动生成的方法
public boolean 是合成方法() {return false; }

// 返回本方法是否有可变参数
public boolean 有可变参数() {return false; }

// 返回本方法是否开放访问权限
public boolean 可访问() {return false; }

// 设置本方法是否开放访问权限
public void 可访问(boolean 可访问) {}

// 取本方法声明的泛型参数
public java.lang.reflect.TypeVariable[] 泛型参数() {return null; }

// 取本方法的所有参数的类型
public java.lang.Class[] 参数类型() {return null; }

// 返回本方法的参数数量
public int 参数数量() {return 0; }

// 返回带有泛型的参数类型
public java.lang.reflect.Type[] 带泛型参数类型() {return null; }

// 返回本方法的所有参数
public java.lang.reflect.Parameter[] 参数() {return null; }

// 返回本方法声明可能抛出的异常
public java.lang.Class[] 异常类型() {return null; }

// 返回本方法声明可能抛出的异常
public java.lang.reflect.Type[] 带泛型异常类型() {return null; }

// 返回本方法的所有参数的注解
public java.lang.annotation.Annotation[][] 参数注解() {return null; }

// 判断本方法上是否标注了某个注解
public boolean 存在注解(java.lang.Class 注解类) {return false; }

// 从注解类获取标注在本方法上的注解
public java.lang.annotation.Annotation 取注解(java.lang.Class 注解类) {return null; }

// 从注解类获取标注在本方法上的注解
public java.lang.annotation.Annotation[] 取注解数组(java.lang.Class 注解类) {return null; }

// 取标注在本方法上的所有注解
public java.lang.annotation.Annotation[] 取所有注解() {return null; }

// 返回本方法是否为Java编译器生成的桥接方法
public boolean 是桥接方法() {return false; }

// 返回本方法是否为接口的默认实现方法
public boolean 是默认实现方法() {return false; }

// 如果本方法所在类是注解类，本方法是注解属性，则调用此方法获取本注解属性默认值
public Object 注解属性默认值() {return null; }

// 返回本方法的返回类型
public java.lang.Class 返回类型() {return null; }

// 返回本方法的返回类型
public java.lang.reflect.Type 带泛型返回类型() {return null; }

// 执行本方法
public Object 执行(Object 所在类实例, Object[] 参数) {return null; }

}