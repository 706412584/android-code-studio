package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import dalvik.system.DexFile;

import ticode.zh.android.安卓环境;

public class Java类加载器 extends java.lang.ClassLoader {

// 根据类名从本类加载器中加载获取Java类
public Java类 加载类(String 完整类名) {
try {
return this.loadClass(完整类名);
} catch(ClassNotFoundException e) {
throw new RuntimeException("找不到类：" + 完整类名);
}
}

public 输入流 取资源输入流(String 资源名) {
return this.getResourceAsStream(资源名);
}

public Java类加载器 取父加载器() {
return this.getParent();
}

public static Java类加载器 取系统类加载器() {
return java.lang.ClassLoader.getSystemClassLoader();
}

}