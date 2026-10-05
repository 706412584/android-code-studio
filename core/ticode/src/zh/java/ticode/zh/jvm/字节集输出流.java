package ticode.zh.jvm;


public abstract class 字节集输出流 extends java.io.ByteArrayOutputStream {

public void 写出(byte[] 字节集, int 起始索引, int 长度) {
try {
if (长度 == -1) { 长度 = 字节集.length; }
this.write(字节集, 起始索引, 长度);
} catch (Exception e) {
e.printStackTrace();
}
}

public void 写出到输出流(输出流 目标输出流) {
try {
this.writeTo(目标输出流);
} catch (Exception e){
e.printStackTrace();
}
}

public byte[] 到字节集() {
return this.toByteArray();
}
}