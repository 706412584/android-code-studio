package ticode.zh.jvm;


public abstract class 对象输入流 extends java.io.ObjectInputStream {
public 对象输入流 赋值_op(输入流 目标输入流) {
try {
return (对象输入流)new java.io.ObjectInputStream(目标输入流);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static 对象输入流 从路径创建(String 文件路径) {
try {
return (对象输入流)new java.io.ObjectInputStream(new java.io.FileInputStream(文件路径));
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