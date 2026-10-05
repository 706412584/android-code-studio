package ticode.zh.jvm;

import java.util.concurrent.*;

import ticode.zh.base.整数类;

public class 线程池 {
public static ExecutorService cachedThreadPool;
public static ExecutorService fixedThreadPool;

int 线程池大小;

public static Object 提交到缓存线程池运行() {
if (线程池.cachedThreadPool == null || 线程池.cachedThreadPool.isShutdown()) {
线程池.cachedThreadPool = java.util.concurrent.Executors.newCachedThreadPool();
}
线程池.cachedThreadPool.execute(new Runnable() {
@Override
public void run() {
}

public static Object 结束提交到缓存线程池() {
}
});
}

public static void 停止缓存线程池所有任务() {
if (cachedThreadPool != null) {
cachedThreadPool.shutdown();
}
}

public static void 等待缓存线程池执行完毕() {
if (cachedThreadPool != null) {
停止缓存线程池所有任务();
try {
cachedThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
} catch (Exception e) {
}
}
}

public static void 置固定线程池大小(int 大小) {
线程池大小 = ((大小 <= 0) ? (Runtime.getRuntime().availableProcessors() * 2) : 大小);
线程池.fixedThreadPool = Executors.newFixedThreadPool(线程池大小);
}

public static Object 提交到固定线程池运行() {
if (线程池.fixedThreadPool == null || 线程池.fixedThreadPool.isShutdown()) {
线程池.置固定线程池大小(线程池.线程池大小);
}
线程池.fixedThreadPool.execute(new Runnable() {
@Override
public void run() {
}

public static Object 结束提交到固定线程池() {
}
});
}

public static void 停止固定线程池所有任务() {
if (fixedThreadPool != null) {
fixedThreadPool.shutdown();
}
}

public static void 等待固定线程池执行完毕() {
if (fixedThreadPool != null) {
停止固定线程池所有任务();
try {
fixedThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
} catch (Exception e) {
}
}
}
}