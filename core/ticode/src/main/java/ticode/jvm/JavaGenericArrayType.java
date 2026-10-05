package ticode.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.android.AndroidEnv;

public class JavaGenericArrayType extends java.lang.reflect.GenericArrayType {

public boolean 等于_op(JavaGenericArrayType 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(JavaGenericArrayType 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 获取数组 [] 前面的类型
public JavaType 数组节点() {
return this.getGenericComponentType();
}

}