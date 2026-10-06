package ticode.zh.jvm;


public class Java类型 implements java.lang.reflect.Type {

// 获取本类型的名称 需要安卓api28，安卓9可使用
public String 类型名称() {
return this.getTypeName();
}

}