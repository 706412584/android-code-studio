package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class Java泛型变量 extends java.lang.reflect.TypeVariable {

public boolean 等于_op(Java泛型变量 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(Java泛型变量 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 获取本泛型参数的名称
public String 名称() {
return this.getName();
}




public Java类型[] 限制类型() {
return this.getBounds();
}

}