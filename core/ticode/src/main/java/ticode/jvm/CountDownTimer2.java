package ticode.jvm;


public class CountDownTimer2 {
private android.os.CountDownTimer timer;

//开始倒计时
public void 开始倒计时(long 倒计时时长, long 间隔时长) {
timer = new android.os.CountDownTimer(倒计时时长, 间隔时长) {
@Override
public void onTick(long p1) {
正在倒计时(p1);
}

@Override
public void onFinish() {
倒计时结束();
}
};
timer.start();
}

//关闭倒计时器，关闭以后无法再使用倒计时器，需重新创建倒计时器
public void 关闭() {
timer.cancel();
}

public void 正在倒计时(long 剩余时长) { } // 事件

public void 倒计时结束() { } // 事件
}