package ticode.jvm;

import java.util.zip.*;
import java.io.*;

public class GzipInputStream2 extends java.util.zip.GZIPInputStream {

public GzipInputStream2 赋值_op(JInputStream 输入流1) {
try {
return new GZIPInputStream(输入流1);
} catch(java.io.IOException e) {
throw new RuntimeException(e.getMessage());
}
}

public static InflaterInputStream2 创建实例(JInputStream 输入流1, Inflater2 解压器1) {
return new InflaterInputStream(输入流1, 解压器1);
}
public static InflaterInputStream2 创建实例2(JInputStream 输入流1, Inflater2 解压器1, int 大小) {
return new InflaterInputStream(输入流1, 解压器1, 大小);
}
}