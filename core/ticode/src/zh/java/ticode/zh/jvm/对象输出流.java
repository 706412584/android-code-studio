package ticode.zh.jvm;


public class 对象输出流 extends java.io.ObjectOutputStream {
public void 赋值_op(输出流 目标输出流) {
try {
return new java.io.ObjectOutputStream(目标输出流);
} catch (java.io.IOException e) {
e.printStackTrace();
return null;
}
}

public static 对象输出流 从路径创建(String 文件路径) {
try {
return new java.io.ObjectOutputStream(new java.io.FileOutputStream(文件路径));
} catch (java.io.IOException e) {
e.printStackTrace();
return null;
}
}

public void 写出对象(序列化类 欲写出对象) {
try {
this.writeObject(欲写出对象);
} catch (Exception e){
e.printStackTrace();
}
}
}