package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class Java参数化类型 extends java.lang.reflect.ParameterizedType {

public boolean 等于_op(Java参数化类型 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(Java参数化类型 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 取实际类型数组
public Java类型[] 实际类型参数() {
return this.getActualTypeArguments();
}

// 取本类型的原始类型
public Java类型 类型() {
return this.getRawType();
}

}