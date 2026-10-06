package ticode.base;

import android.os.*;
import java.util.concurrent.*;

public class PrimitiveTemplate<T1> {



public String 到文本() {
return String.valueOf(this);
}




public T1 到对象() {
return (T1) this;
}
}