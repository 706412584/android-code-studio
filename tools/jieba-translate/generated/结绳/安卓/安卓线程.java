package 结绳.安卓;

import android.os.Message;

public class 安卓线程 {
public 安卓线程 取当前线程() {
return Thread.currentThread();
}

public long 取当前线程ID() {
return Thread.currentThread().getId();
}

public String 取当前线程名称() {
return Thread.currentThread().getName();
}




public void 转交其它线程执行() {
return Thread.yield();
}

//使线程休眠一段时间
public void 延时(long 时长) {
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
return this.start();
}

public void 中断() {
this.interrupt();
}

public void 等待执行完毕() {
容错处理();
this.join();
结束容错();
}
}




