package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class dex文件 extends dalvik.system.DexFile {
public dex文件 赋值_op(String 文件路径) {
try{
return new DexFile(文件路径);
} catch (Exception e) {}
return null;
}

public static dex文件 实例化(String 文件路径) {
try{
return new DexFile(文件路径);
} catch (Exception e) {}
return null;
}

public 类名枚举器 取本文件所有类名() {
return this.entries();
}

public void 关闭() {
try {
this.close();
} catch (Exception e) { }
}
}