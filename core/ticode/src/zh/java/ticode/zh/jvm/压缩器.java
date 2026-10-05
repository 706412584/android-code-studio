package ticode.zh.jvm;

import java.util.zip.*;
import java.io.File;
import java.io.*;

public class 压缩器 extends java.util.zip.Deflater {

public static final int 压缩等级_默认 = -1;
public static final int 压缩等级_无压缩 = 0;
public static final int 压缩等级_最快压缩 = 1;
public static final int 压缩等级_最佳压缩 = 9;

public static final int 压缩策略_默认 = 0;
public static final int 压缩策略_小值数据 = 1;
public static final int 压缩策略_霍夫曼编码 = 2;

// 创建压缩器对象并指定压缩等级(0-9)
public void 赋值_op(int 压缩等级) {
return new Deflater(压缩等级);
}

// 创建使用GZIP兼容压缩的压缩器
public static 压缩器 创建GZIP兼容压缩器(int 压缩等级) {
return new Deflater(压缩等级,true);
}

// 设置压缩级别 0-9
public void 压缩等级(int 等级) {
try {
this.setLevel(等级);
} catch(IllegalArgumentException e) {
throw new RuntimeException("无效的压缩等级：" + 等级);
}
}

// 设置压缩器压缩策略
public void 压缩策略(int 策略) {
try {
this.setStrategy(策略);
} catch(IllegalArgumentException e) {
throw new RuntimeException("无效的压缩策略：" + 策略);
}
}

// 获取ADLER-32值
public int adler值() {
return this.getAdler();
}

// 获取当前未压缩数据的字节总数
public long 未压缩数据大小() {
return this.getBytesRead();
}

// 获取当前已压缩数据的字节总数
public long 已压缩数据大小() {
return this.getBytesWritten();
}

// 返回当前能否向压缩器中设置欲压缩数据
public boolean 能设置欲压缩数据() {
return this.needsInput();
}

// 返回当前是否已将所有欲压缩数据全部压缩完毕
public boolean 压缩完毕() {
return this.finished();
}

// 向压缩器中设置欲压缩数据
public void 设置欲压缩数据(byte[] 数据, int 起始偏移量, int 长度) {
if (长度 == -1) {
长度 = 取数组长度(数据);
}
this.setInput(数据,起始偏移量,长度);
}

public void 设置字典(byte[] 字典, int 起始偏移量, int 长度) {
if (长度 == -1) {
长度 = 取数组长度(字典);
}
this.setDictionary(字典,起始偏移量,长度);
}

// 向压缩器中设置完欲压缩数据后，调用此方法
public void 欲压缩数据设置结束() {
this.finish();
}

// 压缩数据，并返回此次已压缩的字节数
public int 压缩(byte[] 输出) {
return this.deflate(输出);
}

// 重置压缩器，以便处理新的数据
public void 重置() {
this.reset();
}

// 当压缩器不再使用时，调用此方法
public void 关闭() {
this.end();
}

}