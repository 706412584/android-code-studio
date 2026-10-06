package ticode.jvm;

import java.util.zip.*;
import java.io.*;

public class InflaterInputStream2 extends java.util.zip.InflaterInputStream {

public InflaterInputStream2 赋值_op(JInputStream 输入流1) {
return new InflaterInputStream(输入流1);
}

public static InflaterInputStream2 创建实例(JInputStream 输入流1, Inflater2 解压器1) {
return new InflaterInputStream(输入流1, 解压器1);
}

public static InflaterInputStream2 创建实例2(JInputStream 输入流1, Inflater2 解压器1, int 大小) {
return new InflaterInputStream(输入流1, 解压器1, 大小);
}

}