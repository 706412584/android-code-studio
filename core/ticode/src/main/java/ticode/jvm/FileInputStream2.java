package ticode.jvm;


public class FileInputStream2 extends java.io.FileInputStream {
public void 赋值_op(JFile 目标文件) {
try {
return new java.io.FileInputStream(目标文件);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static FileInputStream2 从路径创建(String 文件路径) {
try {
return new java.io.FileInputStream(文件路径);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}
}