package ticode.jvm;

import java.io.Serializable;

public class FileOutputStream2 extends java.io.FileOutputStream {
public void 赋值_op(JFile 目标文件) {
try {
return new java.io.FileOutputStream(目标文件);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static FileOutputStream2 从路径创建(String 文件路径) {
try {
return new java.io.FileOutputStream(文件路径);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}
}