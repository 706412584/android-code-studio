package ticode.zh.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class 拖放动作 {
public static final 拖放动作 开始拖放;

public static final 拖放动作 结束拖放;

public static final 拖放动作 放下;

static {
开始拖放 = android.view.DragEvent.ACTION_DRAG_STARTED;
结束拖放 = android.view.DragEvent.ACTION_DRAG_ENDED;
放下 = android.view.DragEvent.ACTION_DROP;
}
}