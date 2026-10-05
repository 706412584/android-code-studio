package ticode.jvm;

import java.util.concurrent.*;

import ticode.base.IntegerBox;

public class ReadWriteLock2 extends java.util.concurrent.locks.ReentrantReadWriteLock {
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