package ticode.base;

import android.os.*;
import java.util.concurrent.*;

public class IntegerBox extends Integer {



public int 到基本类型() {
return (int) this;
}
public int 整数值() {
return this.intValue();
}
public long 长整数值() {
return this.longValue();
}
public double 小数值() {
return this.doubleValue();
}
public float 单精度小数值() {
return this.floatValue();
}
public byte 字节值() {
return this.byteValue();
}
}