package ticode.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class DragAction {
public static final DragAction 开始拖放;

public static final DragAction 结束拖放;

public static final DragAction 放下;

static {
开始拖放 = android.view.DragEvent.ACTION_DRAG_STARTED;
结束拖放 = android.view.DragEvent.ACTION_DRAG_ENDED;
放下 = android.view.DragEvent.ACTION_DROP;
}
}