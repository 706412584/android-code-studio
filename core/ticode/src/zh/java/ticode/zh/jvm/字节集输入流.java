package ticode.zh.jvm;


public abstract class 字节集输入流 extends java.io.ByteArrayInputStream {
public 字节集输入流 赋值_op(byte[] 字节集) {
return (字节集输入流)new java.io.ByteArrayInputStream(字节集);
}
}