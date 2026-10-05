package ticode.jvm;

import java.io.Serializable;

public class BufferedOutputStream2 extends java.io.BufferedOutputStream {
public void 赋值_op(JOutputStream 目标输出流) {
return new java.io.BufferedOutputStream(目标输出流);
}

public static BufferedOutputStream2 从路径创建(String 文件路径) {
try {
return new java.io.BufferedOutputStream(new java.io.FileOutputStream(文件路径));
} catch (Exception e) {
e.printStackTrace();
}
return null;
}
}