package ticode.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class TouchAction extends android.view.InputEvent {
public static final TouchAction 按下;

public static final TouchAction 移动;

public static final TouchAction 抬起;

public static final TouchAction 多点按下;

public static final TouchAction 多点抬起;

public static final TouchAction 取消;

static {
按下 = android.view.MotionEvent.ACTION_DOWN;
移动 = android.view.MotionEvent.ACTION_MOVE;
抬起 = android.view.MotionEvent.ACTION_UP;
多点按下 = android.view.MotionEvent.ACTION_POINTER_DOWN;
多点抬起 = android.view.MotionEvent.ACTION_POINTER_UP;
取消 = android.view.MotionEvent.ACTION_CANCEL;
}
}