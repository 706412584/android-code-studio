package ticode.base;

import android.os.*;
import java.util.concurrent.*;

public class JLong extends PrimitiveTemplate<LongBox> {

public static String 到十六进制(long 值) {
return Long.toHexString(值);
}

public static String 到八进制(long 值) {
return Long.toOctalString(值);
}

public static String 到二进制(long 值) {
return Long.toBinaryString(值);
}

}