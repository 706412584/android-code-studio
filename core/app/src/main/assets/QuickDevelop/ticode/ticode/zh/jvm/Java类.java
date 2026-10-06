package ticode.zh.jvm;


public class Java类 {

public boolean 等于_op(java.lang.Class 另一个类) {return false; }

public boolean 不等于_op(java.lang.Class 另一个类) {return false; }

public java.lang.Class 赋值_op(String 完整类名) {return null; }

public static java.lang.Class 取指定Java类(Object 类型) {
return Object.class;
}

// 返回本类类名
public String 类名() {return null; }















public String 完整类名() {return null; }

// 返回本类的规范名称
public String 规范名称() {return null; }





public String 描述文本() {return null; }

// 返回本类是否是一个Java接口
public boolean 是接口() {return false; }

// 返回本类是否是一个Java注解
public boolean 是注解() {return false; }

// 返回本类是否是枚举类
public boolean 是枚举() {return false; }

// 如果本类是枚举类，则返回本类所有枚举成员，否则返回 空
public Object[] 枚举成员() {return null; }

// 返回本类对象所代表是否为数组类型
public boolean 是数组() {return false; }

// 返回本类对象所代表是否为Java基本数据类型
public boolean 是基本类型() {return false; }

// 返回本类是否是由Java编译器自动生成的类
public boolean 是合成类() {return false; }

// 返回本类是否是匿名类
public boolean 是匿名类() {return false; }

// 返回本类是否是方法中定义的局部类
public boolean 是局部类() {return false; }

// 返回本类是否是内部类
public boolean 是内部类() {return false; }

// 取本类的修饰符
public int 修饰符() {return 0; }

// 取本类声明的泛型参数
public java.lang.reflect.TypeVariable[] 泛型参数() {return null; }

// 返回本类的父类，如果本类是 Object类 接口 基本类型 void 则返回空，如果本类代表数组类型，则返回Object类
public java.lang.Class 父类() {return null; }

// 获取带有泛型的父类
public java.lang.reflect.Type 带泛型父类() {return null; }

// 获取本类实现的接口，如果本类是接口，则返回本接口扩展的接口
public java.lang.Class[] 实现接口() {return null; }

// 获取带有泛型的接口
public java.lang.reflect.Type[] 带泛型实现接口() {return null; }

// 如果本类对象代表数组类，则返回数组的节点类，否则返回空
public java.lang.Class 数组节点类() {return null; }

// 如果本类是别的类里的成员类，则返回本类的外部类，否则返回 空
public java.lang.Class 外部类() {return null; }

// 使用公开无参构造方法创建一个本类的实例
public Object 创建实例() {return null; }

// 等价于 属于 关键字，用于判断某个实例是否是本类(或本类的子类)的实例
public boolean 从属于(Object 判断对象) {return false; }

// 判断参数一所表示的类是否是本类(或本类的子类)
public boolean 类从属于(java.lang.Class 判断类) {return false; }

// 获取本类的类加载器
public Java类加载器 取类加载器() {return null; }

// 获取本类以及从父类继承来的公开内部类
public java.lang.Class[] 取所有公开内部类() {return null; }

// 取本类所有内部类
public java.lang.Class[] 取所有内部类() {return null; }

// 获取本类以及从父类继承来的公开字段
public java.lang.reflect.Field[] 取所有公开字段() {return null; }

// 从字段名获取公开字段
public java.lang.reflect.Field 取公开字段(String 字段名) {return null; }

// 获取本类所有字段
public java.lang.reflect.Field[] 取所有字段() {return null; }

// 从字段名获取字段
public java.lang.reflect.Field 取字段(String 字段名) {return null; }

// 获取本类的公开构造方法
public java.lang.reflect.Constructor[] 取所有公开构造方法() {return null; }

// 从参数类型获取公开构造方法
public java.lang.reflect.Constructor 取公开构造方法(java.lang.Class[] 参数类型) {return null; }

// 获取本类所有构造方法
public java.lang.reflect.Constructor[] 取所有构造方法() {return null; }

// 从参数类型获取构造方法
public java.lang.reflect.Constructor 取构造方法(java.lang.Class[] 参数类型) {return null; }

// 获取本类以及从父类继承来的公开方法
public java.lang.reflect.Method[] 取所有公开方法() {return null; }

// 从方法名以及参数类型获取公开方法
public java.lang.reflect.Method 取公开方法(String 方法名, java.lang.Class[] 参数类型) {return null; }

// 获取本类所有方法
public java.lang.reflect.Method[] 取所有方法() {return null; }

// 从参数类型获取方法
public java.lang.reflect.Method 取方法(String 方法名, java.lang.Class[] 参数类型) {return null; }

// 判断本类上是否标注了某个注解
public boolean 存在注解(java.lang.Class 注解类) {return false; }

// 从注解类获取标注在本类上的注解
public java.lang.annotation.Annotation 取注解(java.lang.Class 注解类) {return null; }

// 从注解类获取标注在本类上的注解
public java.lang.annotation.Annotation[] 取注解数组(java.lang.Class 注解类) {return null; }

// 取标注在本类上的所有注解
public java.lang.annotation.Annotation[] 取所有注解() {return null; }

}