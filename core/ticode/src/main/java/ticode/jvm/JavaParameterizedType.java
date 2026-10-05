package ticode.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.android.AndroidEnv;

public class JavaParameterizedType extends java.lang.reflect.ParameterizedType {

public boolean 等于_op(JavaParameterizedType 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(JavaParameterizedType 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 取实际类型数组
public JavaType[] 实际类型参数() {
return this.getActualTypeArguments();
}

// 取本类型的原始类型
public JavaType 类型() {
return this.getRawType();
}

}