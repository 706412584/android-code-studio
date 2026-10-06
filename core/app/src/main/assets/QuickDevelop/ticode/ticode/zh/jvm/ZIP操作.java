package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

import static ticode.zh.android.数组操作.取数组长度;

public class ZIP操作 {

public static void 压缩文件(String 欲压缩路径, String 输出文件路径) {
try {
java.io.File 压缩文件 = new java.io.File(欲压缩路径);
java.io.File 输出文件 = new java.io.File(输出文件路径);
文件输出流 输出文件流 = 创建文件输出流(输出文件);
ZIP输出流 zip输出流1 = 创建ZIP输出流(输出文件流);
if (压缩文件.isDirectory()) {
java.io.File[] 子文件集 = 压缩文件.listFiles();
int i;
for (i = 0; i < (子文件集).length; i++) {
压缩文件1(zip输出流1,子文件集[i],"");
}
} else {
压缩文件1(zip输出流1,压缩文件,"");
}
zip输出流1.close();
} catch (java.io.IOException e) {
throw new RuntimeException(e);
}
}

public static void 压缩文件1(ZIP输出流 zip输出流, java.io.File 压缩文件, String 当前路径) {
try {
if (压缩文件.isDirectory()) {
java.io.File[] 子文件集 = 压缩文件.listFiles();
if ((子文件集).length <= 0) {
ZIP条目 条目 = ZIP条目.创建新条目(当前路径 + 压缩文件.getName() + "/");
zip输出流.添加并打开条目(条目);
zip输出流.关闭当前条目();
} else {
int i;
for (i = 0; i < (子文件集).length; i++) {
压缩文件1(zip输出流,子文件集[i],当前路径 + 压缩文件.getName() + "/");
}
}
} else {
byte[] 缓冲 = new byte[4096];
文件输入流 文件输入流1 = 文件输入流.从路径创建(压缩文件.getPath());
ZIP条目 条目1 = ZIP条目.创建新条目(当前路径 + 压缩文件.getName());
zip输出流.添加并打开条目(条目1);
int 长度 = 文件输入流1.read(缓冲);
while (长度 != -1) {
zip输出流.write(缓冲,0,长度);
长度 = 文件输入流1.read(缓冲);
}
zip输出流.关闭当前条目();
文件输入流1.close();
}
} catch (java.io.IOException e) {
throw new RuntimeException(e);
}
}

public static void 解压文件(String ZIP路径, String 输出文件夹路径) {
try {
ZIP文件 zip = ZIP文件.指定编码创建(ZIP路径, "UTF-8");
java.io.File 目标文件 = new java.io.File(输出文件夹路径);
目标文件.mkdirs();
ZIP条目[] 条目集 = zip.取所有条目();
int j;
for (j = 0; j < (条目集).length; j++) {
ZIP条目 条目 = 条目集[j];
String 条目路径 = 条目.取路径();
if (条目.是文件夹条目()) {
条目路径 = 条目路径.substring(0, (条目路径.length() - 2) + 1);
java.io.File 目录 = 文件.新建对象(输出文件夹路径, 条目路径);
目录.mkdirs();
} else {
int 索引 = 条目路径.indexOf("/", 0);
if (索引 != -1) {
java.io.File 目录2 = 文件.新建对象(输出文件夹路径, 条目路径.substring(0, (索引 - 1) + 1));
目录2.mkdirs();
}
java.io.InputStream 输入流1 = zip.取输入流(条目);
java.io.File 文件1 = 文件.新建对象(输出文件夹路径, 条目路径);
文件输出流 文件输出流1 = 创建文件输出流(文件1);
byte[] 字节数组 = new byte[1024];
int i;
i = 输入流1.read(字节数组);
while (i != -1) {
文件输出流1.write(字节数组, 0, i);
i = 输入流1.read(字节数组);
}
文件输出流1.flush();
}
}
zip.关闭();
} catch (java.io.IOException e) {
throw new RuntimeException(e);
}
}

public static void 解压单个文件(String ZIP路径, String 欲解压文件条目路径, String 输出路径) {
try {
ZIP文件 zip = ZIP文件.指定编码创建(ZIP路径, "UTF-8");
ZIP条目 条目 = zip.取条目(欲解压文件条目路径);
if (条目 != null) {
java.io.InputStream 输入流1 = zip.取输入流(条目);
java.io.File 文件1 = new java.io.File(输出路径);
文件输出流 文件输出流1 = 创建文件输出流(文件1);
byte[] 字节数组 = new byte[1024];
int i;
i = 输入流1.read(字节数组);
while (i != -1) {
文件输出流1.write(字节数组, 0, i);
i = 输入流1.read(字节数组);
}
文件输出流1.flush();
}
zip.关闭();
} catch (java.io.IOException e) {
throw new RuntimeException(e);
}
}

public static 文件输出流 创建文件输出流(java.io.File 文件1) {
try {
return new 文件输出流(文件1);
} catch (Exception e) {
throw new RuntimeException("创建文件输出流失败：" + e.getMessage());
}
}

public static ZIP输出流 创建ZIP输出流(java.io.OutputStream 输出流1) {
try {
return new ZIP输出流(输出流1);
} catch (Exception e) {
throw new RuntimeException("创建ZIP输出流失败：" + e.getMessage());
}
}

}