package ticode.jvm;


public class JOutputStream extends java.io.OutputStream {
public void 写出(int 数据) {
try {
this.write(数据);
} catch (Exception e) {
e.printStackTrace();
}
}

public void 写出字节集(byte[] 字节集) {
try {
this.write(字节集);
} catch (Exception e) {
e.printStackTrace();
}
}

public void 写出字节集2(byte[] 字节集, int 起始索引, int 长度) {
try {
this.write(字节集,起始索引,长度);
} catch (Exception e) {
e.printStackTrace();
}
}

public void 刷新() {
try {
this.flush();
} catch (Exception e) {
e.printStackTrace();
}
}

public void 关闭() {
try {
this.close();
} catch (Exception e) {
e.printStackTrace();
}
}
}