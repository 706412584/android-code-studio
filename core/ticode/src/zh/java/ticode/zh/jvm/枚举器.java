package ticode.zh.jvm;


import ticode.zh.base.整数类;

public class 枚举器 implements java.util.Iterator {



public boolean 还有下一个() {
return this.hasNext();
}




public Object 下一个() {
return this.next();
}
}