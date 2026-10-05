
import android.content.Intent;
import android.graphics.Path;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityService;

public class 简易无障碍 {
private static AccessibilityService instance;

protected void onServiceConnected() {
super.onServiceConnected();
instance = this;
}

public void onAccessibilityEvent(AccessibilityEvent event) {
}

public void onInterrupt() {
}

public void 申请权限(安卓环境 环境) {
if (instance != null) return;
Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
环境.startActivity(intent);
}

public void 点击坐标(float X坐标, float Y坐标) {
if (instance == null) return;
Path path = new Path();
path.moveTo(X坐标, Y坐标);
GestureDescription gd = new GestureDescription.Builder()
.addStroke(new GestureDescription.StrokeDescription(path, 0, 1))
.build();
instance.dispatchGesture(gd, null, null);
}

public void 拖动坐标(float 起始X坐标, float 起始Y坐标, float 结束X坐标, float 结束Y坐标) {
if (instance == null) return;
Path path = new Path();
path.moveTo(起始X坐标, 起始Y坐标);
path.lineTo(结束X坐标, 结束Y坐标);
GestureDescription gd = new GestureDescription.Builder()
.addStroke(new GestureDescription.StrokeDescription(path, 0, 1))
.build();
instance.dispatchGesture(gd, null, null);
}
}