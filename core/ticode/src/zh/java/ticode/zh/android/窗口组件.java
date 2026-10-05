package ticode.zh.android;

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

public class 窗口组件 extends 可标记类 {
protected Context context;

public 窗口组件(Context context) {
this(context, true);
}

public 窗口组件(Context context, boolean dispatchEvent) {
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

public android.content.Context 取安卓环境() {
return context;
}

public 安卓窗口 取安卓窗口() {
return (安卓窗口)context;
}
}