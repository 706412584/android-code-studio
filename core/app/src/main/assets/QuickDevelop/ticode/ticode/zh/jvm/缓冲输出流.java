package ticode.zh.jvm;


public class 缓冲输出流 extends java.io.BufferedOutputStream {
public 缓冲输出流(java.io.OutputStream 目标输出流) { super(目标输出流); }

public static 缓冲输出流 从路径创建(String 文件路径) {
try {
return new 缓冲输出流(new java.io.FileOutputStream(文件路径));
} catch (Exception e) {
e.printStackTrace();
}
return null;
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