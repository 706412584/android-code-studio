package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public abstract class 压缩输入流 extends java.util.zip.DeflaterInputStream {

public 压缩输入流(输入流 输入流1) { super(输入流1); }

public static 压缩输入流 创建实例(输入流 输入流1, 压缩器 压缩器1) {
return (压缩输入流)new DeflaterInputStream(输入流1, 压缩器1);
}

public static 压缩输入流 创建实例2(输入流 输入流1, 压缩器 压缩器1, int 大小) {
return (压缩输入流)new DeflaterInputStream(输入流1, 压缩器1, 大小);
}

}