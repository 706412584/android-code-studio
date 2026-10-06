package ticode.jvm;


public class ObjectOutputStream2 extends java.io.ObjectOutputStream {
public ObjectOutputStream2 赋值_op(JOutputStream 目标输出流) {
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
public void 写出(int 数据) {
try {
this.write(数据);
} catch (Exception e) {
e.printStackTrace();
}
}
public void 写出字节集(byte[] 字节集) {
try {
this.write(字节集);
} catch (Exception e) {
e.printStackTrace();
}
}
public void 写出字节集2(byte[] 字节集, int 起始索引, int 长度) {
try {
this.write(字节集,起始索引,长度);
} catch (Exception e) {
e.printStackTrace();
}
}
public void 刷新() {
try {
this.flush();
} catch (Exception e) {
e.printStackTrace();
}
}
public void 关闭() {
try {
this.close();
} catch (Exception e) {
e.printStackTrace();
}
}
}