package ticode.zh.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.concurrent.*;

public class 异常 extends Throwable {
public String 取异常信息() {
return this.getMessage();
}

public 异常 取造成原因() {
return (异常)this.getCause();
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