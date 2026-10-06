package ticode.base;

import java.util.List;
import android.os.*;
import java.util.concurrent.*;

public class AsyncDispatcher {
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