package ticode.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.jvm.JRegex;
import ticode.jvm.JavaClass;

public class JFloat extends PrimitiveTemplate<FloatBox> {
public int 到整数() {
return (int) this;
}
}