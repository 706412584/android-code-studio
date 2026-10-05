package ticode.zh.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.正则表达式;

public class 异常 extends Throwable {
public String 取异常信息() {
return this.getMessage();
}

public 异常 取造成原因() {
return this.getCause();
}

public void 输出堆栈信息() {
this.printStackTrace();
}

public String 堆栈信息转文本() {
StringWriter sw = new StringWriter();
PrintWriter pw = new PrintWriter(sw);
this.printStackTrace(pw);
return sw.toString();
}

}