package ticode.zh.android;

import android.os.Message;

public class 消息 extends android.os.Message {

public static 消息 获取消息(消息处理器 处理器, int 标记值) {
return 消息.obtain(处理器,标记值);
}

public void 置数据(数据包 数据) {
this.setData(数据);
}

public 数据包 取数据包() {
return this.getData();
}

public int 标记值() {
return this.what;
}

public void 标记值(int 值) {
this.what = 值;
}

public Object 参数() {
return this.obj;
}

public void 参数(Object 值) {
this.obj = 值;
}
}