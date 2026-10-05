package ticode.jvm;


import ticode.base.IntegerBox;

public class JEnumerator implements java.util.Iterator {



public boolean 还有下一个() {
return this.hasNext();
}




public Object 下一个() {
return this.next();
}
}