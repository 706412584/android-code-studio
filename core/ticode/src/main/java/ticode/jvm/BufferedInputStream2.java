package ticode.jvm;

import java.io.Serializable;

public class BufferedInputStream2 extends java.io.BufferedInputStream {
public void 赋值_op(JInputStream 目标输入流) {
return new java.io.BufferedInputStream(目标输入流);
}

public static BufferedInputStream2 从路径创建(String 文件路径) {
try {
return new java.io.BufferedInputStream(new java.io.FileInputStream(文件路径));
} catch (Exception e) {
e.printStackTrace();
}
return null;
}
}