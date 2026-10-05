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

public class ZIP输出流 extends java.util.zip.ZipOutputStream {

public void 赋值_op(输出流 输出流1) {
return new ZipOutputStream(输出流1);
}

public static ZIP输出流 指定编码创建(输出流 输出流1, String 编码) {
return new ZipOutputStream(输出流1,java.nio.charset.Charset.forName(编码));
}

// 设置ZIP文件注释内容
public void 注释内容(String 内容) {
try {
this.setComment(内容);
} catch(IllegalArgumentException e) {
throw new RuntimeException("内容不能超过65535个字节");
}
}

// 设置默认压缩方法，取值：ZIP条目.压缩方法_xxx
public void 压缩方法(int 压缩方法) {
try {
this.setMethod(压缩方法);
} catch(IllegalArgumentException e) {
throw new RuntimeException("不正确的压缩方法");
}
}

// 设置默认压缩级别 可取值：0-9 或 压缩器.压缩等级_xxx
public void 压缩等级(int 等级) {
try {
this.setLevel(等级);
} catch(IllegalArgumentException e) {
throw new RuntimeException("无效的压缩等级：" + 等级);
}
}

// 调用此方法向ZIP中添加一个条目，之后可调用 写到字节集() 方法写入该文件条目数据，直到调用 关闭当前条目()
public void 添加并打开条目(ZIP条目 条目) {
try {
this.putNextEntry(条目);
} catch (java.io.IOException e) {
throw new RuntimeException(e.getMessage());
}
}

// 关闭当前打开文件条目
public void 关闭当前条目() {
try {
this.closeEntry();
} catch (java.io.IOException e) {
throw new RuntimeException(e.getMessage());
}
}

}