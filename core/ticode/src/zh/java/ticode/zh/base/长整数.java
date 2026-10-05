package ticode.zh.base;

import android.os.*;
import java.util.concurrent.*;

public abstract class 长整数 extends 基本类型模板类<长整数类> {

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