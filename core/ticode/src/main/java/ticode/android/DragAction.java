package ticode.android;


public class DragAction {
public static final int 开始拖放;

public static final int 结束拖放;

public static final int 放下;

static {
开始拖放 = android.view.DragEvent.ACTION_DRAG_STARTED;
结束拖放 = android.view.DragEvent.ACTION_DRAG_ENDED;
放下 = android.view.DragEvent.ACTION_DROP;
}
}