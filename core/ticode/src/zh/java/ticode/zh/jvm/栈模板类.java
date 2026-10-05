package ticode.zh.jvm;


public class 栈模板类<T1> extends java.util.Stack {

public int 长度() {
return this.size();
}

public 枚举器 枚举器() {
return this.iterator();
}

public T1 出栈() {
return this.pop();
}

public void 压栈(T1 成员) {
this.push(成员);
}

public T1 取栈顶成员() {
return this.peek();
}

public void 清空() {
this.clear();
}

}