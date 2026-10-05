package 结绳.JVM;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

public class Java泛型数组类型 extends Java类型 {

public boolean 等于_op(Java泛型数组类型 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(Java泛型数组类型 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 获取数组 [] 前面的类型
public Java类型 数组节点() {
return this.getGenericComponentType();
}

}

