package ticode.zh.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class 触摸动作 extends android.view.InputEvent {
public static final 触摸动作 按下;

public static final 触摸动作 移动;

public static final 触摸动作 抬起;

public static final 触摸动作 多点按下;

public static final 触摸动作 多点抬起;

public static final 触摸动作 取消;

static {
按下 = android.view.MotionEvent.ACTION_DOWN;
移动 = android.view.MotionEvent.ACTION_MOVE;
抬起 = android.view.MotionEvent.ACTION_UP;
多点按下 = android.view.MotionEvent.ACTION_POINTER_DOWN;
多点抬起 = android.view.MotionEvent.ACTION_POINTER_UP;
取消 = android.view.MotionEvent.ACTION_CANCEL;
}
}