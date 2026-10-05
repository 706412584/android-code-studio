package ticode.zh.jvm;


public abstract class 枚举器 implements java.util.Iterator {



public boolean 还有下一个() {
return this.hasNext();
}




public Object 下一个() {
return this.next();
}
}