package ticode.zh.android;

import android.view.View;
import android.content.Context;
import android.graphics.Canvas;

public class 画板 extends 可视化组件 {
public 画板(Context context) {
super(context);
}

@Override
public View onCreateView(Context context) {
View view = new View(context) {
@Override
protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
super.onMeasure(widthMeasureSpec, heightMeasureSpec);
被测量(widthMeasureSpec, heightMeasureSpec);
}

@Override
protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
super.onLayout(changed, left, top, right, bottom);
被布局(changed, left, top, right - left, bottom - top);
}

@Override
protected void onSizeChanged(int w, int h, int oldw, int oldh) {
super.onSizeChanged(w, h, oldw, oldh);
被改变(w, h, oldw, oldh);
}

@Override
protected void onDraw(Canvas canvas) {
super.onDraw(canvas);
绘制操作(canvas);
}
};
return view;
}

@Override
public View getView() {
return view;
}

public void 被测量(int 宽度, int 高度) { } // 事件

public void 被布局(boolean 是否变化, int 左, int 上, int 宽度, int 高度) { } // 事件

public void 被改变(int 新宽度, int 新高度, int 旧宽度, int 旧高度) { } // 事件

// 参数用原生 Canvas：框架在 onDraw/lockCanvas 里创建的是原生 Canvas，
// 无法强转成 画布对象（那是 ticode 的子类，此处不存在实例）。
public void 绘制操作(android.graphics.Canvas 画布) { } // 事件
}