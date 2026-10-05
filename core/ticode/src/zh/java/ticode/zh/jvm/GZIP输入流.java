package ticode.zh.jvm;

import java.util.zip.*;
import java.io.File;
import java.io.*;

public class GZIP输入流 extends java.util.zip.GZIPInputStream {

public void 赋值_op(输入流 输入流1) {
try {
return new GZIPInputStream(输入流1);
} catch(java.io.IOException e) {
throw new RuntimeException(e.getMessage());
}
}

}