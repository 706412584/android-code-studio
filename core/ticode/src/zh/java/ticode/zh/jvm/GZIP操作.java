package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public class GZIP操作 {

public static byte[] 压缩字节集(byte[] 欲压缩数据) {
try {
字节集输出流 字节集输出流1 = (字节集输出流)new java.io.ByteArrayOutputStream();
GZIP输出流 GZIP输出流1 = 创建GZIP输出流(字节集输出流1);
GZIP输出流1.write(欲压缩数据);
字节集输出流1.flush();
GZIP输出流1.close();
return 字节集输出流1.到字节集();
} catch (java.io.IOException e) {
throw new RuntimeException(e);
}
}

public static byte[] 解压字节集(byte[] 欲解压数据) {
try {
字节集输入流 字节集输入流1 = (字节集输入流)new java.io.ByteArrayInputStream(欲解压数据);
字节集输出流 字节集输出流1 = (字节集输出流)new java.io.ByteArrayOutputStream();
GZIP输入流 GZIP输入流1 = 创建GZIP输入流(字节集输入流1);
byte[] 缓冲 = new byte[1024];
int 长度 = GZIP输入流1.read(缓冲);
while (长度 != -1) {
字节集输出流1.写出(缓冲,0,长度);
长度 = GZIP输入流1.read(缓冲);
}
GZIP输入流1.close();
字节集输出流1.flush();
byte[] 结果 = 字节集输出流1.到字节集();
字节集输出流1.close();
return 结果;
} catch (java.io.IOException e) {
throw new RuntimeException(e);
}
}

public static void 压缩文件(String 原文件路径, String 压缩后文件路径) {
try {
java.io.File 原文件 = new java.io.File(原文件路径);
文件输入流 文件输入流1 = 文件输入流.从路径创建(原文件路径);
java.io.File 输出文件 = new java.io.File(压缩后文件路径);
文件输出流 文件输出流1 = 创建文件输出流(输出文件);
GZIP输出流 GZIP输出流1 = 创建GZIP输出流(文件输出流1);
byte[] 缓冲 = new byte[4096];
int i = 文件输入流1.read(缓冲);
while (i != -1) {
GZIP输出流1.write(缓冲,0,i);
i = 文件输入流1.read(缓冲);
}
文件输入流1.close();
文件输出流1.flush();
GZIP输出流1.close();
} catch (java.io.IOException e) {
throw new RuntimeException(e);
}
}

public static void 解压文件(String 欲解压文件路径, String 输出文件路径) {
try {
java.io.File 欲解压文件 = new java.io.File(欲解压文件路径);
文件输入流 文件输入流1 = 文件输入流.从路径创建(欲解压文件路径);
java.io.File 输出文件 = new java.io.File(输出文件路径);
文件输出流 文件输出流1 = 创建文件输出流(输出文件);
GZIP输入流 GZIP输入流1 = 创建GZIP输入流(文件输入流1);
byte[] 缓冲 = new byte[1024];
int 长度 = GZIP输入流1.read(缓冲);
while (长度 != -1) {
文件输出流1.write(缓冲,0,长度);
长度 = GZIP输入流1.read(缓冲);
}
GZIP输入流1.close();
文件输出流1.flush();
文件输出流1.close();
} catch (java.io.IOException e) {
throw new RuntimeException(e);
}
}

public static 文件输出流 创建文件输出流(java.io.File 文件1) {
try {
return (文件输出流)new java.io.FileOutputStream(文件1);
} catch (Exception e) {
throw new RuntimeException("创建文件输出流失败：" + e.getMessage());
}
}

public static GZIP输出流 创建GZIP输出流(java.io.OutputStream 输出流1) {
try {
return (GZIP输出流)new java.util.zip.GZIPOutputStream(输出流1);
} catch (Exception e) {
throw new RuntimeException("创建GZIP输出流失败：" + e.getMessage());
}
}

public static GZIP输入流 创建GZIP输入流(java.io.InputStream 输入流1) {
try {
return (GZIP输入流)new java.util.zip.GZIPInputStream(输入流1);
} catch (Exception e) {
throw new RuntimeException("创建GZIP输入流失败：" + e.getMessage());
}
}

}