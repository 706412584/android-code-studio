package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class Java泛型数组类型 implements Java类型, java.lang.reflect.GenericArrayType {

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

}