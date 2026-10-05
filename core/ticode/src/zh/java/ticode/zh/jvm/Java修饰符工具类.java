package ticode.zh.jvm;

import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.zh.android.安卓环境;

public class Java修饰符工具类 extends dalvik.system.DexClassLoader {

public static final int ABSTRACT = 1024;

public static final int FINAL = 16;

public static final int INTERFACE = 512;

public static final int NATIVE = 256;

public static final int PRIVATE = 2;

public static final int PROTECTED = 4;

public static final int PUBLIC = 1;

public static final int STATIC = 8;

public static final int STRICT = 2048;

public static final int SYNCHRONIZED = 32;

public static final int TRANSIENT = 128;

public static final int VOLATILE = 64;

public static boolean 包含public(int 修饰符) {
return Modifier.isPublic(修饰符);
}

public static boolean 包含private(int 修饰符) {
return Modifier.isPrivate(修饰符);
}

public static boolean 包含protected(int 修饰符) {
return Modifier.isProtected(修饰符);
}

public static boolean 包含static(int 修饰符) {
return Modifier.isStatic(修饰符);
}

public static boolean 包含final(int 修饰符) {
return Modifier.isFinal(修饰符);
}

public static boolean 包含synchronized(int 修饰符) {
return Modifier.isSynchronized(修饰符);
}

public static boolean 包含volatile(int 修饰符) {
return Modifier.isVolatile(修饰符);
}

public static boolean 包含transient(int 修饰符) {
return Modifier.isTransient(修饰符);
}

public static boolean 包含native(int 修饰符) {
return Modifier.isNative(修饰符);
}

public static boolean 包含interface(int 修饰符) {
return Modifier.isInterface(修饰符);
}

public static boolean 包含abstract(int 修饰符) {
return Modifier.isAbstract(修饰符);
}

public static boolean 包含strict(int 修饰符) {
return Modifier.isStrict(修饰符);
}

public static String 到文本(int 修饰符) {
return Modifier.toString(修饰符);
}

}