package 结绳.JVM;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

public class dex文件 {
public dex文件 赋值_op(String 文件路径) {
try{
return new DexFile(文件路径);
} catch (Exception e) {}
return null;
}

public dex文件 实例化(String 文件路径) {
try{
return new DexFile(文件路径);
} catch (Exception e) {}
return null;
}

public 类名枚举器 取本文件所有类名() {
return this.entries();
}

public void 关闭() {
容错处理();
this.close();
结束容错();
}
}

//注意，每一次获取枚举器，下一个方法的对象会重置为第一个
