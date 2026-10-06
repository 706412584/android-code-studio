package ticode.android;


public class KeyAction {
public static final int 按下;

public static final int 放开;

public static final int 同时按下多个;

static {
按下 = android.view.KeyEvent.ACTION_DOWN;
放开 = android.view.KeyEvent.ACTION_UP;
同时按下多个 = android.view.KeyEvent.ACTION_MULTIPLE;
}
}