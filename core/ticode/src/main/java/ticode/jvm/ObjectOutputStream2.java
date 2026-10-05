package ticode.jvm;


public class ObjectOutputStream2 extends java.io.ObjectOutputStream {
public void 赋值_op(JOutputStream 目标输出流) {
try {
return new java.io.ObjectOutputStream(目标输出流);
} catch (java.io.IOException e) {
e.printStackTrace();
return null;
}
}

public static ObjectOutputStream2 从路径创建(String 文件路径) {
try {
return new java.io.ObjectOutputStream(new java.io.FileOutputStream(文件路径));
} catch (java.io.IOException e) {
e.printStackTrace();
return null;
}
}

public void 写出对象(SerializationUtils 欲写出对象) {
try {
this.writeObject(欲写出对象);
} catch (Exception e){
e.printStackTrace();
}
}
}