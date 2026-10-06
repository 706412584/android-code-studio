package ticode.jvm;

import java.util.zip.*;
import java.io.*;

public class GzipOutputStream2 extends java.util.zip.GZIPOutputStream {

public GzipOutputStream2 赋值_op(JOutputStream 输出流1) {
try {
return new GZIPOutputStream(输出流1);
} catch(java.io.IOException e) {
throw new RuntimeException(e.getMessage());
}
}

public static DeflaterOutputStream2 创建实例(JOutputStream 输出流1, Deflater2 压缩器1) {
return new DeflaterOutputStream(输出流1, 压缩器1);
}
public static DeflaterOutputStream2 创建实例2(JOutputStream 输出流1, Deflater2 压缩器1, int 大小) {
return new DeflaterOutputStream(输出流1, 压缩器1, 大小);
}
}