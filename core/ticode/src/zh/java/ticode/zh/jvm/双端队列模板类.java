package ticode.zh.jvm;


public class 双端队列模板类<T1> extends java.util.ArrayDeque {

public int 长度() {
return this.size();
}

public 枚举器 枚举器() {
return this.iterator();
}

public boolean 添加成员至头部(T1 成员) {
return this.offerFirst(成员);
}

public boolean 添加成员至尾部(T1 成员) {
return this.offerLast(成员);
}

public T1 弹出首成员() {
return this.pollFirst();
}

public T1 弹出尾成员() {
return this.pollLast();
}

public T1 获取首成员() {
return this.peekFirst();
}

public T1 获取尾成员() {
return this.peekLast();
}

public void 清空() {
this.clear();
}

}