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

public class ZIP输入流 extends java.util.zip.ZipInputStream {

public void 赋值_op(输入流 输入流1) {
return new ZipInputStream(输入流1);
}

public static ZIP输入流 指定编码创建(输入流 输入流1, String 编码) {
return new ZipInputStream(输入流1,java.nio.charset.Charset.forName(编码));
}

// 调用此方法获取到下一个文件条目，之后可调用 读到字节集() 方法获取该条目数据
public ZIP条目 获取并打开下一个条目() {
try {
return this.getNextEntry();
} catch(java.io.IOException e) {
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