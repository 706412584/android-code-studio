package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public class 解压输入流 extends java.util.zip.InflaterInputStream {
public 解压输入流(java.io.InputStream 输入流1, java.util.zip.Inflater 解压器1) { super(输入流1, 解压器1); }
public 解压输入流(java.io.InputStream 输入流1, java.util.zip.Inflater 解压器1, int 大小) { super(输入流1, 解压器1, 大小); }

public 解压输入流(java.io.InputStream 输入流1) { super(输入流1); }

public static 解压输入流 创建实例(输入流 输入流1, 解压器 解压器1) {
return new 解压输入流(输入流1, 解压器1);
}

public static 解压输入流 创建实例2(输入流 输入流1, 解压器 解压器1, int 大小) {
return new 解压输入流(输入流1, 解压器1, 大小);
}

}