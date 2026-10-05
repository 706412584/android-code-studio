package ticode.zh.jvm;

import java.util.zip.*;
import java.io.File;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.util.zip.*;
import java.io.*;

public class 解压器 extends java.util.zip.Inflater {

// 创建使用GZIP兼容压缩的压缩器
public static 解压器 创建GZIP兼容解压器() {
return new Inflater(true);
}

// 获取ADLER-32值
public int adler值() {
return this.getAdler();
}

// 获取当前欲解压数据的字节总数
public long 未解压数据大小() {
return this.getBytesRead();
}

// 获取当前已解压数据的字节总数
public long 已解压数据大小() {
return this.getBytesWritten();
}

// 取剩余未解压字节数
public int 剩余未解压字节数() {
return this.getRemaining();
}

// 返回当前能否向解压器中设置欲解压数据
public boolean 能设置欲解压数据() {
return this.needsInput();
}

// 如果解压需要字典则返回 真
public boolean 需要字典() {
return this.needsDictionary();
}

// 返回当前是否已将所有欲解压数据全部解压完毕
public boolean 解压完毕() {
return this.finished();
}

// 向解压器中设置欲解压数据
public void 设置欲解压数据(byte[] 数据, int 起始偏移量, int 长度) {
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

// 解压数据，并返回此次已解压的字节数
public int 解压(byte[] 输出) {
try {
return this.inflate(输出);
} catch(DataFormatException e) {
throw new RuntimeException("欲解压数据格式错误");
}
}

// 重置解压器，以便处理新的数据
public void 重置() {
this.reset();
}

// 当解压器不再使用时，调用此方法
public void 关闭() {
this.end();
}

}