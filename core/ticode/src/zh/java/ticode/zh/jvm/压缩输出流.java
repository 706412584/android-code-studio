package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public class 压缩输出流 extends java.util.zip.DeflaterOutputStream {
public 压缩输出流(java.io.OutputStream 输出流1, java.util.zip.Deflater 压缩器1) { super(输出流1, 压缩器1); }
public 压缩输出流(java.io.OutputStream 输出流1, java.util.zip.Deflater 压缩器1, int 大小) { super(输出流1, 压缩器1, 大小); }

public 压缩输出流(java.io.OutputStream 输出流1) { super(输出流1); }

public static 压缩输出流 创建实例(输出流 输出流1, 压缩器 压缩器1) {
return new 压缩输出流(输出流1, 压缩器1);
}

public static 压缩输出流 创建实例2(输出流 输出流1, 压缩器 压缩器1, int 大小) {
return new 压缩输出流(输出流1, 压缩器1, 大小);
}

}