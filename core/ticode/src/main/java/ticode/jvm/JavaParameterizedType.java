package ticode.jvm;


public class JavaParameterizedType extends JavaType {

public boolean 等于_op(java.lang.reflect.ParameterizedType 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(java.lang.reflect.ParameterizedType 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 取实际类型数组
public java.lang.reflect.Type[] 实际类型参数() {
return this.getActualTypeArguments();
}

// 取本类型的原始类型
public java.lang.reflect.Type 类型() {
return this.getRawType();
}

public String 类型名称() {
return this.getTypeName();
}
}