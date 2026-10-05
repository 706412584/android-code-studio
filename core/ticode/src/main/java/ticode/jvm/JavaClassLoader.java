package ticode.jvm;

import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.android.AndroidEnv;

public class JavaClassLoader extends java.lang.ClassLoader {

// 根据类名从本类加载器中加载获取Java类
public JavaClass 加载类(String 完整类名) {
try {
return this.loadClass(完整类名);
} catch(ClassNotFoundException e) {
throw new RuntimeException("找不到类：" + 完整类名);
}
}

public JInputStream 取资源输入流(String 资源名) {
return this.getResourceAsStream(资源名);
}

public JavaClassLoader 取父加载器() {
return this.getParent();
}

public static JavaClassLoader 取系统类加载器() {
return java.lang.ClassLoader.getSystemClassLoader();
}

}