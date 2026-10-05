package ticode.zh.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.正则表达式;

public class 长整数 extends 基本类型模板类<长整数类> {

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