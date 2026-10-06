package ticode.zh.jvm;


public class Java类加载器 extends java.lang.ClassLoader {

// 根据类名从本类加载器中加载获取Java类
public java.lang.Class 加载类(String 完整类名) {
try {
return (java.lang.Class)this.loadClass(完整类名);
} catch(ClassNotFoundException e) {
throw new RuntimeException("找不到类：" + 完整类名);
}
}

public java.io.InputStream 取资源输入流(String 资源名) {
return this.getResourceAsStream(资源名);
}

public Java类加载器 取父加载器() {
// 父加载器可能是框架的 PathClassLoader，不一定是 Java类加载器 子类。
java.lang.ClassLoader 原 = this.getParent();
return 原 instanceof Java类加载器 ? (Java类加载器)原 : null;
}

public static Java类加载器 取系统类加载器() {
// 系统类加载器是框架的 PathClassLoader，不是 Java类加载器 子类，强转必 ClassCastException。
java.lang.ClassLoader 原 = java.lang.ClassLoader.getSystemClassLoader();
return 原 instanceof Java类加载器 ? (Java类加载器)原 : null;
}

}