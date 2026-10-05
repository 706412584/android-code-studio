package ticode.zh.base;

import android.os.*;
import java.util.concurrent.*;

public abstract class 整数 extends 基本类型模板类<整数类> {
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