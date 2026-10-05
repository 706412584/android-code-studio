package ticode.base;

import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.jvm.JRegex;
import ticode.jvm.JavaClass;

public class JInt extends PrimitiveTemplate<IntegerBox> {
public byte 到字节() {
return (byte)this;
}

public String 到十六进制() {
return Integer.toHexString(this);
}

public String 到八进制() {
return Integer.toOctalString(this);
}

public String 到二进制() {
return Integer.toBinaryString(this);
}
}