package ticode.jvm;

import java.lang.reflect.Modifier;
import dalvik.system.DexFile;

import ticode.android.AndroidEnv;

public class ClassNameEnumerator implements java.util.Enumeration {
public boolean 还有下一个() {
return this.hasMoreElements();
}

public Object 取下一个对象() {
return this.nextElement();
}
}