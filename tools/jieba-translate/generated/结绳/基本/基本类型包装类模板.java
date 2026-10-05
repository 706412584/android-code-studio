package 结绳.基本;

import android.os.*;
import java.util.List;
import java.util.concurrent.*;

public class 基本类型包装类模板<T1> {



public String 到文本() {
return String.valueOf(this);
}




public T1 到基本类型() {
return (T1) this;
}
}

