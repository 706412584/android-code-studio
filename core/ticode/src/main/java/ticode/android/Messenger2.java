package ticode.android;

import android.os.Message;

public class Messenger2 extends android.os.Messenger {

public static Messenger2 新建对象(Handler2 处理器) {
return new android.os.Messenger(处理器);
}

public static Messenger2 新建对象2(EventBus2 中间件) {
return new android.os.Messenger(中间件);
}

public void 发送(Message2 值) {
try {
this.send(值);
} catch (android.os.RemoteException e) {
e.printStackTrace();
}
}

public EventBus2 取通信中间件() {
return this.getBinder();
}

}