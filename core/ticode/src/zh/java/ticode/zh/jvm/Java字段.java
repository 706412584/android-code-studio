package ticode.zh.jvm;


public class Java字段 {

static String 抛出异常_无法访问 = "没有开放访问权限，无法访问";
static String 抛出异常_实例错误 = "错误的类实例";
static String 抛出异常_实例为空 = "不是静态字段，类实例不能为空";

public boolean 等于_op(java.lang.reflect.Field 另一个字段) {return false; }

public boolean 不等于_op(java.lang.reflect.Field 另一个字段) {return false; }

// 返回本字段所在的类
public java.lang.Class 所在类() {return null; }

// 返回本字段的名称
public String 名称() {return null; }

// 返回本字段的修饰符
public int 修饰符() {return 0; }

// 返回本字段是否是枚举成员
public boolean 是枚举成员() {return false; }

// 返回本字段是否是由Java编译器自动生成的字段
public boolean 是合成字段() {return false; }

// 取本字段的类型
public java.lang.Class 类型() {return null; }

// 取带有泛型的类型
public java.lang.reflect.Type 带泛型类型() {return null; }

// 返回本字段是否开放访问权限
public boolean 可访问() {return false; }

// 设置本字段是否开放访问权限
public void 可访问(boolean 可访问) {}

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public Object 取对象值(Object 所在类实例) {return null; }

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public boolean 取逻辑值(Object 所在类实例) {return false; }

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public byte 取字节值(Object 所在类实例) {return 0; }

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public char 取字符值(Object 所在类实例) {return 0; }

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public int 取整数值(Object 所在类实例) {return 0; }

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public long 取长整数值(Object 所在类实例) {return 0L; }

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public float 取单精度小数值(Object 所在类实例) {return 0; }

// 获取本字段的值，如果本字段不是静态字段，请传入所在类实例
public double 取小数值(Object 所在类实例) {return 0; }

// 设置本字段的值
public void 置对象值(Object 所在类实例, Object 值) {}

// 设置本字段的值
public void 置逻辑值(Object 所在类实例, boolean 值) {}

// 设置本字段的值
public void 置字节值(Object 所在类实例, byte 值) {}

// 设置本字段的值
public void 置字符值(Object 所在类实例, char 值) {}

// 设置本字段的值
public void 置整数值(Object 所在类实例, int 值) {}

// 设置本字段的值
public void 置长整数值(Object 所在类实例, long 值) {}

// 设置本字段的值
public void 置单精度小数值(Object 所在类实例, float 值) {}

// 设置本字段的值
public void 置小数值(Object 所在类实例, double 值) {}

// 判断本字段上是否标注了某个注解
public boolean 存在注解(java.lang.Class 注解类) {return false; }

// 从注解类获取标注在本字段上的注解
public java.lang.annotation.Annotation 取注解(java.lang.Class 注解类) {return null; }

// 从注解类获取标注在本字段上的注解
public java.lang.annotation.Annotation[] 取注解数组(java.lang.Class 注解类) {return null; }

// 取标注在本字段上的所有注解
public java.lang.annotation.Annotation[] 取所有注解() {return null; }

}