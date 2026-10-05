package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public abstract class GZIP输出流 extends java.util.zip.GZIPOutputStream {

public GZIP输出流 赋值_op(输出流 输出流1) {
try {
return (GZIP输出流)new GZIPOutputStream(输出流1);
} catch(java.io.IOException e) {
throw new RuntimeException(e.getMessage());
}
}

}