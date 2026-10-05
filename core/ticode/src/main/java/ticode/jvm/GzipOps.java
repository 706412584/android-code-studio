package ticode.jvm;

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

public class GzipOps extends java.util.zip.GZIPOutputStream {

public static byte[] 压缩字节集(byte[] 欲压缩数据) {
ByteArrayOutputStream2 字节集输出流1;
GzipOutputStream2 GZIP输出流1 = 字节集输出流1;
GZIP输出流1.写出字节集(欲压缩数据);
字节集输出流1.刷新();
GZIP输出流1.关闭();
return 字节集输出流1.到字节集();
}

public static byte[] 解压字节集(byte[] 欲解压数据) {
ByteArrayInputStream2 字节集输入流1 = 欲解压数据;
ByteArrayOutputStream2 字节集输出流1;
GzipInputStream2 GZIP输入流1 = 字节集输入流1;
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

public static void 压缩文件(String 原文件路径, String 压缩后文件路径) {
JFile 原文件 = 原文件路径;
FileInputStream2 文件输入流1 = 原文件;
JFile 输出文件 = 压缩后文件路径;
FileOutputStream2 文件输出流1 = 创建文件输出流(输出文件);
GzipOutputStream2 GZIP输出流1 = 文件输出流1;
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

public static void 解压文件(String 欲解压文件路径, String 输出文件路径) {
JFile 欲解压文件 = 欲解压文件路径;
FileInputStream2 文件输入流1 = 欲解压文件;
JFile 输出文件 = 输出文件路径;
FileOutputStream2 文件输出流1 = 创建文件输出流(输出文件);
GzipInputStream2 GZIP输入流1 = 文件输入流1;
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

public static FileOutputStream2 创建文件输出流(JFile 文件1) {
try {
return new java.io.FileOutputStream(文件1);
} catch (Exception e) {
throw new RuntimeException("创建文件输出流失败：" + e.getMessage());
}
}

}