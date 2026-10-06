package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public class 解压输出流 extends java.util.zip.InflaterOutputStream {
public 解压输出流(java.io.OutputStream 输出流1, java.util.zip.Inflater 解压器1) { super(输出流1, 解压器1); }
public 解压输出流(java.io.OutputStream 输出流1, java.util.zip.Inflater 解压器1, int 大小) { super(输出流1, 解压器1, 大小); }

public 解压输出流(java.io.OutputStream 输出流1) { super(输出流1); }

public static 解压输出流 创建实例(输出流 输出流1, 解压器 解压器1) {
return new 解压输出流(输出流1, 解压器1);
}

public static 解压输出流 创建实例2(输出流 输出流1, 解压器 解压器1, int 大小) {
return new 解压输出流(输出流1, 解压器1, 大小);
}

}