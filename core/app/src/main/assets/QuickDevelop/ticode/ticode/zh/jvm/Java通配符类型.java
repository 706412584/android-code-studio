package ticode.zh.jvm;


public abstract class Java通配符类型 extends Java类型 implements java.lang.reflect.WildcardType {

public boolean 等于_op(java.lang.reflect.WildcardType 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(java.lang.reflect.WildcardType 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 获取使用 extends 指定的上边界限制类型
public java.lang.reflect.Type[] 上边界限制类型() {
return this.getUpperBounds();
}

// 获取使用 super 指定的下边界限制类型
public java.lang.reflect.Type[] 下边界限制类型() {
return this.getLowerBounds();
}

public String 类型名称() {
return this.getTypeName();
}
}