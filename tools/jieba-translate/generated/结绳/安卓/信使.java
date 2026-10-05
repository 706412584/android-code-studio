package 结绳.安卓;

import android.os.Message;

public class 信使 {

public 信使 新建对象(消息处理器 处理器) {
return new android.os.Messenger(处理器);
}

public 信使 新建对象2(通信中间件 中间件) {
return new android.os.Messenger(中间件);
}

public void 发送(消息 值) {
try {
this.send(值);
} catch (android.os.RemoteException e) {
e.printStackTrace();
}
}

public 通信中间件 取通信中间件() {
return this.getBinder();
}

}

