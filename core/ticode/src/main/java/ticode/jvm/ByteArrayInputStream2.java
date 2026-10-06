package ticode.jvm;


public class ByteArrayInputStream2 extends java.io.ByteArrayInputStream {
public ByteArrayInputStream2 赋值_op(byte[] 字节集) {
return new java.io.ByteArrayInputStream(字节集);
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