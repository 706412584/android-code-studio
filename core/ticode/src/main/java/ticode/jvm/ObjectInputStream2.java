package ticode.jvm;


public class ObjectInputStream2 extends java.io.ObjectInputStream {
public ObjectInputStream2 赋值_op(JInputStream 目标输入流) {
try {
return new java.io.ObjectInputStream(目标输入流);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static ObjectInputStream2 从路径创建(String 文件路径) {
try {
return new java.io.ObjectInputStream(new java.io.FileInputStream(文件路径));
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public Object 读入对象() {
try {
return this.readObject();
} catch (Exception e){
return null;
}
}
public int 读取() {
try {
return this.read();
} catch (Exception e) {
e.printStackTrace();
}
return -1;
}
public int 读到字节集(byte[] 字节集) {
try {
return this.read(字节集);
} catch (Exception e) {
e.printStackTrace();
}
return -1;
}
public int 读到字节集2(byte[] 字节集, int 起始索引, int 长度) {
try {
return this.read(字节集,起始索引,长度);
} catch (Exception e) {
e.printStackTrace();
}
return -1;
}
public int 可读取字节数量() {
try {
return this.available();
} catch (Exception e) {
e.printStackTrace();
}
return -1;
}
public void 关闭() {
try {
this.close();
} catch (Exception e) {
e.printStackTrace();
}
}
}