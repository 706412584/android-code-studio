package ticode.jvm;


public class ClassNameEnumerator implements java.util.Enumeration {
public boolean 还有下一个() {
return this.hasMoreElements();
}

public Object 取下一个对象() {
return this.nextElement();
}
}