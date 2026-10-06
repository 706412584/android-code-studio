package ticode.zh.jvm;


public abstract class Java注解 implements java.lang.annotation.Annotation {
public java.lang.Class 取类型() {
return (java.lang.Class)this.annotationType();
}
}