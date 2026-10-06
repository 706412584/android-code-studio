package ticode.zh.jvm;


public abstract class Java类加载器 extends java.lang.ClassLoader {

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
return (Java类加载器)this.getParent();
}

public static Java类加载器 取系统类加载器() {
return (Java类加载器)java.lang.ClassLoader.getSystemClassLoader();
}

}