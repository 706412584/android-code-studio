package ticode.zh.jvm;


public abstract class 缓冲输出流 extends java.io.BufferedOutputStream {
public 缓冲输出流 赋值_op(输出流 目标输出流) {
return (缓冲输出流)new java.io.BufferedOutputStream(目标输出流);
}

public static 缓冲输出流 从路径创建(String 文件路径) {
try {
return (缓冲输出流)new java.io.BufferedOutputStream(new java.io.FileOutputStream(文件路径));
} catch (Exception e) {
e.printStackTrace();
}
return null;
}
}