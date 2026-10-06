package ticode.zh.android;


public class 安卓线程 extends Thread {
// 当前线程是框架创建的普通 Thread，不可能是 安卓线程 子类，强转必 ClassCastException。
// 返回原生 Thread（调用方需要 ID/名称时用 Thread 自身方法）。
public static Thread 取当前线程() {
return Thread.currentThread();
}

public static long 取当前线程ID() {
return Thread.currentThread().getId();
}

public static String 取当前线程名称() {
return Thread.currentThread().getName();
}




public static void 转交其它线程执行() {
Thread.yield();
}

//使线程休眠一段时间
public static void 延时(long 时长) {
try { Thread.sleep(时长); } catch (Exception e) { e.printStackTrace(); }
}

public long ID() {
return this.getId();
}

public String 名称() {
return this.getName();
}

public void 名称(String 名称) {
this.setName(名称);
}




public int 优先级() {
return this.getPriority();
}




public void 优先级(int 优先级) {
this.setPriority(优先级);
}

public boolean 正在执行() {
return this.isAlive();
}

//启动线程
public void 启动() {
this.start();
}

public void 中断() {
this.interrupt();
}

public void 等待执行完毕() {
try {
this.join();
} catch (Exception e) { }
}
}