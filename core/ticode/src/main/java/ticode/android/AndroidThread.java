package ticode.android;

import android.os.Message;

public class AndroidThread extends Thread {
public static AndroidThread 取当前线程() {
return Thread.currentThread();
}

public static long 取当前线程ID() {
return Thread.currentThread().getId();
}

public static String 取当前线程名称() {
return Thread.currentThread().getName();
}




public static Object 转交其它线程执行() {
return Thread.yield();
}

//使线程休眠一段时间
public static Object 延时(long 时长) {
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
public Object 启动() {
return this.start();
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