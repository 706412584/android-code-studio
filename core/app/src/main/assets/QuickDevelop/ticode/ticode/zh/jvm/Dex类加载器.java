package ticode.zh.jvm;


public class Dex类加载器 extends dalvik.system.DexClassLoader {
public Dex类加载器(String Dex文件路径, String 缓存路径, String so库搜索路径, java.lang.ClassLoader 父加载器) { super(Dex文件路径, 缓存路径, so库搜索路径, 父加载器); }
public Dex类加载器() { super(null, null, null, null); }

public boolean 等于_op(Dex类加载器 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(Dex类加载器 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public static Dex类加载器 创建实例(String Dex文件路径, String 缓存路径, String so库搜索路径, Java类加载器 父加载器) {
return new Dex类加载器(Dex文件路径,缓存路径,so库搜索路径,父加载器);
}

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
// 系统类加载器是框架的 PathClassLoader，不是 Java类加载器 子类，强转必 ClassCastException。
java.lang.ClassLoader 原 = java.lang.ClassLoader.getSystemClassLoader();
return 原 instanceof Java类加载器 ? (Java类加载器)原 : null;
}
}