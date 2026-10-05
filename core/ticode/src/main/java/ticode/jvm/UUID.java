package ticode.jvm;

import java.util.Timer;
import java.util.TimerTask;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;
import java.util.UUID;

public class UUID extends java.util.UUID {
public static UUID 新建对象(int 最大范围, int 最小范围) {
return new UUID(最大范围,最小范围);
}

public static UUID 取随机标识符() {
return UUID.randomUUID();
}

public static UUID 从字节集创建(byte[] 标识名) {
return UUID.nameUUIDFromBytes(标识名);
}

public static UUID 从文本创建(String 标识名) {
return UUID.fromString(标识名);
}

public long 最大有效范围() {
return this.getLeastSignificantBits();
}

public long 最小有效范围() {
return this.getMostSignificantBits();
}

public int 版本() {
return this.version();
}

public int 关联变量号() {
return this.variant();
}

public long 时间戳() {
return this.timestamp();
}

public int 时间序列() {
return this.clockSequence();
}

public long 节点() {
return this.node();
}

public String 到文本() {
return this.toString();
}

}