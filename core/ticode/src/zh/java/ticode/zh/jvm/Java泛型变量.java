package ticode.zh.jvm;


public abstract class Java泛型变量 extends Java类型 {

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

}