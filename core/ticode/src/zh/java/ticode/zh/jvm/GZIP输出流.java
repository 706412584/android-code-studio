package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public abstract class GZIP输出流 extends java.util.zip.GZIPOutputStream {

public GZIP输出流(输出流 输出流1) throws Exception { super(输出流1); }

public static 压缩输出流 创建实例(输出流 输出流1, 压缩器 压缩器1) {
return (压缩输出流)new DeflaterOutputStream(输出流1, 压缩器1);
}
public static 压缩输出流 创建实例2(输出流 输出流1, 压缩器 压缩器1, int 大小) {
return (压缩输出流)new DeflaterOutputStream(输出流1, 压缩器1, 大小);
}
}