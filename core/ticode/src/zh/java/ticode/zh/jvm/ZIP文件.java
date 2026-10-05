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

public class ZIP文件 extends java.util.zip.ZipFile {

public void 赋值_op(String 路径) {
try {
return new ZipFile(new File(路径));
} catch (java.io.IOException e) {
throw new RuntimeException("文件读取错误：" + e.getMessage());
}
}

public static ZIP文件 指定编码创建(String 路径, String 编码) {
try {
return new ZipFile(new File(路径), java.nio.charset.Charset.forName(编码));
} catch (java.io.IOException e) {
throw new RuntimeException("文件读取错误：" + e.getMessage());
}
}

// 返回ZIP文件的路径名
public String 取名称() {
return this.getName();
}

// 返回ZIP文件注释，如果没有，则返回空对象
public String 取注释内容() {
try {
return this.getComment();
} catch(IllegalStateException e) {
throw new RuntimeException("ZIP文件已关闭");
}
}

// 返回ZIP文件中的条目数
public int 取条目数量() {
try {
return this.size();
} catch(IllegalStateException e) {
throw new RuntimeException("ZIP文件已关闭");
}
}

// 返回ZIP文件中指定路径的条目，如果未找到，则返回空对象
public ZIP条目 取条目(String 条目路径) {
try {
return this.getEntry(条目路径);
} catch(IllegalStateException e) {
throw new RuntimeException("ZIP文件已关闭");
}
}

// 返回ZIP文件中的所有条目
public ZIP条目[] 取所有条目() {
try {
java.util.stream.Stream<? extends java.util.zip.ZipEntry> stream = this.stream();
if(stream == null) {
return new ZipEntry[0];
}
return stream.toArray(new java.util.function.IntFunction<ZipEntry[]>() {
public ZipEntry[] apply(int size) {
return new ZipEntry[size];
}
});
} catch(IllegalStateException e) {
throw new RuntimeException("ZIP文件已关闭");
}
}

// 返回用于读取此ZIP条目内容的输入流
public 输入流 取输入流(ZIP条目 条目) {
try {
return this.getInputStream(条目);
} catch (java.io.IOException e) {
throw new RuntimeException("文件读取错误：" + e.getMessage());
} catch(IllegalStateException e) {
throw new RuntimeException("ZIP文件已关闭");
}
}

// 关闭ZIP文件
public void 关闭() {
try {
this.close();
} catch (Exception e) {
e.printStackTrace();
}
}

}