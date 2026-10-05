package ticode.android;

import android.os.Message;

public class JThread extends AndroidThread {
private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper()){
@Override
public void handleMessage(Message2 msg) {
更新主线程(msg);
}
};

@Override
public void run() {
被启动();
}





public void 通知_更新主线程(Message2 欲发送消息) {
if (欲发送消息 == null) {
handler.sendEmptyMessage(0);
} else {
handler.sendMessage(欲发送消息);
}
}

//线程内部处理器接收到发送的消息时触发该事件，一般在该处进行界面更新处理
public void 更新主线程(Message2 来源消息) { } // 事件

//线程被启动时触发该事件，用户可在该事件进行耗时操作
public void 被启动() { } // 事件
}