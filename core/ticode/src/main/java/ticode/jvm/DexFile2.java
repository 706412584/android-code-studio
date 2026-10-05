package ticode.jvm;

import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.android.AndroidEnv;

public class DexFile2 extends dalvik.system.DexFile {
public DexFile2 赋值_op(String 文件路径) {
try{
return new DexFile(文件路径);
} catch (Exception e) {}
return null;
}

public static DexFile2 实例化(String 文件路径) {
try{
return new DexFile(文件路径);
} catch (Exception e) {}
return null;
}

public java.util.Enumeration 取本文件所有类名() {
return this.entries();
}

public void 关闭() {
try {
this.close();
} catch (Exception e) { }
}
}