package ticode.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.jvm.JRegex;
import ticode.jvm.JavaClass;

public class JException extends Throwable {
public String 取异常信息() {
return this.getMessage();
}

public JException 取造成原因() {
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