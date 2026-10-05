package ticode.zh.base;

import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.正则表达式;

public class 异步调度类 extends java.lang.StringBuilder {
private static final ExecutorService service = Executors.newCachedThreadPool();
private static final Handler handler = new Handler(Looper.getMainLooper());

public static void launch(Runnable callback) {
service.submit(callback);
}

public static <T> void launchAll(List<? extends Callable<T>> callbacks) {
try {
service.invokeAll(callbacks);
} catch (InterruptedException e) {
e.printStackTrace();
}
}

public static void back(Runnable runnable) {
handler.post(runnable);
}

public static void shutdown() {
service.shutdown();
}
}