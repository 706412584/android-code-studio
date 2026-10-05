package ticode.zh.jvm;

import java.util.zip.*;
import java.io.File;
import java.io.*;

public class 解压输入流 extends java.util.zip.InflaterInputStream {

public void 赋值_op(输入流 输入流1) {
return new InflaterInputStream(输入流1);
}

public static 解压输入流 创建实例(输入流 输入流1, 解压器 解压器1) {
return new InflaterInputStream(输入流1, 解压器1);
}

public static 解压输入流 创建实例2(输入流 输入流1, 解压器 解压器1, int 大小) {
return new InflaterInputStream(输入流1, 解压器1, 大小);
}

}