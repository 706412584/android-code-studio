package ticode.zh.android;

import android.os.Message;

public class 消息处理器 extends android.os.Handler {

public void handleMessage(Message msg)
{
处理消息(msg);
}

public void 发送消息(android.os.Message 值) {
sendMessage(值);
}

public void 发送延时消息(android.os.Message 值, long 时长) {
sendMessageDelayed(值,时长);
}

public void 处理消息(android.os.Message 值) {
}

}