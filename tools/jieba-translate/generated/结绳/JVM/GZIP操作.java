package 结绳.JVM;

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

public class GZIP操作 {

public byte[] 压缩字节集(byte[] 欲压缩数据) {
字节集输出流 字节集输出流1;
GZIP输出流 GZIP输出流1 = 字节集输出流1;
GZIP输出流1.写出字节集(欲压缩数据);
字节集输出流1.刷新();
GZIP输出流1.关闭();
return 字节集输出流1.到字节集();
}

public byte[] 解压字节集(byte[] 欲解压数据) {
字节集输入流 字节集输入流1 = 欲解压数据;
字节集输出流 字节集输出流1;
GZIP输入流 GZIP输入流1 = 字节集输入流1;
byte[] 缓冲 = new byte[1024];
int 长度 = GZIP输入流1.读到字节集(缓冲);
while (长度 != -1) {
字节集输出流1.写出(缓冲,0,长度);
长度 = GZIP输入流1.读到字节集(缓冲);
}
GZIP输入流1.关闭();
字节集输出流1.刷新();
byte[] 结果 = 字节集输出流1.到字节集();
字节集输出流1.关闭();
return 结果;
}

public void 压缩文件(String 原文件路径, String 压缩后文件路径) {
文件 原文件 = 原文件路径;
文件输入流 文件输入流1 = 原文件;
文件 输出文件 = 压缩后文件路径;
文件输出流 文件输出流1 = 创建文件输出流(输出文件);
GZIP输出流 GZIP输出流1 = 文件输出流1;
byte[] 缓冲 = new byte[4096];
int i = 文件输入流1.读到字节集(缓冲);
while (i != -1) {
GZIP输出流1.写出字节集2(缓冲,0,i);
i = 文件输入流1.读到字节集(缓冲);
}
文件输入流1.关闭();
文件输出流1.刷新();
GZIP输出流1.关闭();
}

public void 解压文件(String 欲解压文件路径, String 输出文件路径) {
文件 欲解压文件 = 欲解压文件路径;
文件输入流 文件输入流1 = 欲解压文件;
文件 输出文件 = 输出文件路径;
文件输出流 文件输出流1 = 创建文件输出流(输出文件);
GZIP输入流 GZIP输入流1 = 文件输入流1;
byte[] 缓冲 = new byte[1024];
int 长度 = GZIP输入流1.读到字节集(缓冲);
while (长度 != -1) {
文件输出流1.写出字节集2(缓冲,0,长度);
长度 = GZIP输入流1.读到字节集(缓冲);
}
GZIP输入流1.关闭();
文件输出流1.刷新();
文件输出流1.关闭();
}

public 文件输出流 创建文件输出流(文件 文件1) {
try {
return new java.io.FileOutputStream(文件1);
} catch (Exception e) {
throw new RuntimeException("创建文件输出流失败：" + e.getMessage());
}
}

}