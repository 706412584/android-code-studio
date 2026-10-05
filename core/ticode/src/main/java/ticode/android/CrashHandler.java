package ticode.android;

import android.os.Looper;
import android.content.Intent;
import java.io.File;
import android.os.Build;

public class CrashHandler {
private Thread.UncaughtExceptionHandler mDefaultHandler;
private static CrashHandler INSTANCE = new CrashHandler();
private android.content.Context mContext;
private String path;

private CrashHandler() {
}

@Override
public void uncaughtException(Thread thread, Throwable ex) {
if (!handleException(ex) && mDefaultHandler != null) {
mDefaultHandler.uncaughtException(thread, ex);
}
}

private boolean handleException(Throwable ex) {
if (ex == null) {
return false;
}
new Thread() {
@Override
public void run() {
Looper.prepare();
Looper.loop();
}
}.start();
sendError(ex);
return true;
}

private void sendError(Throwable ex) {
StringBuilder sb = new StringBuilder();
Throwable cause = ex.getCause();
Intent intent = new Intent();
intent.setPackage("com.tiecode.develop");
intent.setAction("com.tiecode.LOGCAT");
intent.putExtra("crash", ex);
mContext.sendBroadcast(intent);
sb.append("Caused by: ").append(cause.toString());
StackTraceElement[] elements = cause.getStackTrace();
for (StackTraceElement element : elements) {
intent.putExtra("msg", "[运行异常]\tat " + element.toString());
mContext.sendBroadcast(intent);
sb.append("\n").append("\tat ").append(element.toString());
}
if (path != null && !"".equals(path)) {
try {
//jiesheng.FileUtils.write(new File(path), sb.toString());
} catch (Exception e) {
e.printStackTrace();
}
}
}

private void init(android.content.Context context, String path) {
this.mContext = context;
this.path = path;
mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
Thread.setDefaultUncaughtExceptionHandler(this);
}

//初始化程序崩溃处理，若不想保存到文件，第二个参数填写空或空字符串
public static void 初始化(AndroidEnv 环境, String 日志保存路径) {
INSTANCE.init(环境, 日志保存路径);
}
}