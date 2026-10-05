package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class Dex类加载器 extends dalvik.system.DexClassLoader {

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
return new dalvik.system.DexClassLoader(Dex文件路径,缓存路径,so库搜索路径,父加载器);
}

}