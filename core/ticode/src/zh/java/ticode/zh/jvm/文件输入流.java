package ticode.zh.jvm;


public abstract class 文件输入流 extends java.io.FileInputStream {
public 文件输入流 赋值_op(java.io.File 目标文件) {
try {
return (文件输入流)new java.io.FileInputStream(目标文件);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static 文件输入流 从路径创建(String 文件路径) {
try {
return (文件输入流)new java.io.FileInputStream(文件路径);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}
}