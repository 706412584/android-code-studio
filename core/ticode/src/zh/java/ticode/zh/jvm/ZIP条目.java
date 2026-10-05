package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public abstract class ZIP条目 extends java.util.zip.ZipEntry {

public static final int 压缩方法_存储 = 0;
public static final int 压缩方法_压缩 = 8;

public static ZIP条目 创建新条目(String 条目路径) {
try {
return (ZIP条目)new ZipEntry(条目路径);
} catch(NullPointerException e) {
throw new RuntimeException("路径不能为空");
} catch(IllegalArgumentException e) {
throw new RuntimeException("路径长度不能超过65535个字节");
}
}

// 返回条目的路径
public String 取路径() {
return this.getName();
}

// 返回条目的最后修改时间，如果未设置，则为-1
public long 最后修改时间() {
return this.getTime();
}

// 设置条目的最后修改时间
public void 最后修改时间(long 时间戳) {
this.setTime(时间戳);
}

// 返回条目创建时间，如果未知，则为-1
public long 创建时间() {
java.nio.file.attribute.FileTime ft = this.getCreationTime();
if(ft != null) {
return ft.toMillis();
}
return -1;
}

// 设置条目创建时间
public void 创建时间(long 时间) {
this.setCreationTime(java.nio.file.attribute.FileTime.fromMillis(时间));
}

// 返回条目未压缩时大小，如果未知，则为-1
public long 未压缩大小() {
return this.getSize();
}

// 设置条目未压缩时大小
public void 未压缩大小(long 大小) {
this.setSize(大小);
}

// 返回条目压缩后大小，如果未知，则为-1
public long 压缩后大小() {
return this.getCompressedSize();
}

// 设置条目压缩后大小
public void 压缩后大小(long 大小) {
this.setCompressedSize(大小);
}

// 返回未压缩条目数据的CRC-32校验和
public long CRC效验和() {
return this.getCrc();
}

// 设置未压缩条目数据的CRC-32校验和
public void CRC效验和(long crc) {
this.setCrc(crc);
}

// 返回条目的压缩方法，如果未指定，则为-1
public int 压缩方法() {
return this.getMethod();
}

// 设置压缩方法
public void 压缩方法(int 压缩方法) {
try {
this.setMethod(压缩方法);
} catch(IllegalArgumentException e) {
throw new RuntimeException("不正确的压缩方法");
}
}

// 返回条目的额外字段数据
public byte[] 额外字段数据() {
return this.getExtra();
}

// 为条目设置可选的额外字段数据
public void 额外字段数据(byte[] 数据) {
try {
this.setExtra(数据);
} catch(IllegalArgumentException e) {
throw new RuntimeException("数据不得超过65535个字节");
}
}

// 返回条目的注释内容
public String 注释内容() {
return this.getComment();
}

// 设置条目的可选注释内容
public void 注释内容(String 内容) {
try {
this.setComment(内容);
} catch(IllegalArgumentException e) {
throw new RuntimeException("内容不能超过65535个字节");
}
}

// 取本条目是否为名称以"/"结尾的文件夹条目
public boolean 是文件夹条目() {
return this.isDirectory();
}
}