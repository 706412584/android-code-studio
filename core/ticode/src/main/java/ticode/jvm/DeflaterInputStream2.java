package ticode.jvm;

import java.util.zip.*;
import java.io.File;
import java.io.*;

public class DeflaterInputStream2 extends java.util.zip.DeflaterInputStream {

public void 赋值_op(JInputStream 输入流1) {
return new DeflaterInputStream(输入流1);
}

public static DeflaterInputStream2 创建实例(JInputStream 输入流1, Deflater2 压缩器1) {
return new DeflaterInputStream(输入流1, 压缩器1);
}

public static DeflaterInputStream2 创建实例2(JInputStream 输入流1, Deflater2 压缩器1, int 大小) {
return new DeflaterInputStream(输入流1, 压缩器1, 大小);
}

}