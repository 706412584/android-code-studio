package ticode.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.android.AndroidEnv;

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

}