package 结绳.JVM;

import java.io.Serializable;

public class 文件输出流 extends 输出流 {
public void 赋值_op(文件 目标文件) {
try {
return new java.io.FileOutputStream(目标文件);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public 文件输出流 从路径创建(String 文件路径) {
try {
return new java.io.FileOutputStream(文件路径);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}
}


