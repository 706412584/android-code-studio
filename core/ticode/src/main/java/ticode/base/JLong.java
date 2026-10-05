package ticode.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.jvm.JRegex;
import ticode.jvm.JavaClass;

public class JLong extends PrimitiveTemplate<LongBox> {

public String 到十六进制() {
return Long.toHexString(this);
}

public String 到八进制() {
return Long.toOctalString(this);
}

public String 到二进制() {
return Long.toBinaryString(this);
}

}