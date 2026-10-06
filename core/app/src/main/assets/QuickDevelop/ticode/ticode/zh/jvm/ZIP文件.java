package ticode.zh.jvm;

import java.io.File;
import java.util.zip.*;
import java.io.*;

public class ZIP文件 extends java.util.zip.ZipFile {
public ZIP文件(java.io.File 文件1, java.nio.charset.Charset 编码) throws Exception { super(文件1, 编码); }

public ZIP文件(String 路径) throws Exception { super(new File(路径)); }

public static ZIP文件 指定编码创建(String 路径, String 编码) {
try {
return new ZIP文件(new File(路径), java.nio.charset.Charset.forName(编码));
} catch (Exception e) {
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
java.util.zip.ZipEntry 原生条目 = this.getEntry(条目路径);
return 原生条目 == null ? null : new ZIP条目(原生条目);
} catch(IllegalStateException e) {
throw new RuntimeException("ZIP文件已关闭");
}
}

// 返回ZIP文件中的所有条目
public ZIP条目[] 取所有条目() {
try {
// stream 的元素是原生 ZipEntry，必须逐个包装成 ZIP条目（否则 toArray 触发 ArrayStoreException）
return this.stream().map(e -> new ZIP条目(e)).toArray(ZIP条目[]::new);
} catch(IllegalStateException e) {
throw new RuntimeException("ZIP文件已关闭");
}
}

// 返回用于读取此ZIP条目内容的输入流
public java.io.InputStream 取输入流(ZIP条目 条目) {
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