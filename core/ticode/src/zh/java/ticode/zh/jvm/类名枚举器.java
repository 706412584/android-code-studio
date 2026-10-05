package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import dalvik.system.DexFile;

import ticode.zh.android.安卓环境;

public class 类名枚举器 implements java.util.Enumeration {
public boolean 还有下一个() {
return this.hasMoreElements();
}

public Object 取下一个对象() {
return this.nextElement();
}
}