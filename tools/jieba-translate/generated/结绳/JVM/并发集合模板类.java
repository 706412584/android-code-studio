package 结绳.JVM;

import java.util.concurrent.*;

public class 并发集合模板类<T1> extends 可枚举类 {

public void 赋值_op(T1[] 成员) {
java.util.Vector<T1> list = new java.util.Vector<>(成员.length);
for(T1 el : 成员) {
list.add(el);
}
return list;
}

public T1 取索引_op(int 索引) {
return 取成员(索引);
}

public void 设索引_op(int 索引, T1 值) {
置成员(索引, 值);
}

public boolean 是_op(T1 值) {
return 是否存在(值);
}

public boolean 是否为空() {
return this.isEmpty();
}

public void 添加成员(T1 成员) {
this.add(成员);
}

public void 插入成员(int 索引, T1 成员) {
this.add(索引,成员);
}

public void 置成员(int 索引, T1 成员) {
this.set(索引,成员);
}

public T1 取成员(int 索引) {
return this.get(索引);
}

public boolean 是否存在(T1 成员) {
return this.contains(成员);
}

public int 寻找成员(T1 成员) {
return this.indexOf(成员);
}

//清空集合
public void 清空() {
this.clear();
}

//删除指定索引处成员
public void 删除成员(int 索引) {
this.remove(索引);
}

//删除指定成员对象
public void 删除成员2(T1 成员) {
this.remove(成员);
}




public T1[] 到数组() {
return this.toArray(new T1[0]);
}

public void 打乱集合() {
for (int i = 0,max = this.size() - 1;i < max;i++) {
if (System.nanoTime() % 2 == 0) {
T1 tmp = this.get(i);
this.set(i, this.get(i + 1));
this.set(i + 1, tmp);
}
}
}

public int 长度() {
return this.size();
}




public 枚举器 枚举器() {
return this.iterator();
}
}

