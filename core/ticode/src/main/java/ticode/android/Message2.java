package ticode.android;

import android.os.Message;

public class Message2 extends android.os.Message {

public static Message2 获取消息(Handler2 处理器, int 标记值) {
return Message2.obtain(处理器,标记值);
}

public void 置数据(Bundle2 数据) {
this.setData(数据);
}

public Bundle2 取数据包() {
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