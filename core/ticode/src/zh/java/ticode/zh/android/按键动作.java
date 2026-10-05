package ticode.zh.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class 按键动作 {
public static final 按键动作 按下;

public static final 按键动作 放开;

public static final 按键动作 同时按下多个;

static {
按下 = android.view.KeyEvent.ACTION_DOWN;
放开 = android.view.KeyEvent.ACTION_UP;
同时按下多个 = android.view.KeyEvent.ACTION_MULTIPLE;
}
}