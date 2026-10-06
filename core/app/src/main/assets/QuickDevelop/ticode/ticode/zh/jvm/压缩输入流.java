package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public class 压缩输入流 extends java.util.zip.DeflaterInputStream {
public 压缩输入流(java.io.InputStream 输入流1, java.util.zip.Deflater 压缩器1) { super(输入流1, 压缩器1); }
public 压缩输入流(java.io.InputStream 输入流1, java.util.zip.Deflater 压缩器1, int 大小) { super(输入流1, 压缩器1, 大小); }

public 压缩输入流(java.io.InputStream 输入流1) { super(输入流1); }

public static 压缩输入流 创建实例(输入流 输入流1, 压缩器 压缩器1) {
return new 压缩输入流(输入流1, 压缩器1);
}

public static 压缩输入流 创建实例2(输入流 输入流1, 压缩器 压缩器1, int 大小) {
return new 压缩输入流(输入流1, 压缩器1, 大小);
}

}