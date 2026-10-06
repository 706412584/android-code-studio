package ticode.android;

import android.os.Message;

public class Handler2 extends android.os.Handler {

public void handleMessage(Message msg)
{
处理消息(msg);
}

public void 发送消息(Message2 值) {
sendMessage(值);
}

public void 发送延时消息(Message2 值, long 时长) {
sendMessageDelayed(值,时长);
}

public void 处理消息(Message2 值) {
}

}