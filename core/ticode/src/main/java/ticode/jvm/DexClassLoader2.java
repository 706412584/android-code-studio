package ticode.jvm;


public class DexClassLoader2 extends dalvik.system.DexClassLoader {

public boolean 等于_op(DexClassLoader2 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(DexClassLoader2 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public static DexClassLoader2 创建实例(String Dex文件路径, String 缓存路径, String so库搜索路径, JavaClassLoader 父加载器) {
return new dalvik.system.DexClassLoader(Dex文件路径,缓存路径,so库搜索路径,父加载器);
}

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