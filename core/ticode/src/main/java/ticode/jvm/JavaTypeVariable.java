package ticode.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.android.AndroidEnv;

public class JavaTypeVariable implements JavaType, java.lang.reflect.TypeVariable {

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