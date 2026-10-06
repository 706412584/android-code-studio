package ticode.zh.jvm;


public class 双端队列 extends 双端队列模板类<Object> {

public static 双端队列 从集合创建(集合 集合1) {
return (双端队列)new java.util.ArrayDeque(集合1);
}

}