package ticode.base;

import android.os.*;
import java.util.concurrent.*;

public class PrimitiveBoxTemplate<T1> {



public String 到文本() {
return String.valueOf(this);
}




public T1 到基本类型() {
return (T1) this;
}
}