package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public abstract class 解压输出流 extends java.util.zip.InflaterOutputStream {

public 解压输出流 赋值_op(输出流 输出流1) {
return (解压输出流)new InflaterOutputStream(输出流1);
}

public static 解压输出流 创建实例(输出流 输出流1, 解压器 解压器1) {
return (解压输出流)new InflaterOutputStream(输出流1, 解压器1);
}

public static 解压输出流 创建实例2(输出流 输出流1, 解压器 解压器1, int 大小) {
return (解压输出流)new InflaterOutputStream(输出流1, 解压器1, 大小);
}

}