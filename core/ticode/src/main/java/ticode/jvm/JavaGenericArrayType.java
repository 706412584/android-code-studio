package ticode.jvm;


public class JavaGenericArrayType extends JavaType {

public boolean 等于_op(java.lang.reflect.GenericArrayType 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(java.lang.reflect.GenericArrayType 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 获取数组 [] 前面的类型
public java.lang.reflect.Type 数组节点() {
return this.getGenericComponentType();
}

public String 类型名称() {
return this.getTypeName();
}
}