package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import dalvik.system.DexFile;

import ticode.zh.android.安卓环境;

public class Java参数化类型 implements Java类型, java.lang.reflect.ParameterizedType {

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

}