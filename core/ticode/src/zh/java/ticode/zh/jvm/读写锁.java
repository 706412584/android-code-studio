package ticode.zh.jvm;

import java.util.concurrent.*;

import ticode.zh.base.整数类;

public class 读写锁 extends java.util.concurrent.locks.ReentrantReadWriteLock {
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