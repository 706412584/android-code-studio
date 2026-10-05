package ticode.zh.android;


public class 信使 {

public static android.os.Messenger 新建对象(消息处理器 处理器) {
return new android.os.Messenger(处理器);
}

public static android.os.Messenger 新建对象2(android.os.IBinder 中间件) {
return new android.os.Messenger(中间件);
}

public void 发送(android.os.Message 值) {}

public android.os.IBinder 取通信中间件() {return null; }

}