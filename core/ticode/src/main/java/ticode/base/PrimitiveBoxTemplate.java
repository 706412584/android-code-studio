package ticode.base;

import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.jvm.JRegex;
import ticode.jvm.JavaClass;

public class PrimitiveBoxTemplate<T1> {



public String 到文本() {
return String.valueOf(this);
}




public T1 到基本类型() {
return (T1) this;
}
}