package ticode.jvm;


public class JavaTypeVariable extends JavaType {

public boolean 等于_op(java.lang.reflect.TypeVariable 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(java.lang.reflect.TypeVariable 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 获取本泛型参数的名称
public String 名称() {
return this.getName();
}




public java.lang.reflect.Type[] 限制类型() {
return this.getBounds();
}

public String 类型名称() {
return this.getTypeName();
}
}