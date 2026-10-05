package ticode.zh.jvm;

import java.io.Serializable;

public class 文件输入流 extends java.io.FileInputStream {
public void 赋值_op(文件 目标文件) {
try {
return new java.io.FileInputStream(目标文件);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static 文件输入流 从路径创建(String 文件路径) {
try {
return new java.io.FileInputStream(文件路径);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}
}