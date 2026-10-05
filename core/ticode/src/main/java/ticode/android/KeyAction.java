package ticode.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class KeyAction extends android.view.DragEvent {
public static final KeyAction 按下;

public static final KeyAction 放开;

public static final KeyAction 同时按下多个;

static {
按下 = android.view.KeyEvent.ACTION_DOWN;
放开 = android.view.KeyEvent.ACTION_UP;
同时按下多个 = android.view.KeyEvent.ACTION_MULTIPLE;
}
}