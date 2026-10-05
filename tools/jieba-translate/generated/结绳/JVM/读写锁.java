package 结绳.JVM;

import java.util.concurrent.*;

public class 读写锁 {
public void 获取读锁() {
this.readLock().lock();
}

public void 释放读锁() {
this.readLock().unlock();
}

public void 获取写锁() {
this.writeLock().lock();
}

public void 释放写锁() {
this.writeLock().unlock();
}
}

