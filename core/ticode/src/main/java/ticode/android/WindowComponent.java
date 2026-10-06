package ticode.android;

import android.content.Context;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public class WindowComponent extends Markable {
protected Context context;

public WindowComponent(Context context) {
this(context, true);
}

public WindowComponent(Context context, boolean dispatchEvent) {
this.context = context;
if (dispatchEvent) {
onInit();
创建完毕();
}
}

protected void onInit() {
}

public void 创建完毕() {
}

public AndroidEnv 取安卓环境() {
return context;
}

public AndroidActivity 取安卓窗口() {
return (AndroidActivity)context;
}
}