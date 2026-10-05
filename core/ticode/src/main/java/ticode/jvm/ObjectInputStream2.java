package ticode.jvm;

import java.io.Serializable;

public class ObjectInputStream2 extends java.io.ObjectInputStream {
public void 赋值_op(JInputStream 目标输入流) {
try {
return new java.io.ObjectInputStream(目标输入流);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static ObjectInputStream2 从路径创建(String 文件路径) {
try {
return new java.io.ObjectInputStream(new java.io.FileInputStream(文件路径));
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public Object 读入对象() {
try {
return this.readObject();
} catch (Exception e){
return null;
}
}
}