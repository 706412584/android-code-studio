package ticode.zh.jvm;

import dalvik.system.DexFile;

public class dex文件 {
public dalvik.system.DexFile 赋值_op(String 文件路径) {return null; }

public static dalvik.system.DexFile 实例化(String 文件路径) {
try{
return new DexFile(文件路径);
} catch (Exception e) {}
return null;
}

public java.util.Enumeration 取本文件所有类名() {return null; }

public void 关闭() {}
}