package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public class GZIP输入流 extends java.util.zip.GZIPInputStream {

public GZIP输入流(java.io.InputStream 输入流1) throws Exception { super(输入流1); }

public static 解压输入流 创建实例(输入流 输入流1, 解压器 解压器1) {
return new 解压输入流(输入流1, 解压器1);
}
public static 解压输入流 创建实例2(输入流 输入流1, 解压器 解压器1, int 大小) {
return new 解压输入流(输入流1, 解压器1, 大小);
}
}