package ticode.zh.android;


public class 触摸动作 {
public static final int 按下;

public static final int 移动;

public static final int 抬起;

public static final int 多点按下;

public static final int 多点抬起;

public static final int 取消;

static {
按下 = android.view.MotionEvent.ACTION_DOWN;
移动 = android.view.MotionEvent.ACTION_MOVE;
抬起 = android.view.MotionEvent.ACTION_UP;
多点按下 = android.view.MotionEvent.ACTION_POINTER_DOWN;
多点抬起 = android.view.MotionEvent.ACTION_POINTER_UP;
取消 = android.view.MotionEvent.ACTION_CANCEL;
}
}