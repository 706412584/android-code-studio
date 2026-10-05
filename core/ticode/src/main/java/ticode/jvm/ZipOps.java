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

public class ZipOps {

public static void 压缩文件(String 欲压缩路径, String 输出文件路径) {
JFile 压缩文件 = 欲压缩路径;
JFile 输出文件 = 输出文件路径;
FileOutputStream2 输出文件流 = 创建文件输出流(输出文件);
ZipOutputStream2 zip输出流1 = 输出文件流;
if (压缩文件.为文件夹()) {
JFile[] 子文件集 = 压缩文件.取子文件数组();
int i;
for (int i = 0; i < 取数组长度(子文件集); i++) {
压缩文件1(zip输出流1,子文件集[i],"");
}
} else {
压缩文件1(zip输出流1,压缩文件,"");
}
zip输出流1.关闭();
}

public static void 压缩文件1(ZipOutputStream2 zip输出流, JFile 压缩文件, String 当前路径) {
if (压缩文件.为文件夹()) {
JFile[] 子文件集 = 压缩文件.取子文件数组();
if (取数组长度(子文件集) <= 0) {
ZipEntry2 条目 = ZipEntry2.创建新条目(当前路径 + 压缩文件.取文件名() + "/");
zip输出流.添加并打开条目(条目);
zip输出流.关闭当前条目();
} else {
int i;
for (int i = 0; i < 取数组长度(子文件集); i++) {
压缩文件1(zip输出流,子文件集[i],当前路径 + 压缩文件.取文件名() + "/");
}
}
} else {
byte[] 缓冲 = new byte[4096];
FileInputStream2 文件输入流1 = 压缩文件;
ZipEntry2 条目1 = ZipEntry2.创建新条目(当前路径 + 压缩文件.取文件名());
zip输出流.添加并打开条目(条目1);
int 长度 = 文件输入流1.读到字节集(缓冲);
while (长度 != -1) {
zip输出流.写出字节集2(缓冲,0,长度);
长度 = 文件输入流1.读到字节集(缓冲);
}
zip输出流.关闭当前条目();
文件输入流1.关闭();
}
}

public static void 解压文件(String ZIP路径, String 输出文件夹路径) {
ZipFile2 zip = ZIP路径;
JFile 目标文件 = 输出文件夹路径;
目标文件.新建文件夹();
ZipEntry2[] 条目集 = zip.取所有条目();
int j;
for (int j = 0; j < 取数组长度(条目集); j++) {
ZipEntry2 条目 = 条目集[j];
String 条目路径 = 条目.取路径();
if (条目.是文件夹条目) {
条目路径 = 条目路径.取文本中间(0, 条目路径.长度 - 2);
JFile 目录 = JFile.新建对象(输出文件夹路径, 条目路径);
目录.新建文件夹();
} else {
int 索引 = 条目路径.寻找文本("/", 0);
if (索引 != -1) {
JFile 目录2 = JFile.新建对象(输出文件夹路径, 条目路径.取文本中间(0, 索引 - 1));
目录2.新建文件夹();
}
JInputStream 输入流1 = zip.取输入流(条目);
JFile 文件1 = JFile.新建对象(输出文件夹路径, 条目路径);
FileOutputStream2 文件输出流1 = 创建文件输出流(文件1);
byte[] 字节数组 = new byte[1024];
int i;
i = 输入流1.读到字节集(字节数组);
while (i != -1) {
文件输出流1.写出字节集2(字节数组, 0, i);
i = 输入流1.读到字节集(字节数组);
}
文件输出流1.刷新();
}
}
zip.关闭();
}

public static void 解压单个文件(String ZIP路径, String 欲解压文件条目路径, String 输出路径) {
ZipFile2 zip = ZIP路径;
ZipEntry2 条目 = zip.取条目(欲解压文件条目路径);
if (条目 != null) {
JInputStream 输入流1 = zip.取输入流(条目);
JFile 文件1 = 输出路径;
FileOutputStream2 文件输出流1 = 创建文件输出流(文件1);
byte[] 字节数组 = new byte[1024];
int i;
i = 输入流1.读到字节集(字节数组);
while (i != -1) {
文件输出流1.写出字节集2(字节数组, 0, i);
i = 输入流1.读到字节集(字节数组);
}
文件输出流1.刷新();
}
zip.关闭();
}

public static FileOutputStream2 创建文件输出流(JFile 文件1) {
try {
return new java.io.FileOutputStream(文件1);
} catch (Exception e) {
throw new RuntimeException("创建文件输出流失败：" + e.getMessage());
}
}

}