package ticode.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.android.AndroidEnv;

public class JavaWildcardType extends java.lang.reflect.WildcardType {

public boolean 等于_op(JavaWildcardType 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(JavaWildcardType 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 获取使用 extends 指定的上边界限制类型
public JavaType[] 上边界限制类型() {
return this.getUpperBounds();
}

// 获取使用 super 指定的下边界限制类型
public JavaType[] 下边界限制类型() {
return this.getLowerBounds();
}

}