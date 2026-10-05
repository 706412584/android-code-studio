package ticode.zh.jvm;

import java.util.zip.*;
import java.io.*;

public abstract class GZIP输入流 extends java.util.zip.GZIPInputStream {

public GZIP输入流 赋值_op(输入流 输入流1) {
try {
return (GZIP输入流)new GZIPInputStream(输入流1);
} catch(java.io.IOException e) {
throw new RuntimeException(e.getMessage());
}
}

}