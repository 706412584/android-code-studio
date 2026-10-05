package ticode.jvm;

import java.lang.reflect.Modifier;
import dalvik.system.DexFile;
import java.util.Enumeration;

import ticode.android.AndroidEnv;

public class JavaAnnotation implements java.lang.annotation.Annotation {
public JavaClass 取类型() {
return this.annotationType();
}
}